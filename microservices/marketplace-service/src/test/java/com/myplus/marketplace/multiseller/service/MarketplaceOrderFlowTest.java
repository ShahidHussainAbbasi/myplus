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
    @Mock TradeClient trade;
    @Mock CatalogClient catalog;
    @Mock DocumentNumberService numbers;
    @Mock SellerAccess access;
    @Mock MarketplaceSellerService sellers;
    @Mock OrderService storeOrders;
    @Mock PlatformTransactionManager txManager;

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
        MarketplaceSettingsService settings = new MarketplaceSettingsService(settingRows, access);
        PublicOfferService publicOffers = new PublicOfferService(projections, products, settings);
        checkout = new MarketplaceCheckoutService(offers, projections, products, policies, accounts, orders, sellerOrders,
                lines, publicOffers, settings, shipping, trade, numbers, access, txManager);
        sellerSide = new SellerOrderService(sellerOrders, orders, lines, sellers, checkout, storeOrders, catalog, trade,
                access, txManager);
        sweeper = new MarketplaceOrderSweeper(sellerOrders, orders, sellerSide, txManager);

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
        MarketplaceSettingsService s = new MarketplaceSettingsService(settingRows, access);
        assertThat(s.acceptMinutes()).isEqualTo(5);
        assertThatThrownBy(() -> s.setAcceptMinutes(0)).hasMessageContaining("1 to 60");
        assertThatThrownBy(() -> s.setAcceptMinutes(61)).hasMessageContaining("1 to 60");
        assertThat(s.setAcceptMinutes(1)).isEqualTo(1);
    }
}
