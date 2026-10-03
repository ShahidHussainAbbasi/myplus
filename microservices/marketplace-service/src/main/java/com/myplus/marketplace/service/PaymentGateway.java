package com.myplus.marketplace.service;

import java.math.BigDecimal;

/**
 * Payment provider abstraction (E6, slice 70). Implementations charge a card token and refund a prior charge. The
 * default is {@link SandboxPaymentGateway} (deterministic, no PSP keys); a real Stripe implementation is selected by
 * {@code payments.provider=stripe} once the operator supplies keys (PaymentIntent + webhook) — deferred like the AWS
 * deploy bootstrap. The {@link Charge}/{@link Refund} result shapes stay stable across providers.
 */
public interface PaymentGateway {

    Charge charge(String token, BigDecimal amount);

    /**
     * MKT-1e2: the same charge with an IDEMPOTENCY KEY — a retry after a lost answer must find the first charge, never
     * make a second. A real provider passes the key to the PSP (Stripe: Idempotency-Key). This default suits a
     * provider with no network between us (the sandbox): it cannot lose an answer, so there is nothing to repeat.
     */
    default Charge charge(String token, BigDecimal amount, String idempotencyKey) {
        return charge(token, amount);
    }

    /**
     * MKT-1e2: what happened to the charge made with this idempotency key — for a charge whose answer was lost.
     * {@code null} = the provider cannot say (yet). The default suits the sandbox, which never loses an answer; a real
     * provider looks the key up (Stripe: list PaymentIntents by metadata / idempotent replay).
     */
    default Charge find(String idempotencyKey) {
        return null;
    }

    /** The provider's name on a recorded payment fact ("sandbox", "stripe"). */
    default String name() {
        return getClass().getSimpleName().replace("PaymentGateway", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Refund (full or partial) a prior charge. {@code amount} is the money to return. */
    Refund refund(String chargeId, BigDecimal amount);

    record Charge(boolean success, String chargeId, String declineReason) {}

    record Refund(boolean success, String refundId, String reason) {}
}
