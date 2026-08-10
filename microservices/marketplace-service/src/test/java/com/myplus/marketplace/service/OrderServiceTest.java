package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnRequest;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.repository.OrderEventRepository;
import com.myplus.marketplace.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * E1 (slice 46) — order fulfilment: record (status NEW), list org-scoped, advance status. Real MySQL; skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    private static final Long ORG = 1L, USER = 1L;

    @Autowired private OrderService service;
    @Autowired private OrderRepository repo;
    @Autowired private OrderEventRepository orderEventRepository;   // timeline (slice 57)
    @MockitoBean private InventoryClient inventoryClient;   // only the legacy (pre-O1) cancel path uses this now
    /** OMS O1: the storefront no longer reserves stock itself — it records a SALE, and business-service reserves,
     *  invoices and confirms inside that one call. So the seam under test is TradeClient, not InventoryClient. */
    @MockitoBean private com.myplus.commerce.contracts.client.TradeClient tradeClient;

    @BeforeEach
    void clean() {
        orderEventRepository.deleteAll();
        repo.deleteAll();
        // Default: the sale records fine and comes back with the SERVER's invoice number and total.
        when(tradeClient.recordSale(any(com.myplus.commerce.contracts.dto.SaleRecordRequest.class)))
                .thenReturn(com.myplus.commerce.contracts.dto.SaleRecordResult.builder()
                        .invoiceNo("INV-SF-1").grandTotal(new BigDecimal("20.00")).status("RECORDED").build());
    }

    private OrderDTO.Line line(Long productId, int qty) {
        OrderDTO.Line l = new OrderDTO.Line();
        l.setProductId(productId); l.setQuantity(qty); l.setPrice(new BigDecimal("20.00"));
        return l;
    }

    private OrderDTO sample(String invoice) {
        OrderDTO d = new OrderDTO();
        d.setInvoiceNo(invoice);
        d.setCustomerName("Buyer");
        d.setTotal(new BigDecimal("99.00"));
        return d;
    }

    private OrderDTO storefront(String name, String mode, String token) {
        OrderDTO d = new OrderDTO();
        d.setOrganizationId(ORG);
        d.setCustomerName(name);
        d.setTotal(new BigDecimal("20.00"));
        d.setPaymentMode(mode);
        d.setCardToken(token);
        d.setItems(List.of(line(100L, 1)));   // a cart line so the stock reservation runs
        return d;
    }

    @Test
    void public_card_payment_records_a_SALE_and_marks_the_order_paid() {
        OrderDTO o = service.placePublic(storefront("Card Buyer", "CARD", "ok"));
        assertThat(o.getSource()).isEqualTo("STOREFRONT");
        assertThat(o.getPaymentMode()).isEqualTo("CARD");
        assertThat(o.getPaymentStatus()).isEqualTo("PAID");
        assertThat(o.getPaymentRef()).startsWith("ch_sandbox_");
        // O1: the order now carries the trade sale it produced, and is marked as having reached the books.
        assertThat(o.getInvoiceNo()).isEqualTo("INV-SF-1");
        assertThat(repo.findById(o.getId()).orElseThrow().getBooksStatus()).isEqualTo("POSTED");
        verify(tradeClient, times(1)).recordSale(any());
        // Marketplace no longer runs its own reservation saga — business-service reserves inside the sale.
        verify(inventoryClient, never()).reserve(any());
    }

    @Test
    void public_card_decline_VOIDS_the_sale_and_blocks_the_order() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.placePublic(storefront("Declined", "CARD", "fail")));
        assertThat(repo.findScoped(ORG, USER)).isEmpty();   // no order created
        // The sale is recorded BEFORE the charge (so the server's total is charged), therefore a decline must
        // reverse it — otherwise the books would carry revenue for an order the shopper never paid for.
        verify(tradeClient, times(1)).reverseSale(eq("INV-SF-1"), anyString());
    }

    @Test
    void public_cod_order_is_pending_and_still_reaches_the_books() {
        OrderDTO o = service.placePublic(storefront("COD Buyer", "COD", null));
        assertThat(o.getPaymentMode()).isEqualTo("COD");
        assertThat(o.getPaymentStatus()).isEqualTo("PENDING");
        // Unpaid does NOT mean unbooked: a COD order is a receivable, exactly like an unpaid counter sale.
        assertThat(o.getInvoiceNo()).isEqualTo("INV-SF-1");
        verify(tradeClient, times(1)).recordSale(any());
    }

    @Test
    void the_sale_request_carries_no_client_total() {
        // OMS-5: the client's total must not be trusted. It is not merely ignored — the contract has no field
        // for it, and the order stores the SERVER's figure returned by the sale.
        OrderDTO d = storefront("Total Liar", "CARD", "ok");
        d.setTotal(new BigDecimal("0.01"));           // a shopper claiming the order costs one paisa
        OrderDTO o = service.placePublic(d);
        assertThat(o.getTotal()).isEqualByComparingTo("20.00");   // the server's total, not the client's
    }

    @Test
    void out_of_stock_blocks_the_order_and_never_charges() {
        // The sale refuses (business-service could not reserve), so nothing is invoiced AND nothing is charged —
        // the charge only happens after a sale exists.
        when(tradeClient.recordSale(any(com.myplus.commerce.contracts.dto.SaleRecordRequest.class)))
                .thenThrow(new RuntimeException("OUT_OF_STOCK"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.placePublic(storefront("NoStock", "CARD", "ok")));
        assertThat(repo.findScoped(ORG, USER)).isEmpty();          // no order
        verify(tradeClient, never()).reverseSale(anyString(), anyString());   // nothing to reverse
    }

    @Test
    void cancelling_a_storefront_order_VOIDS_its_invoice() {
        OrderDTO o = service.placePublic(storefront("Cancel Me", "COD", null));
        OrderDTO cancelled = service.updateStatus(o.getId(), "CANCELLED", ORG, USER);
        assertThat(cancelled.getFulfilmentStatus()).isEqualTo("CANCELLED");
        // O1: returning stock alone would leave the revenue booked. The void restores stock AND reverses the
        // books in one operation, so P&L and the tax register stay right.
        verify(tradeClient, times(1)).reverseSale(eq("INV-SF-1"), anyString());
        verify(inventoryClient, never()).returnStock(anyString(), any(StockReturnRequest.class));
    }

    @Test
    void re_cancelling_does_not_reverse_twice() {
        OrderDTO o = service.placePublic(storefront("Twice", "COD", null));
        service.updateStatus(o.getId(), "CANCELLED", ORG, USER);
        service.updateStatus(o.getId(), "CANCELLED", ORG, USER);   // idempotent — already cancelled
        verify(tradeClient, times(1)).reverseSale(eq("INV-SF-1"), anyString());
    }

    @Test
    void cancelling_a_legacy_order_with_no_invoice_still_returns_stock() {
        // Pre-O1 orders (books_status=LEGACY_UNPOSTED) have no sale to reverse, so inventory is the only thing
        // to put back. They must keep working — they are real orders in a live shop.
        Order legacy = repo.save(Order.builder()
                .organizationId(ORG).source("STOREFRONT")
                .reservationId("resv-legacy").booksStatus("LEGACY_UNPOSTED")
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .items(List.of(com.myplus.marketplace.entity.OrderItem.builder()
                        .productId(100L).quantity(1).build()))
                .build());

        service.updateStatus(legacy.getId(), "CANCELLED", ORG, USER);

        verify(inventoryClient, times(1)).returnStock(eq("resv-legacy"), any(StockReturnRequest.class));
        verify(tradeClient, never()).reverseSale(anyString(), anyString());
    }

    @Test
    void cancelling_a_pos_order_does_not_touch_inventory() {
        OrderDTO created = service.record(sample("INV-X"), ORG, USER);   // POS order, no reservation
        service.updateStatus(created.getId(), "CANCELLED", ORG, USER);
        verify(inventoryClient, never()).returnStock(anyString(), any(StockReturnRequest.class));
    }

    @Test
    void order_lifecycle_records_a_notification_timeline() {
        OrderDTO o = service.placePublic(storefront("Timeline", "COD", null));
        assertThat(orderEventRepository.findByOrderIdOrderByCreatedAtAsc(o.getId()))
                .extracting(e -> e.getStatus()).containsExactly("NEW");

        service.updateStatus(o.getId(), "PACKED", ORG, USER);
        assertThat(orderEventRepository.findByOrderIdOrderByCreatedAtAsc(o.getId()))
                .extracting(e -> e.getStatus()).containsExactly("NEW", "PACKED");
    }

    @Test
    void full_refund_marks_a_card_order_refunded() {
        OrderDTO o = service.placePublic(storefront("Refund Me", "CARD", "ok"));   // total 20.00, PAID
        OrderDTO r = service.refund(o.getId(), null, ORG, USER);                   // null amount → full
        assertThat(r.getPaymentStatus()).isEqualTo("REFUNDED");
        assertThat(r.getRefundedAmount()).isEqualByComparingTo(o.getTotal());
        assertThat(r.getRefundRef()).startsWith("re_sandbox_");
    }

    @Test
    void partial_refund_then_remainder_caps_at_total() {
        OrderDTO o = service.placePublic(storefront("Partial", "CARD", "ok"));     // total 20.00
        OrderDTO r1 = service.refund(o.getId(), new BigDecimal("5.00"), ORG, USER);
        assertThat(r1.getPaymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(r1.getRefundedAmount()).isEqualByComparingTo("5.00");
        OrderDTO r2 = service.refund(o.getId(), new BigDecimal("999.00"), ORG, USER);   // over-refund → capped
        assertThat(r2.getPaymentStatus()).isEqualTo("REFUNDED");
        assertThat(r2.getRefundedAmount()).isEqualByComparingTo(o.getTotal());
    }

    @Test
    void cod_order_cannot_be_refunded() {
        OrderDTO o = service.placePublic(storefront("COD NoRefund", "COD", null));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.refund(o.getId(), null, ORG, USER));
    }

    @Test
    void return_request_on_a_delivered_order_sets_return_requested() {
        OrderDTO d = storefront("Returner", "COD", null);
        d.setCustomerContact("0300RET");
        OrderDTO o = service.placePublic(d);
        service.updateStatus(o.getId(), "DELIVERED", ORG, USER);

        var t = service.requestReturn(o.getId(), "0300RET", "too big");
        assertThat(t.getStatus()).isEqualTo("RETURN_REQUESTED");
    }

    @Test
    void return_request_on_a_non_delivered_order_is_rejected() {
        OrderDTO d = storefront("Early", "COD", null);
        d.setCustomerContact("0300ERL");
        OrderDTO o = service.placePublic(d);   // NEW, not delivered
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.requestReturn(o.getId(), "0300ERL", "nope"));
    }

    @Test
    void processing_a_return_returns_stock_and_refunds_a_card_order() {
        OrderDTO d = storefront("RMA Buyer", "CARD", "ok");   // total 20.00, PAID, reservation resv-1
        d.setCustomerContact("0300RMA");
        OrderDTO o = service.placePublic(d);
        service.updateStatus(o.getId(), "DELIVERED", ORG, USER);

        OrderDTO r = service.processReturn(o.getId(), ORG, USER);

        assertThat(r.getFulfilmentStatus()).isEqualTo("RETURNED");
        assertThat(r.getPaymentStatus()).isEqualTo("REFUNDED");
        assertThat(r.getRefundedAmount()).isEqualByComparingTo(o.getTotal());
        verify(inventoryClient, times(1)).returnStock(eq("resv-1"), any(StockReturnRequest.class));  // G2 stock back
    }

    @Test
    void record_then_advance_status_and_list_scoped() {
        OrderDTO created = service.record(sample("INV-1"), ORG, USER);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getFulfilmentStatus()).isEqualTo("NEW");

        OrderDTO shipped = service.updateStatus(created.getId(), "SHIPPED", ORG, USER);
        assertThat(shipped.getFulfilmentStatus()).isEqualTo("SHIPPED");

        // Tenant isolation, asserted through the PAGED read since `list()` was deleted in the 2026-08-10
        // review (it was the unbounded read OMS-7 named, left public with no callers). The assertion is the
        // one that matters and is unchanged: another tenant sees nothing.
        assertThat(service.page(com.myplus.marketplace.dto.OrderQuery.firstPage(), ORG, USER).getContent())
                .hasSize(1);
        assertThat(service.page(com.myplus.marketplace.dto.OrderQuery.firstPage(), 999L, 999L).getContent())
                .isEmpty();
    }
}
