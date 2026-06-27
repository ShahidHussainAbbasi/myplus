package com.myplus.marketplace.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sandbox payment gateway (E2b, slice 48). Deterministic mock so the storefront pay-flow is real + testable
 * without PSP keys: a {@code "fail"} token declines, anything else succeeds. Real Stripe (PaymentIntent + webhook)
 * replaces {@link #charge} later; the {@link Charge} result shape stays.
 */
@Component
public class PaymentGateway {

    public Charge charge(String token, BigDecimal amount) {
        if ("fail".equalsIgnoreCase(token)) {
            return new Charge(false, null, "Card declined");
        }
        return new Charge(true, "ch_sandbox_" + UUID.randomUUID().toString().substring(0, 12), null);
    }

    public record Charge(boolean success, String chargeId, String declineReason) {}
}
