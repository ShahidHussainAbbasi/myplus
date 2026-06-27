package com.myplus.marketplace.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sandbox payment provider (E2b slice 48 + E6 refunds slice 70). Deterministic mock so the pay + refund flows are real
 * and testable without PSP keys: charge token {@code "fail"} declines, anything else succeeds; refunds succeed for any
 * real charge id. Default provider ({@code payments.provider} unset or {@code sandbox}); a Stripe impl would register
 * for {@code payments.provider=stripe}.
 */
@Component
@ConditionalOnProperty(name = "payments.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public Charge charge(String token, BigDecimal amount) {
        if ("fail".equalsIgnoreCase(token)) {
            return new Charge(false, null, "Card declined");
        }
        return new Charge(true, "ch_sandbox_" + shortId(), null);
    }

    @Override
    public Refund refund(String chargeId, BigDecimal amount) {
        if (chargeId == null || chargeId.isBlank()) {
            return new Refund(false, null, "No charge to refund");
        }
        return new Refund(true, "re_sandbox_" + shortId(), null);
    }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 12); }
}
