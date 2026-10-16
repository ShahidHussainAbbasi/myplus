package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.ProductRef;
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
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.entity.MarketplaceStockTransfer;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceStockTransferRepository;

/** MKT-3c — a seller sends stock to the MaxTheService warehouse: who may act, and the two books on Receive. */
@ExtendWith(MockitoExtension.class)
class StockTransferServiceTest {

    static final long SHOP = 6L, WAREHOUSE = 23L, OTHER = 8L, PHONE = 500L, SHOP_PRODUCT = 61L, WH_PRODUCT = 231L;

    @Mock MarketplaceStockTransferRepository transfers;
    @Mock MarketplaceProductSourceRepository sources;
    @Mock MarketplaceProductRepository products;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceSellerService sellers;
    @Mock SellerAccess access;
    @Mock TradeClient trade;
    @Mock CatalogClient catalog;
    @Mock MarketplaceAuditService audit;
    @Mock ConsignmentAgreementService consignments;
    @Mock MarketplaceConsignedStockRepository consigned;
    @Mock ConsignedSaleService consignedSale;
    StockTransferService service;

    @BeforeEach
    void wire() {
        service = new StockTransferService(transfers, sources, products, accounts, settings, sellers, access, trade, catalog, audit,
                consignments, consigned, consignedSale);
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(WAREHOUSE));
        // MKT-5c decorates the held rows; here, the 5b columns as they are
        lenient().when(consignedSale.decorate(anyLong(), anyLong(), any(), any())).thenAnswer(i -> {
            java.util.function.Function<Long, String> names = i.getArgument(3);
            List<Object[]> rows = i.getArgument(2);
            return rows.stream().map(r -> new TransferDTOs.Held(names.apply((Long) r[0]), (Long) r[1], (String) r[2],
                    ((Number) r[3]).longValue(), new java.math.BigDecimal(r[4].toString()).setScale(2))).toList();
        });
        lenient().when(transfers.save(any())).thenAnswer(i -> {
            MarketplaceStockTransfer t = i.getArgument(0);
            if (t.getId() == null) t.setId(5L);
            return t;
        });
        lenient().when(transfers.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(products.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(eq(SHOP), eq(PHONE), eq(Match.MATCHED.name())))
                .thenReturn(Optional.of(source(SHOP, SHOP_PRODUCT)));
        lenient().when(sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(eq(WAREHOUSE), eq(PHONE), eq(Match.MATCHED.name())))
                .thenReturn(Optional.of(source(WAREHOUSE, WH_PRODUCT)));
        MarketplaceSellerAccount shop = new MarketplaceSellerAccount();
        shop.setDisplayName("Shahzad Mobile Shop");
        lenient().when(accounts.findByOrganizationId(SHOP)).thenReturn(Optional.of(shop));
        serialTracked(false);
    }

    static MarketplaceProductSource source(long org, long productId) {
        MarketplaceProductSource s = new MarketplaceProductSource();
        s.setOrganizationId(org);
        s.setSourceProductId(productId);
        s.setSourceProductName("Galaxy A32");
        return s;
    }

    void as(long org) {
        lenient().when(access.org()).thenReturn(org);
        lenient().when(access.userId()).thenReturn(org * 10);
    }

    void serialTracked(boolean yes) {
        lenient().when(catalog.getProductsFresh(any(), anyBoolean()))
                .thenReturn(List.of(ProductRef.builder().id(SHOP_PRODUCT).requiresSerial(yes).build()));
    }

    MarketplaceStockTransfer offered(int attempt) {
        MarketplaceStockTransfer t = new MarketplaceStockTransfer();
        t.setId(5L);
        t.setSellerOrgId(SHOP);
        t.setWarehouseOrgId(WAREHOUSE);
        t.setMktProductId(PHONE);
        t.setSellerProductId(SHOP_PRODUCT);
        t.setWarehouseProductId(WH_PRODUCT);
        t.setProductName("Galaxy A32");
        t.setQuantity(2);
        t.setUnitPrice(new BigDecimal("40000.00"));
        t.setStatus(MarketplaceStockTransfer.OFFERED);
        t.setAttempt(attempt);
        t.setVersion(3);
        when(transfers.findById(5L)).thenReturn(Optional.of(t));
        return t;
    }

