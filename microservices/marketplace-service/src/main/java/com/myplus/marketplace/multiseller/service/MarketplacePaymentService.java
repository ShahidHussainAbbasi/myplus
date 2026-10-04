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
                // MKT-2a: what is left of the charge after the parts already refunded on their own, decided under the
                // order's lock so a part refunding at the same moment is counted exactly once
                refund = tx().execute(s -> {
                    orders.lockById(orderId);
                    MarketplacePayment again = payments.findByIdempotencyKey(key).orElse(null);
                    if (again != null) return again;
                    BigDecimal left = charge.getAmount().subtract(partsRefunded(orderId));
                    if (left.signum() <= 0) return null;                    // every part went back on its own
                    return payments.saveAndFlush(fact(orderId, MarketplacePayment.REFUND, left, key, o.getCancelReason()));
                });
            } catch (DataIntegrityViolationException race) {
                return;                                              // another caller is refunding it
            }
            if (refund == null) return;
            if (MarketplacePayment.SUCCEEDED.equals(refund.getStatus())) return;
        }
        BigDecimal amount = refund.getAmount();
        PaymentGateway.Refund answer;
        try {
            answer = gateway.refund(charge.getProviderRef(), amount);
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

    static final String RETURN_KEY = "return:";
    /** MKT-2a — one seller's part of a multi-seller order that ended while the other parts go ahead. */
    static final String PART_KEY = "part:";

    /** Part refunds written so far (SUCCEEDED or still PENDING): money already on its way back, never counted twice. */
    private BigDecimal partsRefunded(Long orderId) {
        return payments.findByMktOrderIdOrderByIdAsc(orderId).stream()
                .filter(x -> MarketplacePayment.REFUND.equals(x.getKind()))
                .filter(x -> x.getIdempotencyKey() != null && x.getIdempotencyKey().startsWith(PART_KEY))
                .filter(x -> !MarketplacePayment.FAILED.equals(x.getStatus()))
                .map(MarketplacePayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * MKT-2a — give back one part's money (once per part, {@code part:} + its id) while the rest of the order goes
     * ahead. Decided under the order's lock: when the whole order has already ended, the remainder refund of
     * {@link #refundIfCancelled} covers this part and nothing is written here.
     *
     * @return true when the money has gone back (now or before); false when there was no card charge or it is pending
     */
    public boolean refundPart(Long orderId, Long sellerOrderId, BigDecimal amount, String reason) {
        MarketplaceOrder o = orders.findById(orderId).orElse(null);
        if (o == null || amount == null || amount.signum() <= 0) return false;
        MarketplacePayment charge = payments.findByIdempotencyKey(chargeKey(o)).orElse(null);
        if (charge == null || !MarketplacePayment.SUCCEEDED.equals(charge.getStatus())) return false;
        String key = PART_KEY + sellerOrderId;
        MarketplacePayment refund = payments.findByIdempotencyKey(key).orElse(null);
        if (refund != null && MarketplacePayment.SUCCEEDED.equals(refund.getStatus())) return true;
        if (refund == null) {
            try {
                refund = tx().execute(s -> {
                    orders.lockById(orderId);
                    if (payments.findByIdempotencyKey("refund:" + charge.getId()).isPresent()) return null;   // the whole order went back
                    MarketplacePayment again = payments.findByIdempotencyKey(key).orElse(null);
                    if (again != null) return again;
                    return payments.saveAndFlush(fact(orderId, MarketplacePayment.REFUND, amount, key, reason));
                });
            } catch (DataIntegrityViolationException race) {
                return false;                                         // another caller is refunding it
            }
            if (refund == null) return false;
            if (MarketplacePayment.SUCCEEDED.equals(refund.getStatus())) return true;
        }
        return send(o, charge, refund);
    }

    /**
     * MKT-1f — refund an approved, received return through the card it was paid with: once per return
     * ({@code return:} + id), the fact written before the provider is called, a lost answer left PENDING for
     * {@link #reconcile}. The order turns PARTIALLY_REFUNDED or, once the refunds reach the charge, REFUNDED.
     *
     * @return true when the money has gone back (now or before); false when there was no card charge or it is pending
     */
    public boolean refundReturn(Long orderId, Long returnId, BigDecimal amount, String reason) {
        MarketplaceOrder o = orders.findById(orderId).orElse(null);
        if (o == null || amount == null || amount.signum() <= 0) return false;
        MarketplacePayment charge = payments.findByIdempotencyKey(chargeKey(o)).orElse(null);
        if (charge == null || !MarketplacePayment.SUCCEEDED.equals(charge.getStatus())) return false;
        String key = RETURN_KEY + returnId;
        MarketplacePayment refund = payments.findByIdempotencyKey(key).orElse(null);
        if (refund != null && MarketplacePayment.SUCCEEDED.equals(refund.getStatus())) return true;
        if (refund == null) {
            try {
                refund = tx().execute(s -> payments.saveAndFlush(fact(orderId, MarketplacePayment.REFUND, amount, key, reason)));
            } catch (DataIntegrityViolationException race) {
                return false;                                         // another caller is refunding it
            }
        }
        return send(o, charge, refund);
    }

    /** Ask the provider for one written refund fact; the order turns PARTIALLY_REFUNDED or, at the charge, REFUNDED. */
    private boolean send(MarketplaceOrder o, MarketplacePayment charge, MarketplacePayment refund) {
        Long orderId = o.getId();
        PaymentGateway.Refund answer;
        try {
            answer = gateway.refund(charge.getProviderRef(), refund.getAmount());
        } catch (RuntimeException lost) {
            LOG.warn("MKT refund {} for {}: no answer ({}); the sweeper retries", refund.getIdempotencyKey(), o.getOrderNo(), lost.toString());
            return false;
        }
        Long refundId = refund.getId();
        Boolean done = tx().execute(s -> {
            MarketplacePayment f = payments.findById(refundId).orElseThrow();
            boolean ok = answer != null && answer.success();
            if (ok) {
                f.setStatus(MarketplacePayment.SUCCEEDED);
                f.setProviderRef(answer.refundId());
                BigDecimal refunded = payments.findByMktOrderIdOrderByIdAsc(orderId).stream()
                        .filter(x -> MarketplacePayment.REFUND.equals(x.getKind()))
                        .filter(x -> MarketplacePayment.SUCCEEDED.equals(x.getStatus()) || x.getId().equals(refundId))
                        .map(MarketplacePayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                MarketplaceOrder fresh = orders.findById(orderId).orElseThrow();
                fresh.setPaymentStatus(refunded.compareTo(charge.getAmount()) >= 0 ? Payment.REFUNDED.name()
                        : Payment.PARTIALLY_REFUNDED.name());
                orders.save(fresh);
            } else {
                f.setReason(answer == null ? "No answer" : answer.reason());   // stays PENDING: retried
            }
            payments.save(f);
            return ok;
        });
        return Boolean.TRUE.equals(done);
    }

    /** The sweeper's backstop: charges whose answer never came, and refunds not yet done. */
    public int reconcile() {
        int n = 0;
        for (MarketplacePayment p : payments.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(MarketplacePayment.PENDING,
                LocalDateTime.now().minus(RECONCILE_AFTER))) {
            MarketplaceOrder o = orders.findById(p.getMktOrderId()).orElse(null);
            if (o == null) continue;
            if (MarketplacePayment.REFUND.equals(p.getKind())) {
                // MKT-1f: a return's refund belongs to a DELIVERED order, which refundIfCancelled ignores — route by key.
                if (p.getIdempotencyKey().startsWith(RETURN_KEY)) refundReturn(p.getMktOrderId(),
                        Long.valueOf(p.getIdempotencyKey().substring(RETURN_KEY.length())), p.getAmount(), p.getReason());
                else if (p.getIdempotencyKey().startsWith(PART_KEY)) refundPart(p.getMktOrderId(),
                        Long.valueOf(p.getIdempotencyKey().substring(PART_KEY.length())), p.getAmount(), p.getReason());
                else refundIfCancelled(o.getId());
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
