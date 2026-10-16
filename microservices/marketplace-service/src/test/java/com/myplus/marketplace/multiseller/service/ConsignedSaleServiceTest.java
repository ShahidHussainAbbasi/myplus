package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.PurchaseRecordRequest;
import com.myplus.commerce.contracts.dto.PurchaseRecordResult;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Match;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

/** MKT-5c — the warehouse lists and sells consigned stock; the owner's books move at the sale. */
@ExtendWith(MockitoExtension.class)
class ConsignedSaleServiceTest {

    static final long OWNER = 7L, WAREHOUSE = 23L, PHONE = 900L, OWNER_PRODUCT = 41L, WH_PRODUCT = 77L;

    @Mock MarketplaceOfferRepository offers;
    @Mock MarketplaceOfferProjectionRepository projections;
    @Mock MarketplaceProductRepository products;
    @Mock MarketplaceProductSourceRepository sources;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceConsignedStockRepository consigned;
    @Mock MarketplaceConsignmentAgreementRepository agreements;
    @Mock MarketplaceOrderLineRepository lines;
    @Mock ConsignmentAgreementService consignments;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceSellerService sellers;
    @Mock MarketplacePolicyService policies;
    @Mock OfferProjectionService projection;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;
    @Mock TradeClient trade;
    @Mock PlatformTransactionManager txManager;
    ConsignedSaleService service;
    MarketplaceConsignmentAgreement agreement;

    @BeforeEach
    void wire() {
        service = new ConsignedSaleService(offers, projections, products, sources, accounts, consigned, agreements, lines,
                consignments, settings, sellers, policies, projection, access, audit, trade, txManager);
        agreement = new MarketplaceConsignmentAgreement();
        agreement.setId(3L);
        agreement.setConsignorOrgId(OWNER);
        agreement.setConsigneeOrgId(WAREHOUSE);
        agreement.setOwnershipTransfer("ON_SALE");
        agreement.setPriceSetBy("CONSIGNOR");
        agreement.setCommissionPct(new BigDecimal("10.00"));
        lenient().when(consignments.activeFor(OWNER, WAREHOUSE)).thenReturn(Optional.of(agreement));
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(WAREHOUSE));
        MarketplaceSellerAccount acc = new MarketplaceSellerAccount();
        acc.setDisplayName("Mobile Distributor");
        lenient().when(accounts.findByOrganizationId(OWNER)).thenReturn(Optional.of(acc));
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    // ── the listing ──

    void listingStage() {
        MarketplaceProduct p = new MarketplaceProduct();
        p.setId(PHONE);
        p.setCanonicalName("Galaxy A32");
        lenient().when(products.findById(PHONE)).thenReturn(Optional.of(p));
        lenient().when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(5L);
        MarketplaceProductSource shelf = new MarketplaceProductSource();
        shelf.setOrganizationId(WAREHOUSE);
        shelf.setSourceProductId(WH_PRODUCT);
        lenient().when(sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(WAREHOUSE, PHONE, Match.MATCHED.name()))
                .thenReturn(Optional.of(shelf));
        lenient().when(offers.save(any())).thenAnswer(i -> {
            MarketplaceOffer o = i.getArgument(0);
            if (o.getId() == null) o.setId(55L);
            return o;
        });
        MarketplacePolicy w = new MarketplacePolicy();
        w.setId(1L);
        MarketplacePolicy r = new MarketplacePolicy();
        r.setId(2L);
        lenient().when(policies.usable(1L, MarketplacePolicy.WARRANTY)).thenReturn(w);
        lenient().when(policies.usable(2L, MarketplacePolicy.RETURN)).thenReturn(r);
    }

    static TransferDTOs.ListRequest listReq(Long consignor, String price) {
        return new TransferDTOs.ListRequest(consignor, PHONE, price == null ? null : new BigDecimal(price), "Karachi", 48, 1L, 2L, null);
    }