    static TransferDTOs.DecisionRequest decide(List<String> serials) {
        return new TransferDTOs.DecisionRequest("ST-000005", 3, null, serials);
    }

    @Test
    @DisplayName("[MKT-R20.4] an offer names the product in both books, refused in a sentence when it cannot be received")
    void offer() {
        as(SHOP);
        TransferDTOs.Transfer t = service.offer(new TransferDTOs.OfferRequest(PHONE, 2, new BigDecimal("40000"), " boxed "));
        assertThat(t.transferNo()).isEqualTo("ST-000005");
        assertThat(t.status()).isEqualTo(MarketplaceStockTransfer.OFFERED);
        assertThat(t.total()).isEqualByComparingTo("80000");
        ArgumentCaptor<MarketplaceStockTransfer> saved = ArgumentCaptor.forClass(MarketplaceStockTransfer.class);
        verify(transfers).save(saved.capture());
        assertThat(saved.getValue().getSellerProductId()).isEqualTo(SHOP_PRODUCT);
        assertThat(saved.getValue().getWarehouseProductId()).isEqualTo(WH_PRODUCT);
        assertThat(saved.getValue().getNote()).isEqualTo("boxed");

        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 0, BigDecimal.TEN, null)))
                .hasMessage("Enter how many you are sending (1 to 10000).");
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 1, new BigDecimal("1.005"), null)))
                .hasMessage("A price has at most two decimal places.");
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE + 1, 1, BigDecimal.TEN, null)))
                .hasMessage("Choose one of your own products that MaxTheService has matched.");
        when(sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(eq(SHOP), eq(PHONE + 2), anyString()))
                .thenReturn(Optional.of(source(SHOP, 62L)));
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE + 2, 1, BigDecimal.TEN, null)))
                .hasMessage("MaxTheService does not carry this product yet, so it cannot receive it. Ask MaxTheService to add it first.");

        as(WAREHOUSE);
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 1, BigDecimal.TEN, null)))
                .hasMessage("The warehouse cannot send stock to itself.");
        when(settings.warehouseOrg()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 1, BigDecimal.TEN, null)))
                .hasMessage("MaxTheService has no warehouse to send stock to.");
    }

    @Test
    @DisplayName("[MKT-R4.2] only the two parties: another org, and the wrong side of the transfer, read 'not found'")
    void parties() {
        offered(0);
        as(OTHER);
        assertThatThrownBy(() -> service.cancel(decide(null))).hasMessage("Transfer not found.");
        assertThatThrownBy(() -> service.reject(decide(null))).hasMessage("Transfer not found.");
        assertThatThrownBy(() -> service.receive(decide(null))).hasMessage("Transfer not found.");
        as(SHOP);   // the sender cannot receive its own transfer
        assertThatThrownBy(() -> service.receive(decide(null))).hasMessage("Transfer not found.");
        as(WAREHOUSE);   // nor can the warehouse cancel it
        assertThatThrownBy(() -> service.cancel(decide(null))).hasMessage("Transfer not found.");
        verify(trade, never()).recordSale(any());
    }

    @Test
    @DisplayName("[MKT-R20.4] a stale screen and a decided transfer are refused; a Not taken needs a reason")
    void decided() {
        MarketplaceStockTransfer t = offered(0);
        as(WAREHOUSE);
        assertThatThrownBy(() -> service.reject(decide(null))).hasMessage("Tell the seller why it is not taken.");
        assertThatThrownBy(() -> service.receive(new TransferDTOs.DecisionRequest("ST-000005", 2, null, null)))
                .hasMessage("This transfer changed meanwhile. Refresh and try again.");
        t.setStatus(MarketplaceStockTransfer.CANCELLED);
        assertThatThrownBy(() -> service.receive(decide(null))).hasMessage("This transfer is already cancelled.");
        verify(trade, never()).recordSale(any());
    }

    @Test
    @DisplayName("[MKT-R20.4] a serial-tracked phone needs one IMEI per unit before either book is written")
    void serials() {
        offered(0);
        serialTracked(true);
        as(WAREHOUSE);
        assertThatThrownBy(() -> service.receive(decide(List.of("351"))))
                .hasMessage("Scan the serial number (IMEI) of each unit received: 2 for Galaxy A32.");
        assertThatThrownBy(() -> service.receive(decide(List.of("351", " 351 "))))
                .hasMessage("The same serial number is entered twice.");
        verify(trade, never()).recordSale(any());
        verify(trade, never()).recordPurchase(any());
    }

    @Test
    @DisplayName("[MKT-R20.4] [MKT-R4.2] received: the seller's sale, then the warehouse's purchase on that invoice, keyed by attempt")
    void receive() {
        MarketplaceStockTransfer t = offered(1);
        serialTracked(true);
        as(WAREHOUSE);
        when(trade.recordSale(any())).thenReturn(SaleRecordResult.builder().invoiceNo("INV-000340").build());
        when(trade.recordPurchase(any())).thenReturn(PurchaseRecordResult.builder().purchaseId(9L).build());

        TransferDTOs.Transfer done = service.receive(decide(List.of("351", "352")));

        InOrder order = inOrder(trade);
        ArgumentCaptor<SaleRecordRequest> sale = ArgumentCaptor.forClass(SaleRecordRequest.class);
        ArgumentCaptor<PurchaseRecordRequest> purchase = ArgumentCaptor.forClass(PurchaseRecordRequest.class);
        order.verify(trade).recordSale(sale.capture());
        order.verify(trade).recordPurchase(purchase.capture());
        assertThat(sale.getValue().getIdempotencyKey()).isEqualTo("MKT-ST-000005-1-sale");
        assertThat(sale.getValue().getOrganizationId()).isEqualTo(SHOP);
        assertThat(sale.getValue().getCustomer().getContact()).isEqualTo(StockTransferService.BUYER_CONTACT);
        assertThat(sale.getValue().getTenders()).isNullOrEmpty();   // no tender: MaxTheService owes the seller
        assertThat(sale.getValue().getLines()).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(SHOP_PRODUCT);
            assertThat(l.getSerials()).isEqualTo("351,352");
        });
        assertThat(purchase.getValue().getIdempotencyKey()).isEqualTo("MKT-ST-000005-1-purchase");
        assertThat(purchase.getValue().getOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(purchase.getValue().getProductId()).isEqualTo(WH_PRODUCT);
        assertThat(purchase.getValue().getVendorName()).isEqualTo("Shahzad Mobile Shop");
        assertThat(purchase.getValue().getUnitCost()).isEqualByComparingTo("40000");
        assertThat(purchase.getValue().getBillNo()).isEqualTo("INV-000340");
        assertThat(done.status()).isEqualTo(MarketplaceStockTransfer.RECEIVED);
        assertThat(t.getSaleInvoiceNo()).isEqualTo("INV-000340");
        assertThat(t.getPurchaseId()).isEqualTo(9L);
        verify(trade, never()).reverseSale(anyString(), anyString());
    }

    @Test
    @DisplayName("[MKT-R20.4] a purchase the warehouse refuses voids the seller's sale; the next try uses new keys")
    void refusedPurchase() {
        MarketplaceStockTransfer t = offered(0);
        as(WAREHOUSE);
        when(trade.recordSale(any())).thenReturn(SaleRecordResult.builder().invoiceNo("INV-000341").build());
        when(trade.recordPurchase(any())).thenThrow(new ValidationException("refused"));

        assertThatThrownBy(() -> service.receive(decide(null))).hasMessageStartingWith(
                "The warehouse's purchase could not be recorded, so nothing was received and the seller's sale was cancelled.");
        verify(trade).reverseSale(eq("INV-000341"), anyString());
        assertThat(t.getStatus()).isEqualTo(MarketplaceStockTransfer.OFFERED);
        assertThat(t.getAttempt()).isEqualTo(1);
        assertThat(t.getSaleInvoiceNo()).isNull();
    }

    @Test
    @DisplayName("[MKT-R20.4] a sale the seller's books refuse writes nothing else, and a retry replays the same keys")
    void refusedSale() {
        MarketplaceStockTransfer t = offered(0);
        as(WAREHOUSE);
        when(trade.recordSale(any())).thenThrow(new ValidationException("refused"));

        assertThatThrownBy(() -> service.receive(decide(null)))
                .hasMessageStartingWith("The seller's sale could not be recorded, so nothing was received.");
        verify(trade, never()).recordPurchase(any());
        verify(trade, never()).reverseSale(anyString(), anyString());
        assertThat(t.getStatus()).isEqualTo(MarketplaceStockTransfer.OFFERED);
        assertThat(t.getAttempt()).isZero();
    }

    // ── MKT-5b: consigned, not bought ─────────────────────────────────────────────────────────────────

    static MarketplaceConsignmentAgreement agreement(long id) {
        MarketplaceConsignmentAgreement a = new MarketplaceConsignmentAgreement();
        a.setId(id);
        a.setConsignorOrgId(SHOP);
        a.setConsigneeOrgId(WAREHOUSE);
        a.setStatus(MarketplaceConsignmentAgreement.ACTIVE);
        return a;
    }

    MarketplaceStockTransfer consignOffered(int attempt) {
        MarketplaceStockTransfer t = offered(attempt);
        t.setKind(MarketplaceStockTransfer.CONSIGN);
        t.setAgreementId(4L);
        return t;
    }

    @Test
    @DisplayName("[MKT-R2.1] [MKT-R4.5] consigning needs the seller's ACTIVE agreement with the warehouse; the transfer names it")
    void consignOffer() {
        as(SHOP);
        when(consignments.activeFor(SHOP, WAREHOUSE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 2, new BigDecimal("40000"), null, "consign")))
                .hasMessage("You have no active consignment agreement with MaxTheService. Accept its terms under Consignment "
                        + "agreements first, or sell the stock to MaxTheService instead.");
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 2, new BigDecimal("40000"), null, "LEND")))
                .hasMessage("Choose whether MaxTheService buys this stock or holds it on consignment.");
        verify(transfers, never()).save(any());

        when(consignments.activeFor(SHOP, WAREHOUSE)).thenReturn(Optional.of(agreement(4L)));
        assertThatThrownBy(() -> service.offer(new TransferDTOs.OfferRequest(PHONE, 2, null, null, "CONSIGN")))
                .hasMessage("Enter the value of one unit.");
        TransferDTOs.Transfer t = service.offer(new TransferDTOs.OfferRequest(PHONE, 2, new BigDecimal("40000"), null, "consign"));
        assertThat(t.kind()).isEqualTo(MarketplaceStockTransfer.CONSIGN);
        assertThat(t.agreementNo()).isEqualTo("CA-000004");
        verify(audit).event(eq("MKT_CONSIGN_OFFERED"), anyString(), eq("ST-000005"), eq(SHOP), any(), any(), any(), any(), any());
        // the 3c default is unchanged: no kind is a sale to MaxTheService
        assertThat(service.offer(new TransferDTOs.OfferRequest(PHONE, 1, BigDecimal.TEN, null)).kind())
                .isEqualTo(MarketplaceStockTransfer.BUY);
    }

    @Test
    @DisplayName("[MKT-R2.1] [MKT-R4.5] [MKT-R20.5] received on consignment: no sale, no purchase; the seller's stock is set aside for good and counted into the ledger once")
    void consignReceive() {
        MarketplaceStockTransfer t = consignOffered(1);
        serialTracked(true);
        as(WAREHOUSE);
        when(consignments.activeFor(SHOP, WAREHOUSE)).thenReturn(Optional.of(agreement(4L)));
        when(trade.holdStock(any())).thenReturn(StockHoldResponse.builder().held(true).build());

        TransferDTOs.Transfer done = service.receive(decide(List.of("351", "352")));

        ArgumentCaptor<StockHoldRequest> hold = ArgumentCaptor.forClass(StockHoldRequest.class);
        verify(trade).holdStock(hold.capture());
        assertThat(hold.getValue().getOrganizationId()).isEqualTo(SHOP);
        assertThat(hold.getValue().getHoldKey()).isEqualTo("MKT-ST-000005-1-consign");
        assertThat(hold.getValue().getHoldKind()).isEqualTo(StockReservationRequest.HoldKind.CONSIGNMENT);
        assertThat(hold.getValue().getLines()).singleElement().satisfies(l -> {
            assertThat(l.getItemId()).isEqualTo(SHOP_PRODUCT);
            assertThat(l.getQuantity()).isEqualByComparingTo("2");
        });
        verify(trade, never()).recordSale(any());
        verify(trade, never()).recordPurchase(any());
        ArgumentCaptor<MarketplaceConsignedStock> row = ArgumentCaptor.forClass(MarketplaceConsignedStock.class);
        verify(consigned).save(row.capture());
        assertThat(row.getValue().getMovement()).isEqualTo(MarketplaceConsignedStock.RECEIVED);
        assertThat(row.getValue().getQuantity()).isEqualTo(2);
        assertThat(row.getValue().getConsignorOrgId()).isEqualTo(SHOP);
        assertThat(row.getValue().getConsigneeOrgId()).isEqualTo(WAREHOUSE);
        assertThat(row.getValue().getUnitValue()).isEqualByComparingTo("40000");
        assertThat(row.getValue().getSerials()).isEqualTo("351,352");
        assertThat(row.getValue().getAgreementId()).isEqualTo(4L);
        assertThat(done.status()).isEqualTo(MarketplaceStockTransfer.RECEIVED);
        assertThat(t.getHoldKey()).isEqualTo("MKT-ST-000005-1-consign");
        assertThat(t.getSaleInvoiceNo()).isNull();

        // a Receive retried from RECEIVING replays the hold (same key) and does not count the stock twice
        t.setStatus(MarketplaceStockTransfer.RECEIVING);
        when(consigned.existsByTransferIdAndMovement(5L, MarketplaceConsignedStock.RECEIVED)).thenReturn(true);
        service.receive(decide(List.of("351", "352")));
        verify(consigned).save(any());
    }

    @Test
    @DisplayName("[MKT-R2.1] stock the seller's books cannot set aside, or an agreement that ended meanwhile, receives nothing")
    void consignRefused() {
        MarketplaceStockTransfer t = consignOffered(0);
        as(WAREHOUSE);
        when(consignments.activeFor(SHOP, WAREHOUSE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.receive(decide(null))).hasMessage("There is no active consignment agreement with "
                + "this seller any more, so this stock cannot be taken on consignment. Choose Not taken and say why.");
        verify(trade, never()).holdStock(any());

        when(consignments.activeFor(SHOP, WAREHOUSE)).thenReturn(Optional.of(agreement(4L)));
        when(trade.holdStock(any())).thenReturn(StockHoldResponse.builder().held(false).reason("Only 1 in stock").build());
        assertThatThrownBy(() -> service.receive(decide(null))).hasMessage(
                "The seller's books do not show 2 × Galaxy A32 free to set aside, so nothing was received: Only 1 in stock");
        assertThat(t.getStatus()).isEqualTo(MarketplaceStockTransfer.OFFERED);
        verify(consigned, never()).save(any());
        verify(trade, never()).recordSale(any());
    }
}
