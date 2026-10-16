package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.ShrinkagePolicy;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentCount;
import com.myplus.marketplace.multiseller.entity.MarketplaceShrinkageClaim;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentCountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceShrinkageClaimRepository;

/** MKT-5d — counts of consigned stock, shrinkage claims and write-offs. */
@ExtendWith(MockitoExtension.class)
class ShrinkageServiceTest {

    static final long OWNER = 7L, WAREHOUSE = 23L, PHONE = 900L, OWNER_PRODUCT = 41L;

    @Mock MarketplaceConsignmentCountRepository counts;
    @Mock MarketplaceShrinkageClaimRepository claims;
    @Mock MarketplaceConsignedStockRepository consigned;
    @Mock MarketplaceConsignmentAgreementRepository agreements;
    @Mock MarketplaceProductRepository products;
    @Mock ConsignmentAgreementService consignments;
    @Mock ConsignedSaleService sale;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceSettlementService settlement;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;
    @Mock TradeClient trade;
    @Mock InventoryClient inventory;
    @Mock PlatformTransactionManager txManager;
    @Mock MarketplaceConsignedReturnRepository returns;
    ShrinkageService service;
    MarketplaceConsignmentAgreement agreement;
    List<MarketplaceConsignedStock> ledger = new ArrayList<>();