    @Test
    @DisplayName("[MKT-R2.1][MKT-R4.5] the owner prices it, the warehouse sells it: a CONSIGNMENT offer of the warehouse, owned by the consignor, live at once")
    void ownerLists() {
        listingStage();
        when(access.org()).thenReturn(OWNER);
        TransferDTOs.Listing l = service.list(listReq(null, "52000"));
        ArgumentCaptor<MarketplaceOffer> saved = ArgumentCaptor.forClass(MarketplaceOffer.class);
        verify(offers).save(saved.capture());
        MarketplaceOffer o = saved.getValue();
        assertThat(o.getOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(o.getSellerOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(o.getCustodianOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(o.getFulfillerOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(o.getStockOwnerOrganizationId()).isEqualTo(OWNER);
        assertThat(o.getStockSourceType()).isEqualTo("CONSIGNMENT");
        assertThat(o.getSourceProductId()).as("the warehouse's own product: its sale takes it off its shelf").isEqualTo(WH_PRODUCT);
        assertThat(o.getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(o.getCommissionPolicyId()).as("the agreement's commission, at checkout").isNull();
        assertThat(o.getMarketplacePrice()).isEqualByComparingTo("52000");
        assertThat(l.agreementNo()).isEqualTo(agreement.getAgreementNo());
        verify(projection).publish(o);
    }

    @Test
    @DisplayName("[MKT-R2.1] only the party the agreement names sets the price; on-delivery terms and nothing held are refused")
    void whoMayList() {
        listingStage();
        when(access.org()).thenReturn(WAREHOUSE);
        assertThatThrownBy(() -> service.list(listReq(OWNER, "52000")))
                .hasMessage("Under " + agreement.getAgreementNo() + ", Mobile Distributor sets the selling price.");
        agreement.setPriceSetBy("CONSIGNEE");
        when(access.org()).thenReturn(OWNER);
        assertThatThrownBy(() -> service.list(listReq(null, "52000")))
                .hasMessage("Under " + agreement.getAgreementNo() + ", MaxTheService sets the selling price.");
        when(access.org()).thenReturn(WAREHOUSE);
        assertThat(service.list(listReq(OWNER, "52000")).price()).isEqualByComparingTo("52000");

        agreement.setOwnershipTransfer("ON_DELIVERY");
        assertThatThrownBy(() -> service.list(listReq(OWNER, "52000"))).hasMessageContaining("ownership passes on delivery");
        agreement.setOwnershipTransfer("ON_SALE");
        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(0L);
        assertThatThrownBy(() -> service.list(listReq(OWNER, "52000")))
                .hasMessage("MaxTheService holds none of Galaxy A32 for Mobile Distributor.");
        when(consignments.activeFor(OWNER, WAREHOUSE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(listReq(OWNER, "52000"))).hasMessageContaining("no active consignment agreement");
        verify(offers, times(1)).save(any());
    }

    // ── checkout ──

    static MarketplaceOrderLine line(long id, int qty, String price) {
        MarketplaceOrderLine l = new MarketplaceOrderLine();
        l.setId(id);
        l.setSellerOrderId(500L);
        l.setOfferId(55L);
        l.setMktProductId(PHONE);
        l.setSourceProductId(WH_PRODUCT);
        l.setProductName("Galaxy A32");
        l.setQuantity(qty);
        l.setUnitPrice(new BigDecimal(price));
        l.setStockSourceType("CONSIGNMENT");
        l.setSellerOrganizationId(WAREHOUSE);
        l.setStockOwnerOrganizationId(OWNER);
        l.setCustodianOrganizationId(WAREHOUSE);
        l.setFulfillerOrganizationId(WAREHOUSE);
        return l;
    }

    static MarketplaceSellerOrder part(long id) {
        MarketplaceSellerOrder so = new MarketplaceSellerOrder();
        so.setId(id);
        so.setSellerOrganizationId(WAREHOUSE);
        return so;
    }

    @Test
    @DisplayName("[MKT-R15.4] the line is sold on the agreement's commission: ITEMS at its percentage, no offer policy")
    void terms() {
        MarketplaceOrderLine l = line(1, 1, "52000");
        service.terms(l);
        assertThat(l.getCommissionBasis()).isEqualTo("ITEMS");
        assertThat(l.getCommissionRate()).isEqualByComparingTo("0.100000");
        assertThat(l.getCommissionPolicyId()).isNull();
        assertThat(ConsignedSaleService.owed(l)).as("52000 less 10%").isEqualByComparingTo("46800.00");
    }

    @Test
    @DisplayName("[MKT-R20.5] checkout promises no more than the ledger holds, counted under the agreement's lock")
    void promised() {
        when(agreements.lockById(3L)).thenReturn(Optional.of(agreement));
        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(3L);
        MarketplaceSellerOrder so = part(500L);
        when(lines.consignedPromised(OWNER, WAREHOUSE, PHONE)).thenReturn(3L);
        assertThat(service.unpromised(List.of(so), id -> List.of(line(1, 3, "52000")))).isEmpty();
        when(lines.consignedPromised(OWNER, WAREHOUSE, PHONE)).thenReturn(4L);
        assertThat(service.unpromised(List.of(so), id -> List.of(line(1, 3, "52000")))).containsExactly(500L);
        verify(agreements, times(2)).lockById(3L);

        MarketplaceOrderLine own = line(2, 1, "100");
        own.setStockSourceType("MERCHANT");
        assertThat(service.unpromised(List.of(part(501L)), id -> List.of(own))).as("nothing consigned, nothing counted").isEmpty();
        when(consignments.activeFor(OWNER, WAREHOUSE)).thenReturn(Optional.empty());
        assertThat(service.unpromised(List.of(so), id -> List.of(line(1, 1, "52000")))).as("no agreement in force").containsExactly(500L);
    }

    // ── the sale ──

    void twoLots() {
        List<Object[]> lots = new ArrayList<>();
        lots.add(new Object[] {11L, "MKT-ST-000011-0-consign", OWNER_PRODUCT, 2L, new BigDecimal("40000.00")});
        lots.add(new Object[] {12L, "MKT-ST-000012-0-consign", OWNER_PRODUCT, 3L, new BigDecimal("41000.00")});
        lenient().when(consigned.lots(OWNER, WAREHOUSE, PHONE)).thenReturn(lots);
        lenient().when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(5L);
        lenient().when(trade.recordSale(any())).thenAnswer(i -> SaleRecordResult.builder()
                .invoiceNo("INV-" + ((SaleRecordRequest) i.getArgument(0)).getIdempotencyKey()).build());
        lenient().when(trade.recordPurchase(any())).thenReturn(PurchaseRecordResult.builder().purchaseId(900L).build());
        lenient().when(trade.holdStock(any())).thenReturn(StockHoldResponse.builder().held(true).build());
    }

    @Test
    @DisplayName("[MKT-R2.1][MKT-R15.4] at Accept the units leave the owner's books lot by lot: let go, sold to MaxTheService less commission, the rest set aside again, bought, counted SOLD")
    void sell() {
        twoLots();
        MarketplaceOrderLine l = line(81, 3, "52000");
        l.setCommissionRate(new BigDecimal("0.100000"));
        service.sell(part(500L), "MKT-000123", List.of(l), Map.of(81L, List.of("IMEI-1", "IMEI-2", "IMEI-3")));

        InOrder order = inOrder(trade);
        order.verify(trade).releaseHold("MKT-ST-000011-0-consign");
        order.verify(trade).recordSale(any());
        order.verify(trade).recordPurchase(any());
        order.verify(trade).releaseHold("MKT-ST-000012-0-consign");
        order.verify(trade).recordSale(any());
        order.verify(trade).holdStock(any());
        order.verify(trade).recordPurchase(any());

        ArgumentCaptor<SaleRecordRequest> sales = ArgumentCaptor.forClass(SaleRecordRequest.class);
        verify(trade, times(2)).recordSale(sales.capture());
        SaleRecordRequest first = sales.getAllValues().get(0);
        assertThat(first.getOrganizationId()).isEqualTo(OWNER);
        assertThat(first.getIdempotencyKey()).isEqualTo("MKT-SO-500-L81-T11-0-sale");
        assertThat(first.getCustomer().getName()).isEqualTo("MaxTheService");
        assertThat(first.getLines().get(0).getProductId()).isEqualTo(OWNER_PRODUCT);
        assertThat(first.getLines().get(0).getQuantity()).isEqualByComparingTo("2");
        assertThat(first.getLines().get(0).getUnitPrice()).isEqualByComparingTo("46800.00");
        assertThat(first.getLines().get(0).getSerials()).isEqualTo("IMEI-1,IMEI-2");
        assertThat(sales.getAllValues().get(1).getLines().get(0).getSerials()).isEqualTo("IMEI-3");

        ArgumentCaptor<StockHoldRequest> hold = ArgumentCaptor.forClass(StockHoldRequest.class);
        verify(trade).holdStock(hold.capture());
        assertThat(hold.getValue().getHoldKey()).as("the rest of the second lot, under its own key").isEqualTo("MKT-ST-000012-0-consign");
        assertThat(hold.getValue().getHoldKind()).isEqualTo(StockReservationRequest.HoldKind.CONSIGNMENT);
        assertThat(hold.getValue().getLines().get(0).getQuantity()).isEqualByComparingTo("2");

        ArgumentCaptor<PurchaseRecordRequest> buys = ArgumentCaptor.forClass(PurchaseRecordRequest.class);
        verify(trade, times(2)).recordPurchase(buys.capture());
        assertThat(buys.getValue().getOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(buys.getValue().getProductId()).isEqualTo(WH_PRODUCT);
        assertThat(buys.getValue().getVendorName()).isEqualTo("Mobile Distributor");
        assertThat(buys.getValue().getBillNo()).isEqualTo("INV-MKT-SO-500-L81-T12-0-sale");

        ArgumentCaptor<MarketplaceConsignedStock> rows = ArgumentCaptor.forClass(MarketplaceConsignedStock.class);
        verify(consigned, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(MarketplaceConsignedStock::getMovement, MarketplaceConsignedStock::getQuantity,
                        MarketplaceConsignedStock::getTransferId, MarketplaceConsignedStock::getOrderLineId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SOLD", -2, 11L, 81L), org.assertj.core.groups.Tuple.tuple("SOLD", -1, 12L, 81L));
        assertThat(rows.getAllValues().get(0).getUnitPrice()).isEqualByComparingTo("46800.00");
    }

    @Test
    @DisplayName("[MKT-R20.5] an Accept retried after its units moved moves nothing again; more than is held is refused before anything moves")
    void replayAndShort() {
        twoLots();
        MarketplaceOrderLine l = line(81, 3, "52000");
        MarketplaceConsignedStock done = new MarketplaceConsignedStock();
        done.setQuantity(-3);
        when(consigned.findByOrderLineIdAndMovement(81L, "SOLD")).thenReturn(List.of(done));
        service.sell(part(500L), "MKT-000123", List.of(l), Map.of());
        verify(trade, never()).recordSale(any());

        MarketplaceOrderLine big = line(82, 6, "52000");
        assertThatThrownBy(() -> service.sell(part(500L), "MKT-000124", List.of(big), Map.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("MaxTheService holds only 5 × Galaxy A32 for Mobile Distributor, so this order cannot be accepted. Reject it and say why.");
        verify(trade, never()).releaseHold(anyString());

        // MKT-5d: units a count found missing are still in the lots but not on the shelf
        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(3L);
        MarketplaceOrderLine four = line(83, 4, "52000");
        assertThatThrownBy(() -> service.sell(part(500L), "MKT-000125", List.of(four), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageStartingWith("MaxTheService holds only 3 × Galaxy A32");
        verify(trade, never()).releaseHold(anyString());
    }

    @Test
    @DisplayName("[MKT-R20.5] a refused purchase voids the owner's sale, sets the whole lot aside again, and the next try is a new attempt")
    void purchaseRefused() {
        twoLots();
        when(trade.recordPurchase(any())).thenThrow(new RuntimeException("closed period"));
        MarketplaceOrderLine l = line(81, 1, "52000");
        assertThatThrownBy(() -> service.sell(part(500L), "MKT-000123", List.of(l), Map.of()))
                .hasMessageStartingWith("MaxTheService's purchase could not be recorded, so the order was not accepted and Mobile Distributor's sale was cancelled.");
        verify(trade).reverseSale(eq("INV-MKT-SO-500-L81-T11-0-sale"), anyString());
        ArgumentCaptor<StockHoldRequest> holds = ArgumentCaptor.forClass(StockHoldRequest.class);
        verify(trade, times(2)).holdStock(holds.capture());
        assertThat(holds.getAllValues().get(1).getLines().get(0).getQuantity()).as("the whole lot again").isEqualByComparingTo("2");
        ArgumentCaptor<MarketplaceConsignedStock> row = ArgumentCaptor.forClass(MarketplaceConsignedStock.class);
        verify(consigned).save(row.capture());
        assertThat(row.getValue().getMovement()).isEqualTo("VOIDED");
        assertThat(row.getValue().getQuantity()).isZero();

        when(consigned.countByTransferIdAndOrderLineIdAndMovement(11L, 81L, "VOIDED")).thenReturn(1L);
        assertThatThrownBy(() -> service.sell(part(500L), "MKT-000123", List.of(l), Map.of()));
        verify(trade).recordSale(org.mockito.ArgumentMatchers.argThat(r -> r != null && "MKT-SO-500-L81-T11-1-sale".equals(r.getIdempotencyKey())));
    }

    @Test
    @DisplayName("[MKT-R20.5] lines that are not consigned are not touched")
    void ownStockUntouched() {
        MarketplaceOrderLine l = line(83, 1, "100");
        l.setStockSourceType("PLATFORM");
        service.sell(part(500L), "MKT-000125", List.of(l), Map.of());
        verify(consigned, never()).lots(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[MKT-R20.5] the warehouse's own hold skips consigned lines (set aside in the owner's books already); what a listing can promise is held less promised")
    void holdAndAvailability() {
        MarketplaceOrderLine own = line(1, 2, "100");
        own.setStockSourceType("PLATFORM");
        own.setSourceProductId(78L);
        StockHoldRequest r = MarketplaceCheckoutService.holdRequest(part(500L), List.of(line(2, 3, "52000"), own));
        assertThat(r.getLines()).hasSize(1);
        assertThat(r.getLines().get(0).getItemId()).isEqualTo(78L);
        assertThat(MarketplaceCheckoutService.holdRequest(part(501L), List.of(line(3, 1, "52000"))).getLines()).isEmpty();

        OfferProjectionService p = new OfferProjectionService(projections, offers, products, null, null, null, null, txManager,
                consigned, lines);
        MarketplaceOffer o = new MarketplaceOffer();
        o.setStockOwnerOrganizationId(OWNER);
        o.setCustodianOrganizationId(WAREHOUSE);
        o.setMktProductId(PHONE);
        when(consigned.balance(OWNER, WAREHOUSE, PHONE)).thenReturn(5L);
        when(lines.consignedPromised(OWNER, WAREHOUSE, PHONE)).thenReturn(2L, 7L);
        assertThat(p.consignedAvailable(o)).isEqualTo(3L);
        assertThat(p.consignedAvailable(o)).as("never below zero").isZero();
    }
}
