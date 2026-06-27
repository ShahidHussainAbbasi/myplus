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

    /** Refund (full or partial) a prior charge. {@code amount} is the money to return. */
    Refund refund(String chargeId, BigDecimal amount);

    record Charge(boolean success, String chargeId, String declineReason) {}

    record Refund(boolean success, String refundId, String reason) {}
}
