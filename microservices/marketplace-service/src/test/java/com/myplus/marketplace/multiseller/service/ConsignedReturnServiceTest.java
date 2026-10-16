package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedReturn;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentCountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;

/** MKT-5e — unsold consigned stock goes back to its owner. */
@ExtendWith(MockitoExtension.class)
class ConsignedReturnServiceTest {

    static final long OWNER = 7L, WAREHOUSE = 23L, PHONE = 900L, OWNER_PRODUCT = 41L;

    @Mock MarketplaceConsignedReturnRepository returns;
    @Mock MarketplaceConsignedStockRepository consigned;
    @Mock MarketplaceConsignmentCountRepository counts;
    @Mock MarketplaceConsignmentAgreementRepository agreements;
    @Mock MarketplaceOrderLineRepository lines;
    @Mock MarketplaceProductRepository products;
    @Mock ConsignmentAgreementService consignments;
    @Mock ConsignedSaleService sale;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceSettlementService settlement;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;
    @Mock TradeClient trade;
    @Mock PlatformTransactionManager txManager;
    ConsignedReturnService service;
    MarketplaceConsignmentAgreement agreement;
    List<MarketplaceConsignedStock> ledger = new ArrayList<>();
    MarketplaceConsignedReturn stored;

    @BeforeEach
    void wire() {
        service = new ConsignedReturnService(returns, consigned, counts, agreements, lines, products, consignments, sale, settings,
                settlement, access, audit, trade, txManager);
        agreement = new MarketplaceConsignmentAgreement();
        agreement.setId(3L);
        agreement.setConsignorOrgId(OWNER);
        agreement.setConsigneeOrgId(WAREHOUSE);
        agreement.setUnsoldReturnDays(180);
        agreement.setUnsoldReturnBearer("CONSIGNEE");
        MarketplaceProduct p = new MarketplaceProduct();
        p.setCanonicalName("Galaxy A32");
        lenient().when(products.findById(PHONE)).thenReturn(Optional.of(p));
        lenient().when(consignments.activeFor(OWNER, WAREHOUSE)).thenReturn(Optional.of(agreement));
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(WAREHOUSE));
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(sale.name(OWNER)).thenReturn("Mobile Distributor");
        lenient().when(returns.save(any())).thenAnswer(i -> saved(i.getArgument(0)));
        lenient().when(returns.saveAndFlush(any())).thenAnswer(i -> saved(i.getArgument(0)));
        lenient().when(returns.lockById(5L)).thenAnswer(i -> Optional.ofNullable(stored));
        lenient().when(consigned.save(any())).thenAnswer(i -> {
            ledger.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    /** What the repository would do: an id on the first save, the version up on every later one. */
    MarketplaceConsignedReturn saved(MarketplaceConsignedReturn r) {
        if (r.getId() == null) { r.setId(5L); r.setVersion(0); }
        else r.setVersion(r.getVersion() + 1);
        stored = r;
        return r;
    }

    /** On the shelf {@code balance}, promised to orders {@code promised}, held past the term {@code aged}. */
    void shelf(long balance, long promised, long aged) {
        lenient().when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(balance);
        lenient().when(lines.consignedPromised(OWNER, WAREHOUSE, PHONE)).thenReturn(promised);
        lenient().when(consigned.heldSince(eq(OWNER), eq(WAREHOUSE), eq(PHONE), any())).thenReturn(aged);
    }

    void lots(int... left) {
        List<ConsignedSaleService.Lot> ls = new ArrayList<>();
        for (int i = 0; i < left.length; i++)
            ls.add(new ConsignedSaleService.Lot(100L + i, "MKT-CONSIGN-" + i, OWNER_PRODUCT, left[i], new BigDecimal("40000.00")));
        lenient().when(sale.lots(OWNER, WAREHOUSE, PHONE)).thenReturn(ls);
    }

    MarketplaceConsignedReturn ret(String status, String bearer, long requestedBy) {
        MarketplaceConsignedReturn r = new MarketplaceConsignedReturn();
        r.setId(5L);
        r.setVersion(0);
        r.setAgreementId(3L);
        r.setConsignorOrgId(OWNER);
        r.setConsigneeOrgId(WAREHOUSE);
        r.setMktProductId(PHONE);
        r.setProductName("Galaxy A32");
        r.setUnits(4);
        r.setReason(requestedBy == OWNER ? MarketplaceConsignedReturn.RECALL : MarketplaceConsignedReturn.UNSOLD);
        r.setBearer(bearer);
        r.setStatus(status);
        r.setRequestedByOrgId(requestedBy);
        r.setIdempotencyKey("k1");
        r.setRequestedAt(LocalDateTime.now());
        stored = r;
        return r;
    }

    // ── asking for it back ──

    @Test
    @DisplayName("[MKT-R20.5] [MKT-R2.1] the owner asks for its stock back any time: before the term at its own cost; no longer sold at once")
    void ownerAsksAnyTime() {
        when(access.org()).thenReturn(OWNER);
        shelf(5, 1, 0);   // 5 on the shelf, 1 promised to an order waiting, nothing past the term
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(null, PHONE, 5, null, "k1")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Only 4 can go back now: the rest is promised to orders, counted missing or already going back.");

        TransferDTOs.ReturnRow r = service.request(new TransferDTOs.ReturnRequest(WAREHOUSE, PHONE, 4, " season over ", "k1"));
        assertThat(r.returnNo()).isEqualTo("RT-000005");
        assertThat(r.status()).isEqualTo("REQUESTED");
        assertThat(r.byOwner()).isTrue();
        assertThat(r.reason()).isEqualTo("RECALL");
        assertThat(r.bearer()).as("asked for before the 180-day term: the owner pays").isEqualTo("CONSIGNOR");
        assertThat(stored.getConsignorOrgId()).as("the owner's own call names its own stock, whatever it sends").isEqualTo(OWNER);
        assertThat(stored.getNote()).isEqualTo("season over");
        assertThat(r.productName()).isEqualTo("Galaxy A32");
        verify(agreements, org.mockito.Mockito.times(2)).lockById(3L);   // the refused try and this one
        verify(sale).resync(OWNER, WAREHOUSE, PHONE);
        verify(audit).event(eq("MKT_CONSIGN_RETURN_ASKED"), anyString(), eq("RT-000005"), eq(OWNER), any(), any(), eq("REQUESTED"),
                any(), anyString());
        verify(trade, never()).releaseHold(anyString());
    }

    @Test
    @DisplayName("[MKT-R4.6] [MKT-R20.5] past the term the agreement says who pays; the warehouse sends back only what is past it")
    void warehouseOnlyPastTheTerm() {
        when(access.org()).thenReturn(WAREHOUSE);
        shelf(5, 0, 0);
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(OWNER, PHONE, 1, null, "w1")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("None of Mobile Distributor's stock of this has been held 180 days yet (the agreement's term), so it "
                        + "cannot be sent back. The owner can ask for it.");
        shelf(5, 0, 3);
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(OWNER, PHONE, 4, null, "w1")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Only 3 have been held longer than 180 days (the agreement's term), so only they can be sent back.");
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(null, PHONE, 1, null, "w1")))
                .isInstanceOf(ValidationException.class).hasMessage("Choose whose stock goes back.");

        TransferDTOs.ReturnRow r = service.request(new TransferDTOs.ReturnRequest(OWNER, PHONE, 3, null, "w1"));
        assertThat(r.byOwner()).isFalse();
        assertThat(r.reason()).isEqualTo("UNSOLD");
        assertThat(r.bearer()).as("the agreement's party").isEqualTo("CONSIGNEE");

        // the owner asking for no more than is past the term returns at the agreement's party's cost too
        stored = null;
        when(access.org()).thenReturn(OWNER);
        when(returns.findByRequestedByOrgIdAndIdempotencyKey(OWNER, "k2")).thenReturn(Optional.empty());
        TransferDTOs.ReturnRow mine = service.request(new TransferDTOs.ReturnRequest(null, PHONE, 3, null, "k2"));
        assertThat(mine.bearer()).isEqualTo("CONSIGNEE");
    }

    @Test
    @DisplayName("[MKT-R20.5] what is past the term leaves out units counted missing or already going back, and never exceeds what can go")
    void dueLeavesOutWhatIsGoing() {
        shelf(4, 1, 6);
        when(counts.openMissing(OWNER, WAREHOUSE, PHONE)).thenReturn(1L);
        when(returns.units(eq(OWNER), eq(WAREHOUSE), eq(PHONE), eq(MarketplaceConsignedReturn.UNDER_WAY))).thenReturn(2L);
        assertThat(service.returnable(OWNER, WAREHOUSE, PHONE)).isEqualTo(3);
        assertThat(service.due(agreement, OWNER, WAREHOUSE, PHONE, 3)).as("6 aged - 1 missing - 2 going = 3").isEqualTo(3);
        assertThat(service.due(agreement, OWNER, WAREHOUSE, PHONE, 2)).as("capped by what can go").isEqualTo(2);
        when(returns.units(eq(OWNER), eq(WAREHOUSE), eq(PHONE), eq(MarketplaceConsignedReturn.UNDER_WAY))).thenReturn(6L);
        assertThat(service.due(agreement, OWNER, WAREHOUSE, PHONE, 3)).isZero();
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(consigned, org.mockito.Mockito.atLeastOnce()).heldSince(eq(OWNER), eq(WAREHOUSE), eq(PHONE), cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(179)).isAfter(LocalDateTime.now().minusDays(181));
    }

    @Test
    @DisplayName("[MKT-R20.5] a request repeated with the same key replays; nothing on the shelf, no agreement, or no units is refused")
    void replayAndGuards() {
        when(access.org()).thenReturn(OWNER);
        MarketplaceConsignedReturn first = ret(MarketplaceConsignedReturn.REQUESTED, "CONSIGNOR", OWNER);
        when(returns.findByRequestedByOrgIdAndIdempotencyKey(OWNER, "k1")).thenReturn(Optional.of(first));
        assertThat(service.request(new TransferDTOs.ReturnRequest(null, PHONE, 4, null, "k1")).returnNo()).isEqualTo("RT-000005");
        verify(returns, never()).save(any());

        shelf(1, 1, 0);
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(null, PHONE, 1, null, "k3")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Nothing of this product can go back now: MaxTheService holds none for Mobile Distributor that is "
                        + "on the shelf and not promised to an order.");
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(null, PHONE, 0, null, "k3")))
                .isInstanceOf(ValidationException.class).hasMessage("Enter how many units go back, 1 or more.");
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(null, PHONE, 1, null, " ")))
                .isInstanceOf(ValidationException.class).hasMessage("The request is missing its idempotency key.");
        when(consignments.activeFor(OWNER, WAREHOUSE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.request(new TransferDTOs.ReturnRequest(null, PHONE, 1, null, "k3")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("There is no active consignment agreement");
    }

    // ── hand-over, receipt, cancel ──

    @Test
    @DisplayName("[MKT-R4.6] hand-over: the warehouse only, with a reference; a cost the owner bears is debited to it, the return its reference")
    void dispatchChargesTheBearer() {
        ret(MarketplaceConsignedReturn.REQUESTED, "CONSIGNOR", OWNER);
        when(access.org()).thenReturn(OWNER);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "DISPATCH", null, "TCS 4471")))
                .isInstanceOf(ValidationException.class).hasMessage("Only the MaxTheService warehouse hands stock back.");
        when(access.org()).thenReturn(WAREHOUSE);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "DISPATCH", null, " ")))
                .isInstanceOf(ValidationException.class).hasMessage("Give the carrier's reference, or who collected it.");
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "DISPATCH", new BigDecimal("-1"), "TCS 4471")))
                .isInstanceOf(ValidationException.class).hasMessage("Enter what sending it cost, from Rs 0 to Rs 100,000.");
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "DISPATCH", new BigDecimal("1.005"), "TCS 4471")))
                .isInstanceOf(ValidationException.class).hasMessage("Enter what sending it cost, from Rs 0 to Rs 100,000.");

        TransferDTOs.ReturnRow r = service.decide(new TransferDTOs.ReturnDecision(5L, 0, "DISPATCH", new BigDecimal("650"), "TCS 4471"));
        assertThat(r.status()).isEqualTo("DISPATCHED");
        assertThat(r.cost()).isEqualByComparingTo("650.00");
        assertThat(r.reference()).isEqualTo("TCS 4471");
        assertThat(r.settlementRef()).isEqualTo("RT-000005");
        verify(settlement).chargeReturn(eq(OWNER), eq(new BigDecimal("650.00")), eq("RT-000005"), anyString());
        verify(trade, never()).releaseHold(anyString());

        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, r.version(), "DISPATCH", null, "again")))
                .isInstanceOf(ValidationException.class).hasMessage("RT-000005 is already handed over.");
    }

    @Test
    @DisplayName("[MKT-R4.6] hand-over at MaxTheService's cost charges the owner nothing")
    void dispatchAtTheWarehousesCost() {
        ret(MarketplaceConsignedReturn.REQUESTED, "CONSIGNEE", WAREHOUSE);
        when(access.org()).thenReturn(WAREHOUSE);
        TransferDTOs.ReturnRow r = service.decide(new TransferDTOs.ReturnDecision(5L, 0, "DISPATCH", new BigDecimal("650"), "Rider Asif"));
        assertThat(r.settlementRef()).isNull();
        verify(settlement, never()).chargeReturn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[MKT-R20.5] [MKT-R2.1] received: the owner's hold let go lot by lot, oldest first, the rest set aside; RETURNED rows by the return")
    void receiveLetsTheHoldGo() {
        ret(MarketplaceConsignedReturn.DISPATCHED, "CONSIGNOR", OWNER);
        lots(3, 5);
        when(access.org()).thenReturn(WAREHOUSE);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "RECEIVE", null, null)))
                .isInstanceOf(ValidationException.class).hasMessage("Only the owner confirms it has its stock back.");
        when(access.org()).thenReturn(OWNER);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 7, "RECEIVE", null, null)))
                .isInstanceOf(ValidationException.class).hasMessage("This return changed meanwhile. Refresh and try again.");

        TransferDTOs.ReturnRow r = service.decide(new TransferDTOs.ReturnDecision(5L, 0, "RECEIVE", null, null));
        assertThat(r.status()).isEqualTo("RECEIVED");
        InOrder o = inOrder(trade, sale);
        o.verify(trade).releaseHold("MKT-CONSIGN-0");
        o.verify(sale).setAside(eq(OWNER), any(), eq(0));
        o.verify(trade).releaseHold("MKT-CONSIGN-1");
        o.verify(sale).setAside(eq(OWNER), any(), eq(4));
        assertThat(ledger).extracting(MarketplaceConsignedStock::getMovement).containsOnly("RETURNED");
        assertThat(ledger).extracting(MarketplaceConsignedStock::getQuantity).containsExactly(-3, -1);
        assertThat(ledger).extracting(MarketplaceConsignedStock::getTransferId).containsExactly(100L, 101L);
        assertThat(ledger).extracting(MarketplaceConsignedStock::getDocNo).containsOnly("RT-000005");
        verify(audit).event(eq("MKT_CONSIGN_RETURN_RECEIVED"), anyString(), eq("RT-000005"), eq(OWNER), any(), any(), eq("RECEIVED"),
                any(), anyString());
    }

    @Test
    @DisplayName("[MKT-R20.5] received, but the owner's books refuse: nothing is written, it stays being received, and pressing again finishes it")
    void receiveCarriesOn() {
        ret(MarketplaceConsignedReturn.DISPATCHED, "CONSIGNOR", OWNER);
        lots(4);
        when(access.org()).thenReturn(OWNER);
        doThrow(new IllegalStateException("hold service down")).when(trade).releaseHold(anyString());
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "RECEIVE", null, null)))
                .isInstanceOf(ValidationException.class).hasMessageStartingWith("Your stock could not be put back in your books");
        assertThat(stored.getStatus()).isEqualTo("RECEIVING");
        assertThat(ledger).isEmpty();
        // still under way, so still off the shelf; a stale press is refused, a fresh one finishes
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "RECEIVE", null, null)))
                .isInstanceOf(ValidationException.class).hasMessage("This return changed meanwhile. Refresh and try again.");
        org.mockito.Mockito.doNothing().when(trade).releaseHold(anyString());
        TransferDTOs.ReturnRow r = service.decide(new TransferDTOs.ReturnDecision(5L, stored.getVersion(), "RECEIVE", null, null));
        assertThat(r.status()).isEqualTo("RECEIVED");
        assertThat(ledger).extracting(MarketplaceConsignedStock::getQuantity).containsExactly(-4);
        assertThat(MarketplaceConsignedReturn.UNDER_WAY).contains("RECEIVING");
    }

    @Test
    @DisplayName("[MKT-R20.5] cancel: only whoever asked, only before hand-over; the units are on sale again")
    void cancel() {
        ret(MarketplaceConsignedReturn.REQUESTED, "CONSIGNOR", OWNER);
        when(access.org()).thenReturn(WAREHOUSE);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "CANCEL", null, null)))
                .isInstanceOf(ValidationException.class).hasMessage("Only the owner can cancel RT-000005: it asked for it.");
        when(access.org()).thenReturn(OWNER);
        TransferDTOs.ReturnRow r = service.decide(new TransferDTOs.ReturnDecision(5L, 0, "CANCEL", null, null));
        assertThat(r.status()).isEqualTo("CANCELLED");
        verify(sale).resync(OWNER, WAREHOUSE, PHONE);

        ret(MarketplaceConsignedReturn.DISPATCHED, "CONSIGNOR", OWNER);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "CANCEL", null, null)))
                .isInstanceOf(ValidationException.class).hasMessage("RT-000005 is already handed over.");
        when(access.org()).thenReturn(99L);
        assertThatThrownBy(() -> service.decide(new TransferDTOs.ReturnDecision(5L, 0, "CANCEL", null, null)))
                .isInstanceOf(ValidationException.class).hasMessage("No such return of your stock.");
    }

    @Test
    @DisplayName("[MKT-R20.5] [MKT-R4.6] the screen: the term, what can go, what is past it; the owner asks, the warehouse sends")
    void decorateShowsReturns() {
        shelf(5, 0, 2);
        when(returns.findTop10ByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdOrderByIdDesc(OWNER, WAREHOUSE, PHONE))
                .thenReturn(List.of(ret(MarketplaceConsignedReturn.REQUESTED, "CONSIGNOR", OWNER)));
        TransferDTOs.Held h = new TransferDTOs.Held("Mobile Distributor", PHONE, "Galaxy A32", 5, new BigDecimal("200000.00"),
                OWNER, 0, true, null);
        TransferDTOs.Back mine = service.decorate(OWNER, WAREHOUSE, List.of(h)).get(0).back();
        assertThat(mine.returnable()).isEqualTo(5);
        assertThat(mine.due()).isEqualTo(2);
        assertThat(mine.unsoldReturnDays()).isEqualTo(180);
        assertThat(mine.unsoldReturnBearer()).isEqualTo("CONSIGNEE");
        assertThat(mine.canAsk()).isTrue();
        assertThat(mine.canSend()).isFalse();
        assertThat(mine.returns()).extracting(TransferDTOs.ReturnRow::returnNo).containsExactly("RT-000005");
        TransferDTOs.Back theirs = service.decorate(WAREHOUSE, WAREHOUSE, List.of(h)).get(0).back();
        assertThat(theirs.canAsk()).isFalse();
        assertThat(theirs.canSend()).isTrue();
        assertThat(service.decorate(WAREHOUSE, WAREHOUSE, List.of(h)).get(0).withShrink(null).back())
                .as("a later decoration keeps it").isNotNull();
    }
}
