package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Payment;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplacePayment;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePaymentRepository;
import com.myplus.marketplace.service.PaymentGateway;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e2 — the money of a marketplace order, as FACTS (one {@link MarketplacePayment} row per charge or refund).
 *
 * <ul>
 *   <li><b>Charge</b>: the fact is written PENDING and committed BEFORE the provider is called, with the order's key.
 *       A lost answer leaves it PENDING; {@link #reconcile} asks again with the SAME key — never a second charge.</li>
 *   <li><b>Refund once</b>: an order that ends CANCELLED with a SUCCEEDED charge is refunded exactly once — the
 *       refund's key is derived from the charge, so every path that cancels (seller reject, expiry, orphan, customer
 *       cancel) may call {@link #refundIfCancelled} and the second call finds the first refund.</li>
 * </ul>
 * MaxTheService collects the money; MKT-1g's ledger owes it to the seller. Nothing here pays a seller.
 */
@Service
@RequiredArgsConstructor
public class MarketplacePaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplacePaymentService.class);
    /** A PENDING charge older than this is asked again. */
    static final java.time.Duration RECONCILE_AFTER = java.time.Duration.ofMinutes(1);

    public enum Outcome { SUCCEEDED, FAILED, UNKNOWN }

    private final MarketplacePaymentRepository payments;
    private final MarketplaceOrderRepository orders;
    private final PaymentGateway gateway;
    private final PlatformTransactionManager txManager;

    static String chargeKey(MarketplaceOrder o) {
        return "charge:" + o.getIdempotencyKey();
    }

    /** Charge the order's total. Not transactional: the fact commits before the provider is called. */
    public Outcome charge(MarketplaceOrder order, String cardToken) {
        String key = chargeKey(order);
        MarketplacePayment p = payments.findByIdempotencyKey(key).orElse(null);
        if (p != null && !MarketplacePayment.PENDING.equals(p.getStatus())) return outcomeOf(p);
        if (p == null) {
            try {
                p = tx().execute(s -> payments.saveAndFlush(fact(order.getId(), MarketplacePayment.CHARGE, order.getTotal(), key, null)));
            } catch (DataIntegrityViolationException race) {
                p = payments.findByIdempotencyKey(key).orElseThrow(() -> race);
                if (!MarketplacePayment.PENDING.equals(p.getStatus())) return outcomeOf(p);
            }
        }
        PaymentGateway.Charge answer;
        try {
            answer = gateway.charge(cardToken, order.getTotal(), key);
        } catch (RuntimeException lost) {
            LOG.warn("MKT charge {} for {}: no answer ({}); stays PENDING", key, order.getOrderNo(), lost.toString());
            return Outcome.UNKNOWN;
        }
        return record(p.getId(), answer);
    }

    private Outcome record(Long paymentId, PaymentGateway.Charge answer) {
        return tx().execute(s -> {
            MarketplacePayment f = payments.findById(paymentId).orElseThrow();
            if (answer != null && answer.success()) {
                f.setStatus(MarketplacePayment.SUCCEEDED);
                f.setProviderRef(answer.chargeId());
            } else {
                f.setStatus(MarketplacePayment.FAILED);
                f.setReason(answer == null ? "No answer" : answer.declineReason());
            }
            payments.save(f);
            MarketplaceOrder o = orders.findById(f.getMktOrderId()).orElseThrow();
            o.setPaymentStatus(MarketplacePayment.SUCCEEDED.equals(f.getStatus()) ? Payment.CAPTURED.name() : Payment.FAILED.name());
            orders.save(o);
            return outcomeOf(f);
        });
    }

    /**
     * An order that ended CANCELLED with a SUCCEEDED charge gets exactly one refund. Safe to call from every path
     * that cancels, and again: the refund's key is the charge's, so a second call finds the first refund.
     */
    public void refundIfCancelled(Long orderId) {
        MarketplaceOrder o = orders.findById(orderId).orElse(null);
        if (o == null || !"CANCELLED".equals(o.getStatus())) return;
        MarketplacePayment charge = payments.findByIdempotencyKey(chargeKey(o)).orElse(null);
        if (charge == null || !MarketplacePayment.SUCCEEDED.equals(charge.getStatus())) return;
        String key = "refund:" + charge.getId();
        MarketplacePayment refund = payments.findByIdempotencyKey(key).orElse(null);
        if (refund != null && MarketplacePayment.SUCCEEDED.equals(refund.getStatus())) return;
        if (refund == null) {
            try {
                refund = tx().execute(s -> payments.saveAndFlush(fact(orderId, MarketplacePayment.REFUND, charge.getAmount(), key,
                        o.getCancelReason())));
            } catch (DataIntegrityViolationException race) {
                return;                                              // another caller is refunding it
            }
        }
        PaymentGateway.Refund answer;
        try {
            answer = gateway.refund(charge.getProviderRef(), charge.getAmount());
        } catch (RuntimeException lost) {
            LOG.warn("MKT refund {} for {}: no answer ({}); the sweeper retries", key, o.getOrderNo(), lost.toString());
            return;
        }
        Long refundId = refund.getId();
        tx().executeWithoutResult(s -> {
            MarketplacePayment f = payments.findById(refundId).orElseThrow();
            if (answer != null && answer.success()) {
                f.setStatus(MarketplacePayment.SUCCEEDED);
                f.setProviderRef(answer.refundId());
                MarketplaceOrder fresh = orders.findById(orderId).orElseThrow();
                fresh.setPaymentStatus(Payment.REFUNDED.name());
                orders.save(fresh);
            } else {
                f.setReason(answer == null ? "No answer" : answer.reason());   // stays PENDING: retried
            }
            payments.save(f);
        });
    }

    /** The sweeper's backstop: charges whose answer never came, and refunds not yet done. */
    public int reconcile() {
        int n = 0;
        for (MarketplacePayment p : payments.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(MarketplacePayment.PENDING,
                LocalDateTime.now().minus(RECONCILE_AFTER))) {
            MarketplaceOrder o = orders.findById(p.getMktOrderId()).orElse(null);
            if (o == null) continue;
            if (MarketplacePayment.REFUND.equals(p.getKind())) {
                refundIfCancelled(o.getId());
                n++;
            }
            else {
                // the card token is never stored, so a lost charge is LOOKED UP by its key, never charged again
                PaymentGateway.Charge known;
                try {
                    known = gateway.find(p.getIdempotencyKey());
                } catch (RuntimeException stillLost) {
                    continue;
                }
                if (known == null) continue;                          // the provider cannot say yet: ask next sweep
                record(p.getId(), known);
                refundIfCancelled(o.getId());                         // taken for an order that is no longer going ahead
                n++;
            }
        }
        return n;
    }

    public List<MarketplacePayment> history(Long orderId) {
        return payments.findByMktOrderIdOrderByIdAsc(orderId);
    }

    private MarketplacePayment fact(Long orderId, String kind, BigDecimal amount, String key, String reason) {
        MarketplacePayment f = new MarketplacePayment();
        f.setMktOrderId(orderId);
        f.setKind(kind);
        f.setStatus(MarketplacePayment.PENDING);
        f.setAmount(amount);
        f.setIdempotencyKey(key);
        f.setReason(reason);
        f.setProvider(gateway.name());
        return f;
    }

    private static Outcome outcomeOf(MarketplacePayment p) {
        return switch (p.getStatus()) {
            case MarketplacePayment.SUCCEEDED -> Outcome.SUCCEEDED;
            case MarketplacePayment.FAILED -> Outcome.FAILED;
            default -> Outcome.UNKNOWN;
        };
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }
}
