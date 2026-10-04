package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplacePayment;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePaymentRepository;
import com.myplus.marketplace.service.PaymentGateway;

/** MKT-1e2 — money as facts: never two charges for one order, never two refunds for one charge. */
@ExtendWith(MockitoExtension.class)
class MarketplacePaymentServiceTest {

    @Mock MarketplacePaymentRepository payments;
    @Mock MarketplaceOrderRepository orders;
    @Mock PaymentGateway gateway;
    @Mock PlatformTransactionManager txManager;
    MarketplacePaymentService svc;

    final Map<Long, MarketplacePayment> table = new HashMap<>();
    final AtomicLong ids = new AtomicLong();
    MarketplaceOrder order;

    @BeforeEach
    void wire() {
        svc = new MarketplacePaymentService(payments, orders, gateway, txManager);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(gateway.name()).thenReturn("sandbox");
        lenient().when(payments.saveAndFlush(any())).thenAnswer(i -> put(i.getArgument(0)));
        lenient().when(payments.save(any())).thenAnswer(i -> put(i.getArgument(0)));
        lenient().when(payments.findById(any())).thenAnswer(i -> Optional.ofNullable(table.get((Long) i.getArgument(0))));
        lenient().when(payments.findByIdempotencyKey(anyString())).thenAnswer(i -> table.values().stream()
                .filter(p -> p.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        order = new MarketplaceOrder();
        order.setId(7L);
        order.setOrderNo("MKT-000007");
        order.setIdempotencyKey("k-7");
        order.setStatus("SUBMITTED");
        order.setPaymentStatus("UNPAID");
        order.setTotal(new BigDecimal("52000.00"));
        lenient().when(orders.findById(7L)).thenReturn(Optional.of(order));
    }

    private MarketplacePayment put(MarketplacePayment p) {
        if (p.getId() == null) p.setId(ids.incrementAndGet());
        table.put(p.getId(), p);
        return p;
    }

    @Test
    @DisplayName("[MKT-R19.1] a card that pays is CAPTURED; the fact is written BEFORE the provider is called")
    void captured() {
        when(gateway.charge("tok", order.getTotal(), "charge:k-7")).thenAnswer(i -> {
            assertThat(table.values()).as("PENDING fact first").anySatisfy(p -> assertThat(p.getStatus()).isEqualTo("PENDING"));
            return new PaymentGateway.Charge(true, "ch_1", null);
        });
        assertThat(svc.charge(order, "tok")).isEqualTo(MarketplacePaymentService.Outcome.SUCCEEDED);
        assertThat(order.getPaymentStatus()).isEqualTo("CAPTURED");
    }

    @Test
    @DisplayName("[MKT-R22.3] a retried charge finds the first one: the provider is called once")
    void chargedOnce() {
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(new PaymentGateway.Charge(true, "ch_1", null));
        svc.charge(order, "tok");
        assertThat(svc.charge(order, "tok")).isEqualTo(MarketplacePaymentService.Outcome.SUCCEEDED);
        verify(gateway, times(1)).charge(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("[MKT-R19.1] a declined card is FAILED")
    void declined() {
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(new PaymentGateway.Charge(false, null, "Card declined"));
        assertThat(svc.charge(order, "fail")).isEqualTo(MarketplacePaymentService.Outcome.FAILED);
        assertThat(order.getPaymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("[MKT-R22.3] a lost answer stays PENDING (UNKNOWN) and is never charged again")
    void lostAnswer() {
        when(gateway.charge(anyString(), any(), anyString())).thenThrow(new RuntimeException("read timed out"));
        assertThat(svc.charge(order, "tok")).isEqualTo(MarketplacePaymentService.Outcome.UNKNOWN);
        assertThat(table.values()).singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo("PENDING"));
    }

    @Test
    @DisplayName("[MKT-R13.1] a cancelled, captured order is refunded exactly once, however many paths ask")
    void refundOnce() {
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(new PaymentGateway.Charge(true, "ch_1", null));
        when(gateway.refund("ch_1", order.getTotal())).thenReturn(new PaymentGateway.Refund(true, "re_1", null));
        svc.charge(order, "tok");
        svc.refundIfCancelled(7L);
        verify(gateway, never()).refund(anyString(), any());                    // not cancelled: nothing to give back
        order.setStatus("CANCELLED");
        svc.refundIfCancelled(7L);
        svc.refundIfCancelled(7L);                                               // seller reject + sweeper, say
        verify(gateway, times(1)).refund(eq("ch_1"), any());
        assertThat(order.getPaymentStatus()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("[MKT-R22.3] reconcile: a lost charge the provider reports as taken, on a cancelled order, is refunded")
    void reconcileLostThenRefund() {
        when(gateway.charge(anyString(), any(), anyString())).thenThrow(new RuntimeException("read timed out"));
        svc.charge(order, "tok");
        MarketplacePayment pending = table.values().iterator().next();
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(payments.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(eq("PENDING"), any())).thenReturn(List.of(pending));
        when(gateway.find("charge:k-7")).thenReturn(new PaymentGateway.Charge(true, "ch_late", null));
        when(gateway.refund("ch_late", order.getTotal())).thenReturn(new PaymentGateway.Refund(true, "re_1", null));
        order.setStatus("CANCELLED");                                            // the orphan rule cancelled it
        assertThat(svc.reconcile()).isEqualTo(1);
        verify(gateway, times(1)).refund(eq("ch_late"), any());
        assertThat(order.getPaymentStatus()).isEqualTo("REFUNDED");
    }

    // ── MKT-2a: a part of a multi-seller order ──────────────────────────────────────────────────────────

    void byOrder() {
        lenient().when(payments.findByMktOrderIdOrderByIdAsc(7L)).thenAnswer(i -> table.values().stream()
                .sorted(java.util.Comparator.comparing(MarketplacePayment::getId)).toList());
    }

    @Test
    @DisplayName("[MKT-R17.2] one part's money goes back once while the order goes ahead: PARTIALLY_REFUNDED")
    void partRefundedOnce() {
        byOrder();
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(new PaymentGateway.Charge(true, "ch_1", null));
        when(gateway.refund(eq("ch_1"), any())).thenReturn(new PaymentGateway.Refund(true, "re_1", null));
        svc.charge(order, "tok");
        assertThat(svc.refundPart(7L, 301L, new BigDecimal("20000.00"), "part not fulfilled")).isTrue();
        assertThat(svc.refundPart(7L, 301L, new BigDecimal("20000.00"), "again")).isTrue();       // sweeper + reject, say
        verify(gateway, times(1)).refund(eq("ch_1"), eq(new BigDecimal("20000.00")));
        assertThat(order.getPaymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    @DisplayName("[MKT-R17.2] when the whole order ends after a part was refunded, only the REST goes back: never the same money twice")
    void remainderAfterPart() {
        byOrder();
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(new PaymentGateway.Charge(true, "ch_1", null));
        when(gateway.refund(eq("ch_1"), any())).thenReturn(new PaymentGateway.Refund(true, "re_x", null));
        svc.charge(order, "tok");
        svc.refundPart(7L, 301L, new BigDecimal("20000.00"), "part");
        order.setStatus("CANCELLED");
        svc.refundIfCancelled(7L);
        verify(gateway).refund("ch_1", new BigDecimal("32000.00"));
        assertThat(order.getPaymentStatus()).isEqualTo("REFUNDED");
        svc.refundPart(7L, 302L, new BigDecimal("32000.00"), "late part");                         // covered by the rest
        verify(gateway, times(2)).refund(eq("ch_1"), any());
        verify(orders, org.mockito.Mockito.atLeastOnce()).lockById(7L);
    }

    @Test
    @DisplayName("[MKT-R17.2] a cash order has no charge: a part ending gives nothing back")
    void cashPartNothing() {
        assertThat(svc.refundPart(7L, 301L, new BigDecimal("20000.00"), "part")).isFalse();
        verify(gateway, never()).refund(anyString(), any());
    }
}