    @BeforeEach
    void wire() {
        service = new ShrinkageService(counts, claims, consigned, agreements, products, consignments, sale, settings,
                settlement, access, audit, trade, inventory, txManager, returns);
        agreement = new MarketplaceConsignmentAgreement();
        agreement.setId(3L);
        agreement.setConsignorOrgId(OWNER);
        agreement.setConsigneeOrgId(WAREHOUSE);
        agreement.setShrinkageTolerancePct(new BigDecimal("1.00"));
        agreement.setClaimWindowDays(14);
        agreement.setProofRequired(true);
        agreement.setDamageBearer("CONSIGNEE");
        agreement.setExpiryBearer("CONSIGNOR");
        agreement.setCountEveryDays(30);
        lenient().when(consignments.activeFor(OWNER, WAREHOUSE)).thenReturn(Optional.of(agreement));
        lenient().when(agreements.findById(3L)).thenReturn(Optional.of(agreement));
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(WAREHOUSE));
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(sale.name(OWNER)).thenReturn("Mobile Distributor");
        lenient().when(counts.save(any())).thenAnswer(i -> {
            MarketplaceConsignmentCount c = i.getArgument(0);
            if (c.getId() == null) c.setId(11L);
            if (c.getVersion() == null) c.setVersion(0);
            return c;
        });
        lenient().when(claims.save(any())).thenAnswer(i -> {
            MarketplaceShrinkageClaim c = i.getArgument(0);
            if (c.getId() == null) c.setId(5L);
            if (c.getVersion() == null) c.setVersion(0);
            return c;
        });
        lenient().when(consigned.save(any())).thenAnswer(i -> {
            ledger.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    MarketplaceConsignmentCount countOf(int expected, int counted, int tolerated) {
        MarketplaceConsignmentCount c = new MarketplaceConsignmentCount();
        c.setId(11L);
        c.setVersion(0);
        c.setAgreementId(3L);
        c.setConsignorOrgId(OWNER);
        c.setConsigneeOrgId(WAREHOUSE);
        c.setMktProductId(PHONE);
        c.setProductName("Galaxy A32");
        c.setExpected(expected);
        c.setCounted(counted);
        c.setMissing(Math.max(0, expected - counted));
        c.setTolerated(tolerated);
        c.setCountedAt(LocalDateTime.now());
        lenient().when(counts.lockById(11L)).thenReturn(Optional.of(c));
        lenient().when(counts.findById(11L)).thenReturn(Optional.of(c));
        return c;
    }

    void lots(int... left) {
        List<ConsignedSaleService.Lot> ls = new ArrayList<>();
        for (int i = 0; i < left.length; i++)
            ls.add(new ConsignedSaleService.Lot(100L + i, "MKT-CONSIGN-" + i, OWNER_PRODUCT, left[i], new BigDecimal("40000.00")));
        lenient().when(sale.lots(OWNER, WAREHOUSE, PHONE)).thenReturn(ls);
    }

    // ── the count ──

    @Test
    @DisplayName("[MKT-R20.5] [MKT-R12.2] count: only the warehouse counts; missing units leave the listing at once, tolerance rounds down")
    void countRecordsWhatIsMissing() {
        when(access.org()).thenReturn(OWNER);
        assertThatThrownBy(() -> service.count(new TransferDTOs.CountRequest(OWNER, PHONE, 4, null, "k1")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Only the MaxTheService warehouse counts");

        when(access.org()).thenReturn(WAREHOUSE);
        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(5L);
        TransferDTOs.CountRow r = service.count(new TransferDTOs.CountRequest(OWNER, PHONE, 4, "shelf B", "k1"));
        assertThat(r.expected()).isEqualTo(5);
        assertThat(r.missing()).isEqualTo(1);
        assertThat(r.tolerated()).as("1% of 5 is no whole unit").isZero();
        assertThat(r.claimable()).isEqualTo(1);
        assertThat(r.writable()).isEqualTo(1);
        assertThat(r.claimBy()).isEqualTo(LocalDateTime.now().toLocalDate().plusDays(14));
        verify(agreements).lockById(3L);
        verify(sale).resync(OWNER, WAREHOUSE, PHONE);
        verify(audit).event(eq("MKT_CONSIGN_COUNTED"), anyString(), anyString(), eq(OWNER), any(), eq("5"), eq("4"), any(), anyString());

        assertThat(ShrinkagePolicy.tolerated(200, new BigDecimal("1.00"))).isEqualTo(2);
        assertThat(ShrinkagePolicy.tolerated(5, BigDecimal.ZERO)).isZero();
    }

    @Test
    @DisplayName("[MKT-R20.5] count: refused while some of the stock waits to go back to its owner (MKT-5e): it is on the shelf but not in the balance")
    void countWaitsForAReturn() {
        when(access.org()).thenReturn(WAREHOUSE);
        when(returns.units(eq(OWNER), eq(WAREHOUSE), eq(PHONE), any())).thenReturn(2L);
        assertThatThrownBy(() -> service.count(new TransferDTOs.CountRequest(OWNER, PHONE, 5, null, "k9")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Some of this stock is waiting to go back to Mobile Distributor. Hand it over (or cancel the return) first, then count.");
        verify(counts, never()).save(any());
    }

    @Test
    @DisplayName("[MKT-R20.5] count: a repeat with the same key replays; a match or a surplus moves nothing; none held is refused")
    void countReplaysAndMatches() {
        when(access.org()).thenReturn(WAREHOUSE);
        MarketplaceConsignmentCount first = countOf(5, 4, 0);
        when(counts.findByConsigneeOrgIdAndIdempotencyKey(WAREHOUSE, "k1")).thenReturn(Optional.of(first));
        assertThat(service.count(new TransferDTOs.CountRequest(OWNER, PHONE, 4, null, "k1")).id()).isEqualTo(11L);
        verify(counts, never()).save(any());

        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(5L);
        TransferDTOs.CountRow more = service.count(new TransferDTOs.CountRequest(OWNER, PHONE, 6, null, "k2"));
        assertThat(more.missing()).isZero();

        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(0L);
        assertThatThrownBy(() -> service.count(new TransferDTOs.CountRequest(OWNER, PHONE, 0, null, "k3")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("holds none");
        assertThatThrownBy(() -> service.count(new TransferDTOs.CountRequest(OWNER, PHONE, -1, null, "k4")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("how many you counted");
    }

    // ── the claim ──

    @Test
    @DisplayName("[MKT-R12.2] [MKT-R12.3] [MKT-R12.4] claim: needs proof, follows the agreement's bearer, stays within what is claimable; OPEN, nothing paid")
    void claimIsOpenAndPaysNothing() {
        when(access.org()).thenReturn(OWNER);
        countOf(5, 4, 0);
        lots(3, 2);

        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "THEFT", 1, "", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("needs proof");
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "EXPIRY", 1, "photos", null)))
                .as("the agreement puts expiry on the owner")
                .isInstanceOf(ValidationException.class).hasMessageContaining("expiry is yours to bear");
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "SHORT_SHIPMENT", 1, "photos", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("a short shipment is yours to bear");
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "THEFT", 2, "CCTV 14:02", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Only 1 of CC-000011");
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "LOST_IT", 1, "CCTV 14:02", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("what happened");

        TransferDTOs.ClaimRow c = service.claim(new TransferDTOs.ClaimRequest(11L, "damage", 1, "Photo of the cracked box", null));
        assertThat(c.status()).isEqualTo(MarketplaceShrinkageClaim.OPEN);
        assertThat(c.responsible()).isEqualTo("CONSIGNEE");
        assertThat(c.amount()).isEqualByComparingTo("40000.00");
        assertThat(c.countNo()).isEqualTo("CC-000011");
        verify(settlement, never()).creditClaim(anyLong(), any(), anyString(), anyString());
        verify(inventory, never()).adjustStock(any());
    }

    @Test
    @DisplayName("[MKT-R12.2] claim: refused after the claim window, within the tolerance, and for someone else's count")
    void claimWindowAndTolerance() {
        when(access.org()).thenReturn(OWNER);
        MarketplaceConsignmentCount c = countOf(200, 198, 2);
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "THEFT", 1, "CCTV 14:02", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("within the agreement's tolerance");

        c.setTolerated(0);
        c.setCountedAt(LocalDateTime.now().minusDays(15));
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "THEFT", 1, "CCTV 14:02", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("closed on");

        when(access.org()).thenReturn(99L);
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "THEFT", 1, "CCTV 14:02", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("No such count");
    }

    // ── the decisions ──

    MarketplaceShrinkageClaim openClaim(int units) {
        MarketplaceShrinkageClaim m = new MarketplaceShrinkageClaim();
        m.setId(5L);
        m.setVersion(0);
        m.setCountId(11L);
        m.setAgreementId(3L);
        m.setConsignorOrgId(OWNER);
        m.setConsigneeOrgId(WAREHOUSE);
        m.setMktProductId(PHONE);
        m.setProductName("Galaxy A32");
        m.setKind("THEFT");
        m.setResponsible("CONSIGNEE");
        m.setUnits(units);
        m.setAmount(new BigDecimal("40000.00").multiply(BigDecimal.valueOf(units)));
        m.setStatus(MarketplaceShrinkageClaim.OPEN);
        lenient().when(claims.findById(5L)).thenReturn(Optional.of(m));
        lenient().when(claims.findByCountIdInOrderByIdDesc(List.of(11L))).thenReturn(List.of(m));
        return m;
    }

    @Test
    @DisplayName("[MKT-R12.4] [MKT-R20.5] approve: the owner's books first, lot by lot and keyed by the claim, then LOST rows and the credit")
    void approvePaysTheOwner() {
        MarketplaceConsignmentCount c = countOf(5, 3, 0);
        MarketplaceShrinkageClaim m = openClaim(2);
        lots(1, 4);

        TransferDTOs.CountRow r = service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "APPROVE", null));

        verify(access).assertOperator();
        InOrder o = inOrder(trade, inventory, sale, settlement);
        o.verify(trade).releaseHold("MKT-CONSIGN-0");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        o.verify(inventory).adjustStock(body.capture());
        o.verify(sale).setAside(eq(OWNER), any(), eq(0));
        o.verify(trade).releaseHold("MKT-CONSIGN-1");
        o.verify(inventory).adjustStock(body.capture());
        o.verify(sale).setAside(eq(OWNER), any(), eq(3));
        o.verify(settlement).creditClaim(eq(OWNER), eq(new BigDecimal("80000.00")), eq("SC-000005"), anyString());
        assertThat(body.getAllValues().get(0)).containsEntry("adjustmentType", "DECREASE").containsEntry("quantity", 1)
                .containsEntry("idempotencyKey", "MKT-LOST-SC-000005-T100").containsEntry("productId", OWNER_PRODUCT);
        assertThat(body.getAllValues().get(1)).containsEntry("quantity", 1).containsEntry("idempotencyKey", "MKT-LOST-SC-000005-T101");

        assertThat(ledger).extracting(MarketplaceConsignedStock::getMovement).containsOnly(MarketplaceConsignedStock.LOST);
        assertThat(ledger).extracting(MarketplaceConsignedStock::getQuantity).containsExactly(-1, -1);
        assertThat(ledger).extracting(MarketplaceConsignedStock::getDocNo).containsOnly("SC-000005");
        assertThat(m.getStatus()).isEqualTo(MarketplaceShrinkageClaim.APPROVED);
        assertThat(m.getSettlementRef()).isEqualTo("SC-000005");
        assertThat(c.getApproved()).isEqualTo(2);
        assertThat(r.open()).isZero();
    }

    @Test
    @DisplayName("[MKT-R12.4] approve: refused books leave the claim OPEN, the lot set aside whole, and pay nothing")
    void approveBooksRefused() {
        countOf(5, 4, 0);
        MarketplaceShrinkageClaim m = openClaim(1);
        lots(5);
        when(inventory.adjustStock(any())).thenThrow(new IllegalStateException("inventory down"));

        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "APPROVE", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("could not be written off");
        verify(sale).setAside(eq(OWNER), any(), eq(5));
        verify(settlement, never()).creditClaim(anyLong(), any(), anyString(), anyString());
        assertThat(m.getStatus()).isEqualTo(MarketplaceShrinkageClaim.OPEN);
        assertThat(ledger).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R12.4] [MKT-R12.2] reject needs a reason and moves nothing; the units can then be written off; a decided claim is final")
    void rejectThenWriteOff() {
        MarketplaceConsignmentCount c = countOf(5, 4, 0);
        MarketplaceShrinkageClaim m = openClaim(1);
        lots(5);

        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "REJECT", " ")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Say why");
        service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "REJECT", "CCTV shows it shipped to order MKT-001286"));
        assertThat(m.getStatus()).isEqualTo(MarketplaceShrinkageClaim.REJECTED);
        verify(inventory, never()).adjustStock(any());
        verify(settlement, never()).creditClaim(anyLong(), any(), anyString(), anyString());
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "APPROVE", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already rejected");
        when(access.org()).thenReturn(OWNER);
        when(claims.rejectedUnits(11L)).thenReturn(1L);
        assertThatThrownBy(() -> service.claim(new TransferDTOs.ClaimRequest(11L, "THEFT", 1, "CCTV again", null)))
                .as("the dispute is over").isInstanceOf(ValidationException.class).hasMessageContaining("rejected (write those off)");

        TransferDTOs.CountRow r = service.decide(new TransferDTOs.ShrinkDecision(11L, 0, "WRITE_OFF", null));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(inventory).adjustStock(body.capture());
        assertThat(body.getValue()).containsEntry("quantity", 1).containsEntry("idempotencyKey", "MKT-LOST-CC-000011-W1-T100");
        verify(sale).setAside(eq(OWNER), any(), eq(4));
        assertThat(c.getWrittenOff()).isEqualTo(1);
        assertThat(c.getPending()).isZero();
        assertThat(c.getWriteOffs()).isEqualTo(1);
        assertThat(ledger).extracting(MarketplaceConsignedStock::getDocNo).containsExactly("CC-000011-W1");
        assertThat(r.writable()).isZero();
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(11L, null, "WRITE_OFF", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Nothing of CC-000011 is left");
    }

    @Test
    @DisplayName("[MKT-R12.2] write-off: units under a waiting claim stay out of it; a refused write-off gives the units back")
    void writeOffSkipsClaimedAndRollsBack() {
        when(access.org()).thenReturn(OWNER);
        MarketplaceConsignmentCount c = countOf(5, 3, 0);
        openClaim(1);
        when(claims.openUnits(11L)).thenReturn(1L);
        lots(5);
        doThrow(new IllegalStateException("hold service down")).when(trade).releaseHold(anyString());

        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(11L, 0, "WRITE_OFF", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("could not be let go");
        assertThat(c.getPending()).as("given back").isZero();
        assertThat(c.getWrittenOff()).isZero();
        assertThat(c.getWriteOffs()).as("the retry reuses the same keys").isZero();
        assertThat(ShrinkageService.writable(c, 1)).isEqualTo(1);
        assertThat(ShrinkageService.claimable(c, 1, 0)).isEqualTo(1);
        assertThat(ShrinkageService.claimable(c, 1, 1)).as("a rejected unit is not claimed again").isZero();
    }

    @Test
    @DisplayName("[MKT-R12.4] withdraw: only the owner's own OPEN claim; the operator decides only as the operator")
    void withdrawAndOperatorOnly() {
        countOf(5, 4, 0);
        MarketplaceShrinkageClaim m = openClaim(1);
        when(access.org()).thenReturn(99L);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "WITHDRAW", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("No such claim of yours");
        when(access.org()).thenReturn(OWNER);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(5L, 3, "WITHDRAW", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("changed meanwhile");
        service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "WITHDRAW", null));
        assertThat(m.getStatus()).isEqualTo(MarketplaceShrinkageClaim.WITHDRAWN);

        doThrow(new ValidationException("Only MaxTheService can do that.")).when(access).assertOperator();
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ShrinkDecision(5L, 0, "APPROVE", null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Only MaxTheService");
        verify(inventory, never()).adjustStock(any());
    }

    @Test
    @DisplayName("[MKT-R12.2] [MKT-R20.5] the screen: missing units, the next count due from the first receipt, who may count and claim")
    void decorateShowsCounts() {
        MarketplaceConsignmentCount c = countOf(5, 4, 0);
        when(counts.findTop10ByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdOrderByIdDesc(OWNER, WAREHOUSE, PHONE))
                .thenReturn(List.of(c));
        when(claims.findByCountIdInOrderByIdDesc(List.of(11L))).thenReturn(List.of());
        TransferDTOs.Held h = new TransferDTOs.Held("Mobile Distributor", PHONE, "Galaxy A32", 5, new BigDecimal("200000.00"),
                OWNER, 0, true, null);

        TransferDTOs.Shrink s = service.decorate(OWNER, WAREHOUSE, List.of(h)).get(0).shrink();
        assertThat(s.missing()).isEqualTo(1);
        assertThat(s.canClaim()).isTrue();
        assertThat(s.canCount()).isFalse();
        assertThat(s.countDue()).isEqualTo(c.getCountedAt().toLocalDate().plusDays(30));
        assertThat(s.counts()).singleElement().satisfies(r -> {
            assertThat(r.claimable()).isEqualTo(1);
            assertThat(r.countNo()).isEqualTo("CC-000011");
        });

        TransferDTOs.Shrink w = service.decorate(WAREHOUSE, WAREHOUSE, List.of(h)).get(0).shrink();
        assertThat(w.canCount()).isTrue();
        assertThat(w.canClaim()).isFalse();
    }

    @Test
    @DisplayName("[MKT-R12.1] [MKT-R12.3] R12.1 and R12.3: every kind of shrinkage, and the source's default table")
    void policyTable() {
        assertThat(ShrinkagePolicy.Kind.values()).hasSize(9);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.MERCHANT_STOCK_AT_MERCHANT)).isEqualTo(ShrinkagePolicy.Party.MERCHANT);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.PLATFORM_STOCK_AT_PLATFORM)).isEqualTo(ShrinkagePolicy.Party.PLATFORM);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.SUPPLIER_STOCK_AT_SUPPLIER)).isEqualTo(ShrinkagePolicy.Party.SUPPLIER);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.CONSIGNMENT_AT_CUSTODIAN)).isEqualTo(ShrinkagePolicy.Party.CUSTODIAN);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.SUPPLIER_SHORT_SHIPMENT)).isEqualTo(ShrinkagePolicy.Party.SUPPLIER);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.CARRIER_LOSS_AFTER_HANDOVER)).isEqualTo(ShrinkagePolicy.Party.CARRIER);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.EXPIRY_POOR_ROTATION)).isEqualTo(ShrinkagePolicy.Party.CUSTODIAN);
        assertThat(ShrinkagePolicy.byDefault(ShrinkagePolicy.Situation.EXPIRY_SHORT_SHELF_LIFE)).isEqualTo(ShrinkagePolicy.Party.AGREEMENT);

        for (ShrinkagePolicy.Kind k : List.of(ShrinkagePolicy.Kind.THEFT, ShrinkagePolicy.Kind.MISCOUNT,
                ShrinkagePolicy.Kind.CONSUMPTION, ShrinkagePolicy.Kind.DELIVERY_LOSS))
            assertThat(ShrinkagePolicy.forConsignment(k, "CONSIGNOR", "CONSIGNOR")).as(k.name()).isEqualTo("CONSIGNEE");
        assertThat(ShrinkagePolicy.forConsignment(ShrinkagePolicy.Kind.DAMAGE, "CONSIGNOR", "CONSIGNEE")).isEqualTo("CONSIGNOR");
        assertThat(ShrinkagePolicy.forConsignment(ShrinkagePolicy.Kind.BREAKAGE, "CONSIGNEE", "CONSIGNOR")).isEqualTo("CONSIGNEE");
        assertThat(ShrinkagePolicy.forConsignment(ShrinkagePolicy.Kind.SPOILAGE, "CONSIGNEE", "CONSIGNOR")).isEqualTo("CONSIGNOR");
        assertThat(ShrinkagePolicy.forConsignment(ShrinkagePolicy.Kind.EXPIRY, "CONSIGNOR", "CONSIGNEE")).isEqualTo("CONSIGNEE");
        assertThat(ShrinkagePolicy.forConsignment(ShrinkagePolicy.Kind.SHORT_SHIPMENT, "CONSIGNEE", "CONSIGNEE")).isEqualTo("CONSIGNOR");
    }
}
