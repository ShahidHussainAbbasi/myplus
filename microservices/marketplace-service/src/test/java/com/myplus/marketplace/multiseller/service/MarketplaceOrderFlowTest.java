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
    @Mock com.myplus.marketplace.multiseller.repository.MarketplaceShortageRepository shortageRows;   // MKT-2b

    MarketplaceCheckoutService checkout;
    SellerOrderService sellerSide;
    MarketplaceOrderSweeper sweeper;
    MarketplaceSettingsService settings;
    MarketplaceShortageService shortages;
    LiveRouting routing;
    final Map<Long, com.myplus.marketplace.multiseller.entity.MarketplaceShortage> shTable = new HashMap<>();

    final Map<Long, MarketplaceOrder> orderTable = new HashMap<>();
    final Map<Long, MarketplaceSellerOrder> soTable = new HashMap<>();
    final List<MarketplaceOrderLine> lineTable = new ArrayList<>();
    final AtomicLong ids = new AtomicLong(1000);
    MarketplaceOfferProjection row;
    MarketplaceProduct product;
    MarketplaceOffer offer;

    @BeforeEach
    void wire() {
        settings = new MarketplaceSettingsService(settingRows, access, audit);
        PublicOfferService publicOffers = new PublicOfferService(projections, products, settings);
        checkout = new MarketplaceCheckoutService(offers, projections, products, policies, accounts, orders, sellerOrders,
                lines, publicOffers, settings, shipping, trade, numbers, access, txManager, payments, shortageRows, sellerSideProvider,
                routing = new LiveRouting(trade, java.time.Duration.ofMillis(1000), java.time.Duration.ofMillis(2000), 3,
                        java.time.Duration.ofSeconds(30), false, System::nanoTime, () -> null));
        shortages = new MarketplaceShortageService(shortageRows, sellerOrders, orders, lines, offers, projections, products,
                publicOffers, settings, checkout, payments, trade, access, audit, txManager, sellerSideProvider);
        sellerSide = new SellerOrderService(sellerOrders, orders, lines, sellers, checkout, payments, storeOrders, catalog, trade,
                access, txManager, audit, shortages, shortageRows);
        lenient().when(sellerSideProvider.getObject()).thenReturn(sellerSide);
        sweeper = new MarketplaceOrderSweeper(sellerOrders, orders, sellerSide, payments, txManager, shortages);

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
        // MKT-2b: the shortage records, in memory too
        lenient().when(shortageRows.save(any())).thenAnswer(i -> save(shTable, i.getArgument(0)));
        lenient().when(shortageRows.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(shTable.get((Long) i.getArgument(0))));
        lenient().when(shortageRows.findBySellerOrderId(anyLong())).thenAnswer(i -> shTable.values().stream()
                .filter(x -> x.getSellerOrderId().equals(i.getArgument(0))).findFirst());
        lenient().when(shortageRows.findByMktOrderIdOrderByIdAsc(anyLong())).thenAnswer(i -> shTable.values().stream()
                .filter(x -> x.getMktOrderId().equals(i.getArgument(0))).sorted(java.util.Comparator.comparing(
                        com.myplus.marketplace.multiseller.entity.MarketplaceShortage::getId)).toList());
        lenient().when(shortageRows.findByMktOrderIdIn(any())).thenAnswer(i -> shTable.values().stream()
                .filter(x -> ((java.util.Collection<?>) i.getArgument(0)).contains(x.getMktOrderId())).toList());
        lenient().when(shortageRows.findBySellerOrderIdIn(any())).thenAnswer(i -> shTable.values().stream()
                .filter(x -> ((java.util.Collection<?>) i.getArgument(0)).contains(x.getSellerOrderId())).toList());
        lenient().when(shortageRows.findByResultAndProposalExpiresAtBefore(anyString(), any(), any())).thenAnswer(i -> shTable.values()
                .stream().filter(x -> x.getResult().equals(i.getArgument(0)) && x.getProposalExpiresAt() != null
                        && x.getProposalExpiresAt().isBefore(i.getArgument(1))).toList());
        lenient().when(shortageRows.findByResultAndCreatedAtBefore(anyString(), any(), any())).thenAnswer(i -> shTable.values()
                .stream().filter(x -> x.getResult().equals(i.getArgument(0)) && x.getCreatedAt().isBefore(i.getArgument(1))).toList());
        lenient().when(shortageRows.findByProposalHeldTrueAndResultNot(anyString(), any())).thenAnswer(i -> shTable.values()
                .stream().filter(x -> Boolean.TRUE.equals(x.getProposalHeld()) && !x.getResult().equals(i.getArgument(0))).toList());
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
    @DisplayName("[MKT-R18.5] an inventory outage is 'not held', never 'held', and the shopper is told the seller did not answer")
    void holdOutage() {
        when(trade.holdStock(any())).thenThrow(new RuntimeException("connect timed out"));
        assertThatThrownBy(() -> checkout.checkout(req("o"))).hasMessage("This seller did not answer in time. Please choose another offer.");
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
        MarketplaceAccountService accounts = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit, shortages);
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
        MarketplaceAccountService accounts = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit, shortages);
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
        // MKT-2c: the sellers are asked at the same time, so the calls arrive in either order
        assertThat(holds.getAllValues()).extracting(StockHoldRequest::getOrganizationId).containsExactlyInAnyOrder(SELLER, OTHER_SELLER);
        StockHoldRequest second = holds.getAllValues().stream().filter(h -> h.getOrganizationId() == OTHER_SELLER).findFirst().orElseThrow();
        assertThat(second.getLines()).hasSize(1);
        assertThat(second.getLines().get(0).getItemId()).isEqualTo(SOURCE_B);
        assertThat(second.getLines().get(0).getQuantity()).isEqualByComparingTo("2");
        assertThat(holds.getAllValues().get(0).getHoldKey()).isNotEqualTo(holds.getAllValues().get(1).getHoldKey());
        assertThat(lineTable).extracting(MarketplaceOrderLine::getSellerOrganizationId).containsExactly(SELLER, OTHER_SELLER);
    }

    @Test
    @DisplayName("[MKT-R17.2] all or nothing: one seller cannot hold → every part cancelled, every hold released, that seller named")
    void oneRefusalCancelsAll() {
        secondSeller(true);
        when(trade.holdStock(any())).thenAnswer(i -> held(i.<StockHoldRequest>getArgument(0).getOrganizationId() != OTHER_SELLER));
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
        MarketplaceAccountService acc = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit, shortages);
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

    // ── MKT-2c: live routing — a seller's stock is asked for with a deadline ─────────────────────────────────

    /** {@code slowSeller}'s holds answer (held) only after {@code ms}; everyone else's at once. */
    void slow(Long slowSeller, long ms) {
        when(trade.holdStock(any())).thenAnswer(i -> {
            if (slowSeller.equals(i.<StockHoldRequest>getArgument(0).getOrganizationId())) Thread.sleep(ms);
            return held(true);
        });
    }

    @Test
    @DisplayName("[MKT-R18.3] [MKT-R18.5] a seller that does not answer within its timeout: the shopper is told at once, the order CANCELLED, and the hold that lands late is released")
    void slowSellerRefusedInTime() {
        slow(SELLER, 1500);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> checkout.checkout(req("r1"))).hasMessage("This seller did not answer in time. Please choose another offer.");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("the 1 s timeout, not the seller's 1.5 s").isLessThan(1400);
        MarketplaceSellerOrder so = soTable.values().iterator().next();
        assertThat(so.getAcceptanceStatus()).isEqualTo("CANCELLED");
        assertThat(orderTable.values().iterator().next().getStatus()).isEqualTo("CANCELLED");
        // once by the checkout itself, once more when the late hold lands
        verify(trade, org.mockito.Mockito.timeout(3000).times(2)).releaseHold(so.getHoldKey());
    }

    @Test
    @DisplayName("[MKT-R18.1] [MKT-R18.3] a basket from two sellers waits for the slower one, never for the sum")
    void sellersAskedAtOnce() {
        secondSeller(true);
        when(trade.holdStock(any())).thenAnswer(i -> {
            Thread.sleep(600);
            return held(true);
        });
        long t0 = System.nanoTime();
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(basket("r2", null, lineA(1), lineB(1)));
        assertThat((System.nanoTime() - t0) / 1_000_000).as("two 600 ms sellers in parallel").isLessThan(1100);
        assertThat(v.sellerOrders()).extracting(MarketplaceOrderDTOs.PartView::status).containsOnly("OFFERED");
    }

    @Test
    @DisplayName("[MKT-R18.5] [MKT-R17.2] one slow seller in a basket: the whole basket refused, that seller named, every hold released")
    void slowSellerInBasketNamed() {
        secondSeller(true);
        slow(OTHER_SELLER, 1500);
        assertThatThrownBy(() -> checkout.checkout(basket("r3", null, lineA(1), lineB(1))))
                .hasMessage("Mobile Distributor did not answer in time. Please remove its items and place the order again.");
        assertThat(soTable.values()).extracting(MarketplaceSellerOrder::getAcceptanceStatus).containsOnly("CANCELLED");
        verify(trade).releaseHold(part(SELLER).getHoldKey());
        verify(trade, org.mockito.Mockito.timeout(3000).times(2)).releaseHold(part(OTHER_SELLER).getHoldKey());
    }

    @Test
    @DisplayName("[MKT-R18.3] after 3 calls in a row it did not answer, a seller is not asked again for a while: refused at once, without a call; the operator can ask it again")
    void circuitOpens() {
        slow(SELLER, 1200);
        for (int i = 0; i < 3; i++) assertThatThrownBy(() -> checkout.checkout(req("c" + System.nanoTime()))).hasMessageContaining("did not answer in time");
        verify(trade, org.mockito.Mockito.times(3)).holdStock(any());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> checkout.checkout(req("c4"))).hasMessage("This seller did not answer in time. Please choose another offer.");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("refused without waiting").isLessThan(300);
        verify(trade, org.mockito.Mockito.times(3)).holdStock(any());
        assertThat(routing.openCircuits()).singleElement().satisfies(c -> {
            assertThat(c.sellerOrganizationId()).isEqualTo(SELLER);
            assertThat(c.failures()).isEqualTo(3);
        });
        org.mockito.Mockito.doReturn(held(true)).when(trade).holdStock(any());
        assertThat(routing.close(SELLER)).isTrue();
        assertThat(checkout.checkout(req("c5")).status()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("[MKT-R11.1] [MKT-R18.3] a reroute passes over a candidate seller that does not answer in time, and releases what it held late")
    void slowCandidatePassedOver() {
        rerouteTo("51500", 4);
        checkout.checkout(req("r5"));
        org.mockito.Mockito.reset(trade);
        slow(OTHER_SELLER, 1500);
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        assertThat(sh.getResult()).as("no other seller answered in time: the part ends").isIn("LINE_CANCELLED", "ORDER_CANCELLED");
        assertThat(soTable).hasSize(1);
        ArgumentCaptor<String> released = ArgumentCaptor.forClass(String.class);
        verify(trade, org.mockito.Mockito.timeout(3000).atLeast(2)).releaseHold(released.capture());
        assertThat(released.getAllValues()).as("the candidate's late hold, under its own key").anyMatch(k -> !k.equals(a.getHoldKey()));
    }

    // ── MKT-2b: a part its seller did not fulfil — recorded, moved, offered to the shopper, or cancelled ──────────

    static final String PHONE = "0300-123 4567";

    /** The reroute switch on, and OTHER_SELLER selling the same product at {@code price} within {@code promise} hours. */
    MarketplaceOfferProjection rerouteTo(String price, int promise) {
        secondSeller(false);
        com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting on = new com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting();
        on.setSettingKey("shortage.reroute");
        on.setSettingValue("true");
        lenient().when(settingRows.findById("shortage.reroute")).thenReturn(Optional.of(on));
        MarketplaceOfferProjection rb = projections.findById(OFFER_B).orElseThrow();
        rb.setPrice(new BigDecimal(price));
        rb.setPromiseHours(promise);
        offers.findById(OFFER_B).orElseThrow().setPromiseHours(promise);
        lenient().when(projections.findByMktProductIdAndStatusOrderByPriceAsc(PRODUCT, MarketplaceOfferProjection.LIVE))
                .thenAnswer(i -> List.of(row, rb));
        return rb;
    }

    com.myplus.marketplace.multiseller.entity.MarketplaceShortage shortageOf(MarketplaceSellerOrder so) {
        return shTable.values().stream().filter(x -> x.getSellerOrderId().equals(so.getId())).findFirst().orElseThrow();
    }

    MarketplaceSellerOrder rejectFirst(String cause) {
        MarketplaceSellerOrder a = part(SELLER);
        sellerSide.reject(a.getId(), new MarketplaceOrderDTOs.RejectRequest(a.getVersion(), "none left", cause));
        return a;
    }

    @Test
    @DisplayName("[MKT-R11.4] [MKT-R12.4] with the switch off, a rejection is recorded with its cause and party and the order ends as before; nothing is debited")
    void switchOffRecordsCauseOnly() {
        checkout.checkout(req("s0"));
        MarketplaceSellerOrder a = rejectFirst("SUPPLIER_STALE_STOCK");
        var sh = shortageOf(a);
        assertThat(sh.getCause()).isEqualTo("SUPPLIER_STALE_STOCK");
        assertThat(sh.getResponsibleRole()).isEqualTo("SUPPLIER");
        assertThat(sh.getStatus()).isEqualTo("RECORDED");
        assertThat(sh.getResult()).isEqualTo("ORDER_CANCELLED");
        assertThat(sh.getEvidence()).isEqualTo("none left");
        assertThat(orderTable.get(a.getMktOrderId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(soTable).hasSize(1);
        assertThatThrownBy(() -> MarketplaceShortageService.sellerCause("NO_RESPONSE"))
                .as("the clock's cause is never the seller's to pick").isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("[MKT-R11.1] [MKT-R11.3] same product, cheaper and no later: moved to the other seller without asking; a card gets the difference back")
    void silentReassign() {
        rerouteTo("51500", 4);
        when(payments.charge(any(), eq("tok"))).thenReturn(MarketplacePaymentService.Outcome.SUCCEEDED);
        checkout.checkout(basket("s1", "CARD", lineA(1)), 77L);
        MarketplaceSellerOrder a = rejectFirst(null);
        MarketplaceSellerOrder b = part(OTHER_SELLER);
        MarketplaceOrder parent = orderTable.get(a.getMktOrderId());
        assertThat(b.getAcceptanceStatus()).isEqualTo("OFFERED");
        assertThat(b.getReplacesSellerOrderId()).isEqualTo(a.getId());
        assertThat(b.getHeld()).isTrue();
        assertThat(a.getShortagePending()).isFalse();
        assertThat(parent.getStatus()).as("the order goes on with the new seller").isEqualTo("SUBMITTED");
        assertThat(parent.getTotal()).isEqualByComparingTo("51500");
        var sh = shortageOf(a);
        assertThat(sh.getResult()).isEqualTo("REASSIGNED");
        assertThat(sh.getReplacementSellerOrderId()).isEqualTo(b.getId());
        verify(payments).refundPart(eq(parent.getId()), eq(a.getId()), org.mockito.ArgumentMatchers.argThat(x -> x.compareTo(new BigDecimal("500")) == 0), anyString());
        verify(trade).releaseHold(a.getHoldKey());
        MarketplaceOrderDTOs.PartView old = checkout.view(parent).sellerOrders().stream().filter(p -> p.id().equals(a.getId())).findFirst().orElseThrow();
        assertThat(old.shortage().movedTo()).isEqualTo("Mobile Distributor");
        assertThat(old.shortage().priceDifference()).isEqualByComparingTo("-500");
    }

    @Test
    @DisplayName("[MKT-R11.2] a later promise needs the shopper: the alternative is held and offered; accepting makes it the order's new part")
    void proposalAccepted() {
        rerouteTo("51500", 24);
        String no = checkout.checkout(req("s2")).orderNo();
        MarketplaceSellerOrder a = rejectFirst("MERCHANT_STALE_STOCK");
        var sh = shortageOf(a);
        assertThat(sh.getResult()).isEqualTo("SUBSTITUTION_REQUESTED");
        assertThat(sh.getProposalHeld()).isTrue();
        assertThat(sh.getProposalTotal()).isEqualByComparingTo("51500");
        assertThat(soTable).as("no part until the shopper says yes").hasSize(1);
        MarketplaceOrder parent = orderTable.get(a.getMktOrderId());
        assertThat(parent.getStatus()).as("the order waits for the answer").isEqualTo("SUBMITTED");
        MarketplaceOrderDTOs.ShortageView v = checkout.view(parent).sellerOrders().get(0).shortage();
        assertThat(v.proposalSeller()).isEqualTo("Mobile Distributor");
        assertThat(v.proposalPromiseHours()).isEqualTo(24);
        assertThat(v.secondsToDecide()).isBetween(1790L, 1800L);

        assertThatThrownBy(() -> shortages.decide(no, sh.getId(), new MarketplaceOrderDTOs.ShortageDecision("0300 000 0000", true)))
                .as("another phone reads as no order").hasMessage("No such order.");
        shortages.decide(no, sh.getId(), new MarketplaceOrderDTOs.ShortageDecision(PHONE, true));
        MarketplaceSellerOrder b = part(OTHER_SELLER);
        assertThat(b.getAcceptanceStatus()).isEqualTo("OFFERED");
        assertThat(b.getHoldKey()).as("the proposal's hold becomes the part's").isEqualTo(sh.getProposalHoldKey());
        assertThat(sh.getCustomerDecision()).isEqualTo("ACCEPTED");
        assertThat(sh.getResult()).isEqualTo("REASSIGNED");
        assertThat(sh.getProposalHeld()).isFalse();
        assertThat(parent.getTotal()).isEqualByComparingTo("51500");
        assertThat(shortages.decide(no, sh.getId(), new MarketplaceOrderDTOs.ShortageDecision(PHONE, true)).status())
                .as("a repeated yes changes nothing").isEqualTo("SUBMITTED");
        assertThat(soTable).hasSize(2);
        assertThatThrownBy(() -> shortages.decide(no, sh.getId(), new MarketplaceOrderDTOs.ShortageDecision(PHONE, false)))
                .hasMessage("This alternative is no longer open.");
    }

    @Test
    @DisplayName("[MKT-R11.2] declining the alternative ends the part: its hold is released and the order follows")
    void proposalDeclined() {
        rerouteTo("51500", 24);
        String no = checkout.checkout(req("s3")).orderNo();
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        shortages.decide(no, sh.getId(), new MarketplaceOrderDTOs.ShortageDecision(PHONE, false));
        assertThat(sh.getCustomerDecision()).isEqualTo("DECLINED");
        assertThat(sh.getResult()).isEqualTo("ORDER_CANCELLED");
        assertThat(orderTable.get(a.getMktOrderId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(orderTable.get(a.getMktOrderId()).getCancelReason()).isEqualTo(MarketplaceShortageService.DECLINED_FOR_SHOPPER);
        verify(trade).releaseHold(sh.getProposalHoldKey());
        assertThat(sh.getProposalHeld()).isFalse();
        verify(payments).refundIfCancelled(a.getMktOrderId());
    }

    @Test
    @DisplayName("[MKT-R11.2] an unanswered alternative expires on the clock: the part ends and the hold goes back")
    void proposalExpires() {
        rerouteTo("51500", 24);
        checkout.checkout(req("s4"));
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        sh.setProposalExpiresAt(LocalDateTime.now().minusSeconds(1));
        sweeper.sweep();
        assertThat(sh.getCustomerDecision()).isEqualTo("EXPIRED");
        assertThat(sh.getResult()).isEqualTo("ORDER_CANCELLED");
        assertThat(orderTable.get(a.getMktOrderId()).getCancelReason()).isEqualTo(MarketplaceShortageService.EXPIRED_FOR_SHOPPER);
        verify(trade).releaseHold(sh.getProposalHoldKey());
    }

    @Test
    @DisplayName("[MKT-R11.2] a card order is never asked to pay more: a dearer alternative is not offered, the part ends and is refunded")
    void cardNeverAskedMore() {
        rerouteTo("53000", 4);
        when(payments.charge(any(), eq("tok"))).thenReturn(MarketplacePaymentService.Outcome.SUCCEEDED);
        checkout.checkout(basket("s5", "CARD", lineA(1)), 77L);
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        assertThat(sh.getResult()).isEqualTo("ORDER_CANCELLED");
        assertThat(sh.getProposalSellerOrgId()).isNull();
        assertThat(soTable).hasSize(1);
        verify(payments).refundIfCancelled(a.getMktOrderId());
    }

    @Test
    @DisplayName("[MKT-R11.1] a cash order may be offered a dearer alternative; the difference is shown, never charged")
    void cashMayBeAskedMore() {
        rerouteTo("53000", 4);
        checkout.checkout(req("s6"));
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        assertThat(sh.getResult()).isEqualTo("SUBSTITUTION_REQUESTED");
        assertThat(checkout.view(orderTable.get(a.getMktOrderId())).sellerOrders().get(0).shortage().priceDifference())
                .isEqualByComparingTo("1000");
        verify(payments, never()).charge(any(), any());
    }

    @Test
    @DisplayName("[MKT-R11.1] a seller already in the order is never a candidate: with none left, only that line ends and the rest goes on")
    void noCandidateEndsTheLine() {
        rerouteTo("51500", 4);
        com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting multi = new com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting();
        multi.setSettingValue("true");
        lenient().when(settingRows.findById("checkout.multiSeller")).thenReturn(Optional.of(multi));
        checkout.checkout(basket("s7", null, lineA(1), lineB(1)));
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        assertThat(sh.getResult()).isEqualTo("LINE_CANCELLED");
        assertThat(orderTable.get(a.getMktOrderId()).getStatus()).isEqualTo("SUBMITTED");
        assertThat(soTable).hasSize(2);
        verify(payments, never()).refundIfCancelled(anyLong());
    }

    @Test
    @DisplayName("[MKT-R10.5] the shopper cancels while an alternative waits for them: the record ends with the order and its hold goes back")
    void cancelDuringProposal() {
        rerouteTo("51500", 24);
        MarketplaceAccountService acc = new MarketplaceAccountService(orders, sellerOrders, lines, checkout, sellerSide, payments, txManager, audit, shortages);
        com.myplus.marketplace.multiseller.entity.MarketplaceCustomer me = new com.myplus.marketplace.multiseller.entity.MarketplaceCustomer();
        me.setId(77L);
        String no = checkout.checkout(req("s8"), 77L).orderNo();
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        assertThat(acc.view(orderTable.get(a.getMktOrderId())).canCancel()).as("still the shopper's to cancel").isTrue();
        assertThat(acc.cancel(me, no, null).status()).isEqualTo("CANCELLED");
        assertThat(sh.getCustomerDecision()).isEqualTo("CANCELLED");
        assertThat(sh.getResult()).isEqualTo("ORDER_CANCELLED");
        verify(trade).releaseHold(sh.getProposalHoldKey());
        assertThatThrownBy(() -> shortages.decide(no, sh.getId(), new MarketplaceOrderDTOs.ShortageDecision(PHONE, true)))
                .hasMessage("This alternative is no longer open.");
    }

    @Test
    @DisplayName("[MKT-R11.1] a part not accepted in time is recorded against the seller (NO_RESPONSE) and moved like a rejection")
    void expiryReroutes() {
        rerouteTo("51500", 4);
        checkout.checkout(req("s9"));
        MarketplaceSellerOrder a = part(SELLER);
        a.setAcceptBy(LocalDateTime.now().minus(MarketplaceOrderSweeper.GRACE).minusSeconds(1));
        sweeper.sweep();
        var sh = shortageOf(a);
        assertThat(a.getAcceptanceStatus()).isEqualTo("EXPIRED");
        assertThat(sh.getCause()).isEqualTo("NO_RESPONSE");
        assertThat(sh.getResponsibleRole()).isEqualTo("MERCHANT");
        assertThat(sh.getResult()).isEqualTo("REASSIGNED");
        assertThat(part(OTHER_SELLER).getAcceptanceStatus()).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("[MKT-R12.4] the seller disputes the cause and the operator decides; neither step moves any money")
    void disputeAndRuleMoveNoMoney() {
        checkout.checkout(req("s10"));
        MarketplaceSellerOrder a = rejectFirst(null);
        var sh = shortageOf(a);
        org.mockito.Mockito.clearInvocations(payments);
        when(access.org()).thenReturn(OTHER_SELLER);
        assertThatThrownBy(() -> shortages.dispute(sh.getId(), new MarketplaceOrderDTOs.DisputeRequest("not mine")))
                .as("another seller's record reads as missing").hasMessage("No such record.");
        when(access.org()).thenReturn(SELLER);
        assertThatThrownBy(() -> shortages.dispute(sh.getId(), new MarketplaceOrderDTOs.DisputeRequest(" "))).hasMessageContaining("Say why");
        assertThat(shortages.dispute(sh.getId(), new MarketplaceOrderDTOs.DisputeRequest("The listing showed 3 in stock")).status())
                .isEqualTo("DISPUTED");
        assertThatThrownBy(() -> shortages.dispute(sh.getId(), new MarketplaceOrderDTOs.DisputeRequest("again")))
                .hasMessageContaining("already disputed");
        assertThatThrownBy(() -> shortages.rule(sh.getId(), new MarketplaceOrderDTOs.ShortageRuling("OVERTURNED", "")))
                .hasMessage("Write the reason for the seller.");
        assertThat(shortages.rule(sh.getId(), new MarketplaceOrderDTOs.ShortageRuling("overturned", "Sync was late")).status())
                .isEqualTo("OVERTURNED");
        assertThatThrownBy(() -> shortages.rule(sh.getId(), new MarketplaceOrderDTOs.ShortageRuling("UPHELD", "x")))
                .hasMessage("Only a disputed record is decided.");
        org.mockito.Mockito.verifyNoInteractions(payments);
        doThrow(new org.springframework.security.access.AccessDeniedException("operators only")).when(access).assertOperator();
        assertThatThrownBy(() -> shortages.operatorList(null, 0, 10)).hasMessage("operators only");
    }
}
