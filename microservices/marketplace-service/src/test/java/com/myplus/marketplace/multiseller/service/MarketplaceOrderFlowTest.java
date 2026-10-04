package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePlatformSettingRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePolicyRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.service.OrderService;
import com.myplus.marketplace.service.ShippingPolicy;

/**
 * MKT-1e — checkout → hold → accept / reject / expire, end to end through the REAL services, with the repositories
 * backed by in-memory maps (so the two checkout transactions, the snapshot and the state transitions all run) and
 * only the remote edges mocked: the trade hold/sale and catalog.
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceOrderFlowTest {

    static final long SELLER = 7L, OTHER_SELLER = 8L, OFFER = 11L, PRODUCT = 100L, SOURCE = 555L;

    @Mock MarketplaceOfferRepository offers;
    @Mock MarketplaceOfferProjectionRepository projections;
    @Mock MarketplaceProductRepository products;
    @Mock MarketplacePolicyRepository policies;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceOrderRepository orders;
    @Mock MarketplaceSellerOrderRepository sellerOrders;
    @Mock MarketplaceOrderLineRepository lines;
    @Mock MarketplacePlatformSettingRepository settingRows;
    @Mock ShippingPolicy shipping;
    @Mock MarketplacePaymentService payments;                       // MKT-1e2: cash orders never touch it
    @Mock org.springframework.beans.factory.ObjectProvider<SellerOrderService> sellerSideProvider;
    @Mock TradeClient trade;
    @Mock CatalogClient catalog;
    @Mock DocumentNumberService numbers;
    @Mock SellerAccess access;
    @Mock MarketplaceSellerService sellers;
    @Mock OrderService storeOrders;
    @Mock PlatformTransactionManager txManager;
    @Mock MarketplaceAuditService audit;                             // G-16: actions are audited

    MarketplaceCheckoutService checkout;
    SellerOrderService sellerSide;
    MarketplaceOrderSweeper sweeper;

    final Map<Long, MarketplaceOrder> orderTable = new HashMap<>();
    final Map<Long, MarketplaceSellerOrder> soTable = new HashMap<>();
    final List<MarketplaceOrderLine> lineTable = new ArrayList<>();
    final AtomicLong ids = new AtomicLong(1000);
    MarketplaceOfferProjection row;
    MarketplaceProduct product;
    MarketplaceOffer offer;

    @BeforeEach
    void wire() {
        MarketplaceSettingsService settings = new MarketplaceSettingsService(settingRows, access, audit);
        PublicOfferService publicOffers = new PublicOfferService(projections, products, settings);
        checkout = new MarketplaceCheckoutService(offers, projections, products, policies, accounts, orders, sellerOrders,
                lines, publicOffers, settings, shipping, trade, numbers, access, txManager, payments, sellerSideProvider);
        sellerSide = new SellerOrderService(sellerOrders, orders, lines, sellers, checkout, payments, storeOrders, catalog, trade,
                access, txManager, audit);
        lenient().when(sellerSideProvider.getObject()).thenReturn(sellerSide);
        sweeper = new MarketplaceOrderSweeper(sellerOrders, orders, sellerSide, payments, txManager);

        product = new MarketplaceProduct();
        product.setId(PRODUCT);
        product.setCanonicalName("Samsung Galaxy A32 128GB Black");
        product.setApprovalStatus("APPROVED");
        product.setRegulatedStatus("NONE");
        offer = new MarketplaceOffer();
        offer.setId(OFFER);
        offer.setMktProductId(PRODUCT);
        offer.setSourceProductId(SOURCE);
        offer.setStockSourceType("MERCHANT");
        offer.setSellerOrganizationId(SELLER);
        offer.setStockOwnerOrganizationId(SELLER);
        offer.setCustodianOrganizationId(SELLER);
        offer.setFulfillerOrganizationId(SELLER);
        offer.setPromiseHours(4);
        offer.setWarrantyPolicyId(1L);
        offer.setReturnPolicyId(2L);
        offer.setCommissionPolicyId(3L);
        row = new MarketplaceOfferProjection();
        row.setOfferId(OFFER);
        row.setMktProductId(PRODUCT);
        row.setSellerOrganizationId(SELLER);
        row.setStockSourceType("MERCHANT");
        row.setRegulatedStatus("NONE");
        row.setPrice(new BigDecimal("52000"));
        row.setAvailableQty(new BigDecimal("5"));
        row.setPromiseHours(4);
        row.setDeliveryAreas("Karachi");
        row.setStatus("LIVE");
        row.setLastSyncAt(LocalDateTime.now().minusMinutes(1));

        lenient().when(offers.findById(OFFER)).thenReturn(Optional.of(offer));
        lenient().when(projections.findById(OFFER)).thenAnswer(i -> Optional.of(row));
        lenient().when(products.findById(PRODUCT)).thenAnswer(i -> Optional.of(product));
        lenient().when(policies.findById(1L)).thenReturn(Optional.of(policy(1L, "WARRANTY")));
        lenient().when(policies.findById(2L)).thenReturn(Optional.of(policy(2L, "RETURN")));
        lenient().when(policies.findById(3L)).thenReturn(Optional.of(policy(3L, "COMMISSION")));
        MarketplaceSellerAccount acct = new MarketplaceSellerAccount();
        acct.setDisplayName("Shahzad Mobile Shop");
        lenient().when(accounts.findByOrganizationId(SELLER)).thenReturn(Optional.of(acct));
        lenient().when(shipping.codEnabled(anyLong())).thenReturn(true);
        lenient().when(numbers.next(0L, "MKT")).thenAnswer(i -> ids.incrementAndGet());
        lenient().when(access.org()).thenReturn(SELLER);
        lenient().when(access.userId()).thenReturn(70L);
        lenient().when(trade.holdStock(any())).thenReturn(held(true));

        // ── in-memory tables ──
        lenient().when(orders.saveAndFlush(any())).thenAnswer(i -> save(orderTable, i.getArgument(0)));
        lenient().when(orders.save(any())).thenAnswer(i -> save(orderTable, i.getArgument(0)));
        lenient().when(orders.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(orderTable.get((Long) i.getArgument(0))));
        lenient().when(orders.findAllById(anyList())).thenAnswer(i -> ((List<Long>) i.getArgument(0)).stream().map(orderTable::get).toList());
        lenient().when(orders.findByIdempotencyKey(anyString())).thenAnswer(i -> orderTable.values().stream()
                .filter(o -> o.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        lenient().when(orders.findByOrderNo(anyString())).thenAnswer(i -> orderTable.values().stream()
                .filter(o -> o.getOrderNo().equals(i.getArgument(0))).findFirst());
        lenient().when(orders.countByCustomerPhoneAndStatus(anyString(), anyString())).thenAnswer(i -> orderTable.values().stream()
                .filter(o -> o.getCustomerPhone().equals(i.getArgument(0)) && o.getStatus().equals(i.getArgument(1))).count());
        lenient().when(sellerOrders.saveAndFlush(any())).thenAnswer(i -> save(soTable, i.getArgument(0)));
        lenient().when(sellerOrders.save(any())).thenAnswer(i -> save(soTable, i.getArgument(0)));
        lenient().when(sellerOrders.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(soTable.get((Long) i.getArgument(0))));
        lenient().when(sellerOrders.findByMktOrderId(anyLong())).thenAnswer(i -> soTable.values().stream()
                .filter(s -> s.getMktOrderId().equals(i.getArgument(0))).toList());
        lenient().when(sellerOrders.findByIdAndSellerOrganizationId(anyLong(), anyLong())).thenAnswer(i -> Optional
                .ofNullable(soTable.get((Long) i.getArgument(0))).filter(s -> s.getSellerOrganizationId().equals(i.getArgument(1))));
        lenient().when(sellerOrders.findByAcceptanceStatusAndAcceptByBeforeOrderByAcceptByAsc(anyString(), any(), any()))
                .thenAnswer(i -> soTable.values().stream().filter(s -> s.getAcceptanceStatus().equals(i.getArgument(0))
                        && s.getAcceptBy() != null && s.getAcceptBy().isBefore(i.getArgument(1))).toList());
        lenient().when(sellerOrders.findByAcceptanceStatusAndCreatedAtBefore(anyString(), any(), any())).thenReturn(List.of());
        lenient().when(sellerOrders.findByHeldTrueAndAcceptanceStatusIn(any(), any())).thenReturn(List.of());
        lenient().when(lines.save(any())).thenAnswer(i -> { MarketplaceOrderLine l = i.getArgument(0); l.setId(ids.incrementAndGet()); lineTable.add(l); return l; });
        lenient().when(lines.findBySellerOrderIdOrderByIdAsc(anyLong())).thenAnswer(i -> lineTable.stream()
                .filter(l -> l.getSellerOrderId().equals(i.getArgument(0))).toList());
    }

    @SuppressWarnings("unchecked")
    <T> T save(Map<Long, T> table, T e) {
        try {
            var id = e.getClass().getMethod("getId");
            if (id.invoke(e) == null) e.getClass().getMethod("setId", Long.class).invoke(e, ids.incrementAndGet());
            var v = e.getClass().getMethod("getVersion");
            Integer cur = (Integer) v.invoke(e);
            e.getClass().getMethod("setVersion", Integer.class).invoke(e, cur == null ? 0 : cur + 1);
            var created = e.getClass().getMethod("getCreatedAt");
            if (created.invoke(e) == null) e.getClass().getMethod("setCreatedAt", LocalDateTime.class).invoke(e, LocalDateTime.now());
            table.put((Long) id.invoke(e), e);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static MarketplacePolicy policy(long id, String type) {
        MarketplacePolicy p = new MarketplacePolicy();
        p.setId(id);
        p.setPolicyType(type);
        p.setWarrantyProvider("Samsung Pakistan");
        p.setWarrantyMonths(12);
        p.setWarrantyStarts("DELIVERY");
        p.setReturnDays(7);
        p.setCommissionBasis("ITEMS");
        p.setCommissionRate(new BigDecimal("0.080000"));
        return p;
    }

    static StockHoldResponse held(boolean held) {
        StockHoldResponse r = new StockHoldResponse();
        r.setHeld(held);
        if (!held) r.setReason("Only 0 left");
        return r;
    }

    MarketplaceOrderDTOs.CheckoutRequest req(String key) {
        return new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 1, new BigDecimal("52000"), "Ali", "0300-123 4567",
                "1 Clifton", "Karachi", key);
    }

    MarketplaceOrderDTOs.CheckoutRequest card(String key, String token) {
        return new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 1, new BigDecimal("52000"), "Ali", "0300-123 4567",
                "1 Clifton", "Karachi", key, "CARD", token);
    }

    // ── checkout ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[MKT-R10.2] [MKT-R18.5] [MKT-R13.3] checkout holds the stock and asks the seller: SUBMITTED / OFFERED, never confirmed; the line is a snapshot")
    void checkoutHoldsAndOffers() {
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(req("k1"));
        assertThat(v.status()).isEqualTo("SUBMITTED");
        assertThat(v.sellerOrderStatus()).isEqualTo("OFFERED");
        assertThat(v.paymentMode()).isEqualTo("COD");
        assertThat(v.paymentStatus()).isEqualTo("UNPAID");
        assertThat(v.orderNo()).matches("MKT-\\d{6}");
        assertThat(v.total()).isEqualByComparingTo("52000");
        assertThat(v.secondsToAccept()).isBetween(290L, 300L);
        assertThat(v.sellerName()).isEqualTo("Shahzad Mobile Shop");

        ArgumentCaptor<StockHoldRequest> hold = ArgumentCaptor.forClass(StockHoldRequest.class);
        verify(trade).holdStock(hold.capture());
        assertThat(hold.getValue().getOrganizationId()).isEqualTo(SELLER);
        assertThat(hold.getValue().getLines().get(0).getItemId()).isEqualTo(SOURCE);
        assertThat(hold.getValue().getHoldKey()).isEqualTo(soTable.values().iterator().next().getHoldKey());

        MarketplaceOrderLine l = lineTable.get(0);
        assertThat(l.getUnitPrice()).isEqualByComparingTo("52000");
        assertThat(l.getWarrantyProvider()).isEqualTo("Samsung Pakistan");
        assertThat(l.getReturnDays()).isEqualTo(7);
        assertThat(l.getCommissionBasis()).isEqualTo("ITEMS");
        assertThat(l.getSettlementStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(orderTable.values().iterator().next().getCustomerPhone()).as("phone stored as digits").isEqualTo("03001234567");
    }

    @Test
    @DisplayName("[MKT-R22.3] a double submit with the same key is the SAME order, one hold")
    void idempotent() {
        String a = checkout.checkout(req("same")).orderNo();
        String b = checkout.checkout(req("same")).orderNo();
        assertThat(b).isEqualTo(a);
        assertThat(orderTable).hasSize(1);
        verify(trade).holdStock(any());
    }

    @Test
    @DisplayName("[MKT-R22.2] the price is the server's: a changed price refuses before anything is created")
    void priceChanged() {
        row.setPrice(new BigDecimal("53000"));
        assertThatThrownBy(() -> checkout.checkout(req("p"))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("The price changed to Rs. 53,000");
        assertThat(orderTable).isEmpty();
        verify(trade, never()).holdStock(any());
    }

    @Test
    @DisplayName("[MKT-R20.1] a seller who takes no cash on delivery is refused in Phase 1 (COD only), before anything is held")
    void codOffRefused() {
        when(shipping.codEnabled(any())).thenReturn(false);
        assertThatThrownBy(() -> checkout.checkout(req("cod"))).isInstanceOf(ValidationException.class)
                .hasMessage("This seller does not accept cash on delivery yet. Please choose another offer.");
        assertThat(orderTable).isEmpty();
        verify(trade, never()).holdStock(any());
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R20.1] a city the seller does not serve, or more than it has, refuses before anything is created")
    void eligibility() {
        assertThatThrownBy(() -> checkout.checkout(new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 1, new BigDecimal("52000"),
                "Ali", "03001234567", "1 Mall Road", "Lahore", "c"))).hasMessageContaining("cannot deliver 1 to Lahore");
        assertThatThrownBy(() -> checkout.checkout(new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 6, new BigDecimal("52000"),
                "Ali", "03001234567", "1 Clifton", "Karachi", "q"))).hasMessageContaining("cannot deliver 6");
        assertThat(orderTable).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R10.2] the hold refused: the order is CANCELLED and the shopper told, never left pending")
    void holdRefused() {
        when(trade.holdStock(any())).thenReturn(held(false));
        assertThatThrownBy(() -> checkout.checkout(req("h"))).hasMessageContaining("no longer has enough stock");
        assertThat(orderTable.values().iterator().next().getStatus()).isEqualTo("CANCELLED");
        assertThat(soTable.values().iterator().next().getAcceptanceStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("[MKT-R18.5] an inventory outage is 'not held', never 'held'")
    void holdOutage() {
        when(trade.holdStock(any())).thenThrow(new RuntimeException("connect timed out"));
        assertThatThrownBy(() -> checkout.checkout(req("o"))).hasMessageContaining("no longer has enough stock");
        assertThat(soTable.values().iterator().next().getHeld()).isFalse();
    }

    @Test
    @DisplayName("[MKT-R22.3] one phone can have at most 3 orders waiting (whatever its formatting)")
    void phoneGuard() {
        for (int i = 0; i < 3; i++) checkout.checkout(req("g" + i));
        assertThatThrownBy(() -> checkout.checkout(new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 1, new BigDecimal("52000"),
                "Ali", "(0300) 1234567", "1 Clifton", "Karachi", "g4"))).hasMessageContaining("already have 3 orders waiting");
    }

    @Test
    @DisplayName("[MKT-R20.1] a seller that does not take cash refuses COD at checkout")
    void codRefused() {
        when(shipping.codEnabled(SELLER)).thenReturn(false);
        assertThatThrownBy(() -> checkout.checkout(req("cod"))).hasMessageContaining("does not accept cash on delivery");
        assertThat(orderTable).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R22.1] input is bounded: quantity 1–10, a callable phone, an address, a city")
    void validation() {
        assertThatThrownBy(() -> MarketplaceCheckoutService.validate(new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 11,
                BigDecimal.ONE, "Ali", "03001234567", "1 Clifton", "Karachi", "k"))).hasMessageContaining("1 to 10");
        assertThatThrownBy(() -> MarketplaceCheckoutService.validate(new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 1,
                BigDecimal.ONE, "Ali", "call me", "1 Clifton", "Karachi", "k"))).hasMessageContaining("phone number");
        assertThatThrownBy(() -> MarketplaceCheckoutService.validate(new MarketplaceOrderDTOs.CheckoutRequest(OFFER, 1,
                BigDecimal.ONE, "Ali", "03001234567", "x", "Karachi", "k"))).hasMessageContaining("address");
    }

    @Test
    @DisplayName("[MKT-R22.1] tracking needs the order number AND the phone; formatting does not matter")
    void tracking() {
        String no = checkout.checkout(req("t")).orderNo();
        assertThat(checkout.track(no, "+92 300 123 4567".replace("+92 ", "0")).orderNo()).isEqualTo(no);
        assertThatThrownBy(() -> checkout.track(no, "03009999999")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No such order.");
        assertThatThrownBy(() -> checkout.track("MKT-999999", "03001234567")).hasMessage("No such order.");
    }

    // ── accept / reject / expire ───────────────────────────────────────────────────────────────────────

    MarketplaceSellerOrder offered() {
        checkout.checkout(req("so" + ids.get()));
        return soTable.values().stream().filter(s -> "OFFERED".equals(s.getAcceptanceStatus())).findFirst().orElseThrow();
    }

    OrderDTO sale() {
        OrderDTO d = new OrderDTO();
        d.setId(9001L);
        d.setOrderNo("SO-000042");
        d.setInvoiceNo("INV-000099");
        return d;
    }

    @Test
    @DisplayName("[MKT-R10.1] [MKT-R1.3] accept: release the hold, THEN the sale in the seller's books at the marketplace price; order CONFIRMED")
    void acceptRecordsTheSale() {
        MarketplaceSellerOrder so = offered();
        when(catalog.getProductsFresh(anyList(), anyBoolean())).thenReturn(List.of());
        when(storeOrders.placeMarketplace(any())).thenReturn(sale());
        MarketplaceOrderDTOs.SellerOrderView v = sellerSide.accept(so.getId(), new MarketplaceOrderDTOs.AcceptRequest(so.getVersion(), null));
        assertThat(v.acceptanceStatus()).isEqualTo("ACCEPTED");
        assertThat(v.invoiceNo()).isEqualTo("INV-000099");
        assertThat(v.storeOrderNo()).isEqualTo("SO-000042");
        assertThat(orderTable.get(so.getMktOrderId()).getStatus()).isEqualTo("CONFIRMED");

        InOrder order = inOrder(trade, storeOrders);
        order.verify(trade).releaseHold(so.getHoldKey());
        ArgumentCaptor<OrderService.MarketplaceSale> s = ArgumentCaptor.forClass(OrderService.MarketplaceSale.class);
        order.verify(storeOrders).placeMarketplace(s.capture());
        assertThat(s.getValue().sellerOrganizationId()).isEqualTo(SELLER);
        assertThat(s.getValue().idempotencyKey()).isEqualTo("MKT-SO-" + so.getId());
        assertThat(s.getValue().lines().get(0).unitPrice()).isEqualByComparingTo("52000");
        assertThat(s.getValue().lines().get(0).productId()).isEqualTo(SOURCE);
    }

    @Test
    @DisplayName("[MKT-R10.1] a serial-tracked phone needs its IMEI BEFORE the hold is let go")
    void serialsBeforeRelease() {
        MarketplaceSellerOrder so = offered();
        ProductRef ref = new ProductRef();
        ref.setId(SOURCE);
        ref.setRequiresSerial(true);
        when(catalog.getProductsFresh(anyList(), anyBoolean())).thenReturn(List.of(ref));
        assertThatThrownBy(() -> sellerSide.accept(so.getId(), new MarketplaceOrderDTOs.AcceptRequest(so.getVersion(), null)))
                .hasMessageContaining("serial number (IMEI)");
        verify(trade, never()).releaseHold(anyString());
        verify(storeOrders, never()).placeMarketplace(any());

        when(storeOrders.placeMarketplace(any())).thenReturn(sale());
        Long lineId = lineTable.stream().filter(l -> l.getSellerOrderId().equals(so.getId())).findFirst().orElseThrow().getId();
        sellerSide.accept(so.getId(), new MarketplaceOrderDTOs.AcceptRequest(so.getVersion(), Map.of(lineId, List.of(" 356938035643809 "))));
        ArgumentCaptor<OrderService.MarketplaceSale> s = ArgumentCaptor.forClass(OrderService.MarketplaceSale.class);
        verify(storeOrders).placeMarketplace(s.capture());
        assertThat(s.getValue().lines().get(0).serials()).containsExactly("356938035643809");
    }

    @Test
    @DisplayName("[MKT-R10.2] a sale refused at accept re-holds the stock and leaves the order OFFERED, reason shown")
    void saleRefusedReholds() {
        MarketplaceSellerOrder so = offered();
        when(catalog.getProductsFresh(anyList(), anyBoolean())).thenReturn(List.of());
        when(storeOrders.placeMarketplace(any())).thenThrow(new ValidationException("Only 0 of Galaxy A32 in stock"));
        assertThatThrownBy(() -> sellerSide.accept(so.getId(), new MarketplaceOrderDTOs.AcceptRequest(so.getVersion(), null)))
                .hasMessageContaining("The sale could not be recorded");
        assertThat(soTable.get(so.getId()).getAcceptanceStatus()).isEqualTo("OFFERED");
        assertThat(soTable.get(so.getId()).getHeld()).as("re-held, and the flag says so").isTrue();
        assertThat(orderTable.get(so.getMktOrderId()).getStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("[MKT-R10.2] [MKT-R19.1] accept at or after the deadline is refused: EXPIRED, never ACCEPTED")
    void acceptAfterDeadline() {
        MarketplaceSellerOrder so = offered();
        so.setAcceptBy(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> sellerSide.accept(so.getId(), new MarketplaceOrderDTOs.AcceptRequest(so.getVersion(), null)))
                .hasMessage("This order expired before it was accepted.");
        verify(storeOrders, never()).placeMarketplace(any());
    }

    @Test
    @DisplayName("[MKT-R10.2] a stale row on an EXPIRED order says 'expired', not 'someone else changed it'; a stale OFFERED row is still a conflict")
    void expiredBeatsStaleVersion() {
        MarketplaceSellerOrder so = offered();
        Integer seen = so.getVersion();
        so.setAcceptanceStatus("EXPIRED");                       // the sweeper moved it after the seller's page loaded
        so.setVersion(seen + 1);
        assertThatThrownBy(() -> sellerSide.accept(so.getId(), new MarketplaceOrderDTOs.AcceptRequest(seen, null)))
                .hasMessage("This order expired before it was accepted.");
        assertThatThrownBy(() -> sellerSide.reject(so.getId(), new MarketplaceOrderDTOs.RejectRequest(seen, "no stock")))
                .hasMessageContaining("cannot be rejected");
        MarketplaceSellerOrder live = offered();
        assertThatThrownBy(() -> sellerSide.accept(live.getId(), new MarketplaceOrderDTOs.AcceptRequest(live.getVersion() - 1, null)))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(storeOrders, never()).placeMarketplace(any());
    }

    @Test
    @DisplayName("[MKT-R22.1] another seller's order reads 'No such order.'; a suspended seller cannot accept")
    void tenancyAndActiveSeller() {
        MarketplaceSellerOrder so = offered();
        when(access.org()).thenReturn(OTHER_SELLER);
        assertThatThrownBy(() -> sellerSide.accept(so.getId(), null)).hasMessage("No such order.");
        assertThatThrownBy(() -> sellerSide.reject(so.getId(), new MarketplaceOrderDTOs.RejectRequest(null, "x"))).hasMessage("No such order.");
        when(access.org()).thenReturn(SELLER);
        doThrow(new ValidationException("Your seller account is suspended")).when(sellers).assertActiveSeller();
        assertThatThrownBy(() -> sellerSide.accept(so.getId(), null)).hasMessageContaining("suspended");
        verify(trade, never()).releaseHold(anyString());
    }

    @Test
    @DisplayName("[MKT-R10.2] [MKT-R10.5] reject: a reason is required; then REJECTED, order CANCELLED, stock released")
    void reject() {
        MarketplaceSellerOrder so = offered();
        assertThatThrownBy(() -> sellerSide.reject(so.getId(), new MarketplaceOrderDTOs.RejectRequest(so.getVersion(), " ")))
                .hasMessageContaining("Give a reason");
        sellerSide.reject(so.getId(), new MarketplaceOrderDTOs.RejectRequest(so.getVersion(), "out of stock in store"));
        assertThat(soTable.get(so.getId()).getAcceptanceStatus()).isEqualTo("REJECTED");
        assertThat(orderTable.get(so.getMktOrderId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(orderTable.get(so.getMktOrderId()).getCancelReason()).isEqualTo("The seller could not fulfil this order.");
        verify(trade).releaseHold(so.getHoldKey());
        assertThat(soTable.get(so.getId()).getHeld()).isFalse();
    }

    @Test
    @DisplayName("[MKT-R10.2] [MKT-R10.5] [MKT-R19.1] the sweeper expires past the deadline + grace, cancels, releases — exactly once")
    void sweeperExpires() {
        MarketplaceSellerOrder so = offered();
        so.setAcceptBy(LocalDateTime.now().minusSeconds(10));
        sweeper.sweep();
        assertThat(soTable.get(so.getId()).getAcceptanceStatus()).as("inside the grace: untouched").isEqualTo("OFFERED");
        so.setAcceptBy(LocalDateTime.now().minus(MarketplaceOrderSweeper.GRACE).minusSeconds(1));
        sweeper.sweep();
        sweeper.sweep();
        assertThat(soTable.get(so.getId()).getAcceptanceStatus()).isEqualTo("EXPIRED");
        assertThat(orderTable.get(so.getMktOrderId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(orderTable.get(so.getMktOrderId()).getCancelReason()).isEqualTo("The seller did not confirm in time.");
        verify(trade).releaseHold(eq(so.getHoldKey()));
    }

    @Test
    @DisplayName("[MKT-R7.4] the acceptance window is the operator's (1–60 min); a stored nonsense value reads as 5")
    void acceptWindow() {
        MarketplaceSettingsService s = new MarketplaceSettingsService(settingRows, access, audit);
        assertThat(s.acceptMinutes()).isEqualTo(5);
        assertThatThrownBy(() -> s.setAcceptMinutes(0)).hasMessageContaining("1 to 60");
        assertThatThrownBy(() -> s.setAcceptMinutes(61)).hasMessageContaining("1 to 60");
        assertThat(s.setAcceptMinutes(1)).isEqualTo(1);
    }
    // ── MKT-1e2: card payment and the customer's cancel ─────────────────────────────────────────────────

    @Test
    @DisplayName("[MKT-R20.1] online payment needs an account; nothing is created or held without one")
    void cardNeedsAccount() {
        assertThatThrownBy(() -> checkout.checkout(card("c1", "tok"), null)).hasMessage("Sign in to pay online.");
        assertThat(orderTable).isEmpty();
        verify(trade, never()).holdStock(any());
    }

    @Test
    @DisplayName("[MKT-R19.1] a card order does not need the seller to take cash; paid → offered to the seller, CARD, owned")
    void cardPaidOffered() {
        lenient().when(shipping.codEnabled(anyLong())).thenReturn(false);
        when(payments.charge(any(), eq("tok"))).thenReturn(MarketplacePaymentService.Outcome.SUCCEEDED);
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(card("c2", "tok"), 77L);
        assertThat(v.sellerOrderStatus()).isEqualTo("OFFERED");
        assertThat(v.paymentMode()).isEqualTo("CARD");
        assertThat(orderTable.values()).singleElement().satisfies(o -> assertThat(o.getCustomerId()).isEqualTo(77L));
    }

    @Test
    @DisplayName("[MKT-R19.1] a declined card cancels the order and gives the held stock back; the seller never sees it")
    void cardDeclined() {
        when(payments.charge(any(), eq("fail"))).thenReturn(MarketplacePaymentService.Outcome.FAILED);
        assertThatThrownBy(() -> checkout.checkout(card("c3", "fail"), 77L)).hasMessage(MarketplaceCheckoutService.DECLINED);
        MarketplaceSellerOrder so = soTable.values().iterator().next();
        assertThat(so.getAcceptanceStatus()).isEqualTo("CANCELLED");
        assertThat(orderTable.values().iterator().next().getStatus()).isEqualTo("CANCELLED");
        verify(trade).releaseHold(so.getHoldKey());
    }

    @Test
    @DisplayName("[MKT-R22.3] a lost payment answer is never offered to a seller: PAYMENT_PENDING, stock still held for the sweeper")
    void cardUnknown() {
        when(payments.charge(any(), eq("tok"))).thenReturn(MarketplacePaymentService.Outcome.UNKNOWN);
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(card("c4", "tok"), 77L);
        assertThat(v.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(v.sellerOrderStatus()).isEqualTo("UNASSIGNED");
        assertThat(soTable.values().iterator().next().getHeld()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R10.5] the customer cancels while the seller has not answered: stock back, refund asked; after Accept it cannot")
    void customerCancel() {
        MarketplaceAccountService accounts = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit);
        com.myplus.marketplace.multiseller.entity.MarketplaceCustomer me = new com.myplus.marketplace.multiseller.entity.MarketplaceCustomer();
        me.setId(77L);
        checkout.checkout(req("cc1"), 77L);
        MarketplaceSellerOrder so = soTable.values().iterator().next();
        MarketplaceOrder o = orderTable.get(so.getMktOrderId());
        lenient().when(orders.findByOrderNo(o.getOrderNo())).thenReturn(java.util.Optional.of(o));
        MarketplaceOrderDTOs.AccountOrderView v = accounts.cancel(me, o.getOrderNo().toLowerCase(), "changed my mind");
        assertThat(v.status()).isEqualTo("CANCELLED");
        assertThat(v.cancelReason()).startsWith(MarketplaceAccountService.CANCELLED_BY_YOU);
        assertThat(so.getAcceptanceStatus()).isEqualTo("CANCELLED");
        verify(trade).releaseHold(so.getHoldKey());
        verify(payments).refundIfCancelled(o.getId());
        com.myplus.marketplace.multiseller.entity.MarketplaceCustomer stranger = new com.myplus.marketplace.multiseller.entity.MarketplaceCustomer();
        stranger.setId(78L);
        assertThatThrownBy(() -> accounts.cancel(stranger, o.getOrderNo(), null)).hasMessage("No such order.");
    }

    @Test
    @DisplayName("[MKT-R10.5] after the seller accepts, the customer's cancel is refused with the way forward")
    void cancelAfterAccept() {
        MarketplaceAccountService accounts = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit);
        com.myplus.marketplace.multiseller.entity.MarketplaceCustomer me = new com.myplus.marketplace.multiseller.entity.MarketplaceCustomer();
        me.setId(77L);
        checkout.checkout(req("cc2"), 77L);
        MarketplaceSellerOrder so = soTable.values().iterator().next();
        so.setAcceptanceStatus("ACCEPTED");
        MarketplaceOrder o = orderTable.get(so.getMktOrderId());
        lenient().when(orders.findByOrderNo(o.getOrderNo())).thenReturn(java.util.Optional.of(o));
        assertThatThrownBy(() -> accounts.cancel(me, o.getOrderNo(), null)).hasMessageContaining("already confirmed by the seller");
        verify(trade, never()).releaseHold(anyString());
    }

    @Test
    @DisplayName("[MKT-R22.4] a reject is audited under the seller; an accept that is refused (expired) records nothing")
    void ordersAudited() {
        MarketplaceSellerOrder late = offered();
        late.setAcceptBy(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> sellerSide.accept(late.getId(), new MarketplaceOrderDTOs.AcceptRequest(late.getVersion(), null)));
        verify(audit, never()).event(eq("MKT_ORDER_ACCEPTED"), any(), any(), any(), any(), any(), any(), any(), any());
        MarketplaceSellerOrder so = offered();
        sellerSide.reject(so.getId(), new MarketplaceOrderDTOs.RejectRequest(so.getVersion(), "out of stock"));
        verify(audit).event(eq("MKT_ORDER_REJECTED"), eq("MKT_SELLER_ORDER"), any(), eq(SELLER),
                eq(MarketplaceAuditService.Actor.SELLER), eq("OFFERED"), eq("REJECTED"), any(), eq("out of stock"));
    }

    // ── MKT-2a: one checkout, several sellers ───────────────────────────────────────────────────────────

    static final long OFFER_B = 12L, SOURCE_B = 556L;

    /** A second seller's live offer for the same product, at 51,500; the operator's switch as given. */
    void secondSeller(boolean multiSellerOn) {
        MarketplaceOffer b = new MarketplaceOffer();
        b.setId(OFFER_B);
        b.setMktProductId(PRODUCT);
        b.setSourceProductId(SOURCE_B);
        b.setStockSourceType("MERCHANT");
        b.setSellerOrganizationId(OTHER_SELLER);
        b.setStockOwnerOrganizationId(OTHER_SELLER);
        b.setCustodianOrganizationId(OTHER_SELLER);
        b.setFulfillerOrganizationId(OTHER_SELLER);
        b.setPromiseHours(24);
        MarketplaceOfferProjection rb = new MarketplaceOfferProjection();
        rb.setOfferId(OFFER_B);
        rb.setMktProductId(PRODUCT);
        rb.setSellerOrganizationId(OTHER_SELLER);
        rb.setStockSourceType("MERCHANT");
        rb.setRegulatedStatus("NONE");
        rb.setPrice(new BigDecimal("51500"));
        rb.setAvailableQty(new BigDecimal("5"));
        rb.setPromiseHours(24);
        rb.setDeliveryAreas("Karachi");
        rb.setStatus("LIVE");
        rb.setLastSyncAt(LocalDateTime.now().minusMinutes(1));
        lenient().when(offers.findById(OFFER_B)).thenReturn(Optional.of(b));
        lenient().when(projections.findById(OFFER_B)).thenReturn(Optional.of(rb));
        MarketplaceSellerAccount acct = new MarketplaceSellerAccount();
        acct.setDisplayName("Mobile Distributor");
        lenient().when(accounts.findByOrganizationId(OTHER_SELLER)).thenReturn(Optional.of(acct));
        com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting on = new com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting();
        on.setSettingKey("checkout.multiSeller");
        on.setSettingValue(String.valueOf(multiSellerOn));
        lenient().when(settingRows.findById("checkout.multiSeller")).thenReturn(Optional.of(on));
    }

    MarketplaceOrderDTOs.CheckoutRequest basket(String key, String mode, MarketplaceOrderDTOs.CheckoutLine... ls) {
        return new MarketplaceOrderDTOs.CheckoutRequest(null, null, null, "Ali", "0300-123 4567", "1 Clifton", "Karachi", key,
                mode, "CARD".equals(mode) ? "tok" : null, List.of(ls));
    }

    static MarketplaceOrderDTOs.CheckoutLine lineA(int qty) {
        return new MarketplaceOrderDTOs.CheckoutLine(OFFER, qty, new BigDecimal("52000"));
    }

    static MarketplaceOrderDTOs.CheckoutLine lineB(int qty) {
        return new MarketplaceOrderDTOs.CheckoutLine(OFFER_B, qty, new BigDecimal("51500"));
    }

    MarketplaceSellerOrder part(long seller) {
        return soTable.values().stream().filter(x -> x.getSellerOrganizationId().equals(seller)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("[MKT-R17.1] the multi-seller checkout is off until the operator switches it on: two sellers are refused, nothing created")
    void multiSellerOffRefused() {
        secondSeller(false);
        assertThatThrownBy(() -> checkout.checkout(basket("m0", null, lineA(1), lineB(1))))
                .hasMessage("Items from different sellers must be checked out separately.");
        assertThat(orderTable).isEmpty();
        verify(trade, never()).holdStock(any());
        assertThat(checkout.checkout(basket("m0b", null, lineA(2))).sellerOrders()).as("one seller, one part: still fine").hasSize(1);
    }

    @Test
    @DisplayName("[MKT-R17.2] [MKT-R20.3] one parent order, one part per seller: each holds only its own lines under its own key, each has its own deadline and promise")
    void splitsPerSeller() {
        secondSeller(true);
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(basket("m1", null, lineA(1), lineB(2)));
        assertThat(v.status()).isEqualTo("SUBMITTED");
        assertThat(v.total()).isEqualByComparingTo("155000");                         // 52,000 + 2 × 51,500
        assertThat(v.sellerOrders()).hasSize(2);
        assertThat(v.sellerOrders()).extracting(MarketplaceOrderDTOs.PartView::sellerName).containsExactly("Shahzad Mobile Shop", "Mobile Distributor");
        assertThat(v.sellerOrders()).extracting(MarketplaceOrderDTOs.PartView::status).containsOnly("OFFERED");
        assertThat(v.sellerOrders()).extracting(MarketplaceOrderDTOs.PartView::total).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("52000"), new BigDecimal("103000"));
        assertThat(v.sellerOrders().get(0).promisedBy()).isBefore(v.sellerOrders().get(1).promisedBy());   // 4 h against 24 h
        assertThat(v.sellerName()).isEqualTo("Shahzad Mobile Shop, Mobile Distributor");

        ArgumentCaptor<StockHoldRequest> holds = ArgumentCaptor.forClass(StockHoldRequest.class);
        verify(trade, org.mockito.Mockito.times(2)).holdStock(holds.capture());
        assertThat(holds.getAllValues()).extracting(StockHoldRequest::getOrganizationId).containsExactly(SELLER, OTHER_SELLER);
        assertThat(holds.getAllValues().get(1).getLines()).hasSize(1);
        assertThat(holds.getAllValues().get(1).getLines().get(0).getItemId()).isEqualTo(SOURCE_B);
        assertThat(holds.getAllValues().get(1).getLines().get(0).getQuantity()).isEqualByComparingTo("2");
        assertThat(holds.getAllValues().get(0).getHoldKey()).isNotEqualTo(holds.getAllValues().get(1).getHoldKey());
        assertThat(lineTable).extracting(MarketplaceOrderLine::getSellerOrganizationId).containsExactly(SELLER, OTHER_SELLER);
    }

    @Test
    @DisplayName("[MKT-R17.2] all or nothing: one seller cannot hold → every part cancelled, every hold released, that seller named")
    void oneRefusalCancelsAll() {
        secondSeller(true);
        when(trade.holdStock(any())).thenReturn(held(true), held(false));
        assertThatThrownBy(() -> checkout.checkout(basket("m2", null, lineA(1), lineB(1))))
                .hasMessage("Mobile Distributor no longer has enough stock. Please remove its items and place the order again.");
        assertThat(orderTable.values().iterator().next().getStatus()).isEqualTo("CANCELLED");
        assertThat(soTable.values()).extracting(MarketplaceSellerOrder::getAcceptanceStatus).containsOnly("CANCELLED");
        verify(trade).releaseHold(part(SELLER).getHoldKey());
        verify(trade).releaseHold(part(OTHER_SELLER).getHoldKey());
    }

    @Test
    @DisplayName("[MKT-R17.2] a card is charged ONCE for the whole basket, after every part is held")
    void cardChargedOnce() {
        secondSeller(true);
        when(payments.charge(any(), eq("tok"))).thenReturn(MarketplacePaymentService.Outcome.SUCCEEDED);
        checkout.checkout(basket("m3", "CARD", lineA(1), lineB(1)), 77L);
        ArgumentCaptor<MarketplaceOrder> charged = ArgumentCaptor.forClass(MarketplaceOrder.class);
        verify(payments).charge(charged.capture(), eq("tok"));
        assertThat(charged.getValue().getTotal()).isEqualByComparingTo("103500");
        InOrder o = inOrder(trade, payments);
        o.verify(trade, org.mockito.Mockito.times(2)).holdStock(any());
        o.verify(payments).charge(any(), any());
    }

    @Test
    @DisplayName("[MKT-R17.2] each seller answers for its own part: one accepts (order CONFIRMED), the other rejects (that part's money back, the order goes ahead)")
    void partsLiveOnTheirOwn() {
        secondSeller(true);
        checkout.checkout(basket("m4", null, lineA(1), lineB(1)));
        MarketplaceSellerOrder a = part(SELLER), b = part(OTHER_SELLER);
        when(catalog.getProductsFresh(anyList(), anyBoolean())).thenReturn(List.of());
        when(storeOrders.placeMarketplace(any())).thenReturn(sale());
        MarketplaceOrderDTOs.SellerOrderView mine = sellerSide.accept(a.getId(), new MarketplaceOrderDTOs.AcceptRequest(a.getVersion(), null));
        assertThat(mine.total()).as("the seller sees its own part, never the basket").isEqualByComparingTo("52000");
        assertThat(mine.lines()).hasSize(1);
        MarketplaceOrder parent = orderTable.get(a.getMktOrderId());
        assertThat(parent.getStatus()).isEqualTo("CONFIRMED");

        when(access.org()).thenReturn(OTHER_SELLER);
        sellerSide.reject(b.getId(), new MarketplaceOrderDTOs.RejectRequest(b.getVersion(), "no stock"));
        assertThat(parent.getStatus()).as("the accepted part goes ahead").isEqualTo("CONFIRMED");
        verify(trade).releaseHold(b.getHoldKey());
        verify(payments).refundPart(eq(parent.getId()), eq(b.getId()), org.mockito.ArgumentMatchers.argThat(x -> x.compareTo(new BigDecimal("51500")) == 0), anyString());
        verify(payments, never()).refundIfCancelled(anyLong());
    }

    @Test
    @DisplayName("[MKT-R17.2] the order ends only when its last part ends: the first rejection leaves it waiting, the second cancels it and refunds the rest")
    void lastPartEndsTheOrder() {
        secondSeller(true);
        checkout.checkout(basket("m5", null, lineA(1), lineB(1)));
        MarketplaceSellerOrder a = part(SELLER), b = part(OTHER_SELLER);
        MarketplaceOrder parent = orderTable.get(a.getMktOrderId());
        sellerSide.reject(a.getId(), new MarketplaceOrderDTOs.RejectRequest(a.getVersion(), "no stock"));
        assertThat(parent.getStatus()).isEqualTo("SUBMITTED");
        b.setAcceptBy(LocalDateTime.now().minus(MarketplaceOrderSweeper.GRACE).minusSeconds(1));
        sweeper.sweep();
        assertThat(soTable.get(b.getId()).getAcceptanceStatus()).isEqualTo("EXPIRED");
        assertThat(parent.getStatus()).isEqualTo("CANCELLED");
        assertThat(parent.getCancelReason()).isEqualTo("The seller did not confirm in time.");
        verify(payments).refundPart(eq(parent.getId()), eq(a.getId()), any(), anyString());
        verify(payments).refundIfCancelled(parent.getId());
    }

    @Test
    @DisplayName("[MKT-R17.2] a part that expires after the other was accepted never cancels the order")
    void expiryAfterAcceptKeepsTheOrder() {
        secondSeller(true);
        checkout.checkout(basket("m6", null, lineA(1), lineB(1)));
        MarketplaceSellerOrder a = part(SELLER), b = part(OTHER_SELLER);
        when(catalog.getProductsFresh(anyList(), anyBoolean())).thenReturn(List.of());
        when(storeOrders.placeMarketplace(any())).thenReturn(sale());
        sellerSide.accept(a.getId(), new MarketplaceOrderDTOs.AcceptRequest(a.getVersion(), null));
        b.setAcceptBy(LocalDateTime.now().minus(MarketplaceOrderSweeper.GRACE).minusSeconds(1));
        sweeper.sweep();
        assertThat(soTable.get(b.getId()).getAcceptanceStatus()).isEqualTo("EXPIRED");
        assertThat(orderTable.get(a.getMktOrderId()).getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("[MKT-R17.2] the customer cancels a basket no seller has accepted: every part at once; once one accepted, it is support")
    void customerCancelsEveryPart() {
        secondSeller(true);
        MarketplaceAccountService acc = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit);
        com.myplus.marketplace.multiseller.entity.MarketplaceCustomer me = new com.myplus.marketplace.multiseller.entity.MarketplaceCustomer();
        me.setId(77L);
        String no = checkout.checkout(basket("m7", null, lineA(1), lineB(1)), 77L).orderNo();
        MarketplaceOrderDTOs.AccountOrderView v = acc.cancel(me, no, null);
        assertThat(v.status()).isEqualTo("CANCELLED");
        assertThat(v.sellerOrders()).extracting(MarketplaceOrderDTOs.PartView::status).containsOnly("CANCELLED");
        verify(trade).releaseHold(part(SELLER).getHoldKey());
        verify(trade).releaseHold(part(OTHER_SELLER).getHoldKey());
    }

    @Test
    @DisplayName("[MKT-R17.1] [MKT-R20.3] the switch is the operator's and off by default; a stored nonsense value reads as off")
    void multiSellerSwitch() {
        MarketplaceSettingsService s = new MarketplaceSettingsService(settingRows, access, audit);
        assertThat(s.multiSeller()).isFalse();
        com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting junk = new com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting();
        junk.setSettingValue("yes please");
        when(settingRows.findById("checkout.multiSeller")).thenReturn(Optional.of(junk));
        assertThat(s.multiSeller()).isFalse();
        doThrow(new org.springframework.security.access.AccessDeniedException("operators only")).when(access).assertOperator();
        assertThatThrownBy(() -> s.setMultiSeller(true)).hasMessage("operators only");
    }

    @Test
    @DisplayName("[MKT-R22.1] a basket is bounded: the same offer twice is one line; more than 10 of an item, or two prices for it, are refused")
    void basketBounds() {
        assertThat(MarketplaceCheckoutService.wants(basket("w", null, lineA(2), lineA(3)))).singleElement()
                .satisfies(w -> assertThat(w.qty()).isEqualTo(5));
        assertThatThrownBy(() -> MarketplaceCheckoutService.wants(basket("w", null, lineA(6), lineA(5))))
                .hasMessageContaining("1 to 10 of each item");
        assertThatThrownBy(() -> MarketplaceCheckoutService.wants(basket("w", null, lineA(1),
                new MarketplaceOrderDTOs.CheckoutLine(OFFER, 1, new BigDecimal("1"))))).hasMessageContaining("two prices");
    }
}
