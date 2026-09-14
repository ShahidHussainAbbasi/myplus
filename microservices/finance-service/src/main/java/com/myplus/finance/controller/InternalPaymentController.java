package com.myplus.finance.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ledger WRITE — reachable only from inside the private network (BLK-0).
 *
 * <h3>Why this controller exists at all</h3>
 * {@code POST /api/finance/payments} used to carry this method, and {@code /api/finance/**} <b>is routed by
 * the gateway</b>. So any holder of a valid JWT could write rows straight into the ledger: bypassing the
 * AR/AP allocation that decides which invoices a receipt settles, bypassing the idempotency that stops the
 * real screens double-charging, and leaving no record of who did it. The sibling {@link GlController} gates
 * every one of its writes with {@code ADMIN_PRIVILEGE}; this one was gated by nothing.
 *
 * <h3>⚠ Why the two obvious fixes do NOT work, so nobody re-tries them</h3>
 * <ul>
 *   <li><b>{@code @PreAuthorize} cannot be the fix.</b> A legitimate payment arrives carrying the END
 *       USER'S privileges — {@code GatewayIdentityForwarding} forwards {@code X-User-Privileges} verbatim —
 *       so any authority strong enough to stop an attacker also stops the cashier. And the user-facing
 *       {@code /receivePayment} has no {@code @PreAuthorize} at all, so "who may take a payment" has never
 *       been decided; inventing an answer here would silently change who can do their job.</li>
 *   <li><b>Enforcing {@code X-Internal-Secret} cannot be the fix either.</b> The gateway STRIPS any
 *       client-supplied value and then stamps its OWN on every request it forwards, browser requests
 *       included. That header proves "came through the gateway", not "came from a service".</li>
 * </ul>
 *
 * <h3>What actually works: the path</h3>
 * <b>No gateway route matches {@code /internal/**}</b> — verified in {@code api-gateway/application.yml},
 * which routes {@code /api/finance/**} and nothing under {@code /internal}. So this endpoint is reachable
 * by an in-network caller and by nothing outside. It is the same mechanism {@code InternalSalesController}
 * already relies on, and it needs no privilege decision to be correct.
 *
 * <h3>What stayed public, deliberately</h3>
 * The READS ({@code GET /api/finance/payments} and {@code /payments/summary}) are untouched: they are
 * tenant-scoped and {@code FinanceReportService} calls them for statements. A read is not the exposure.
 */
@RestController
@RequestMapping("/internal/finance")
@RequiredArgsConstructor
public class InternalPaymentController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalPaymentController.class);

    private final PaymentService paymentService;

    /**
     * Record a payment (+ allocations) in the ledger. Called by {@code common-subledger}'s
     * {@code SubledgerService} after business-service has already de-duplicated the operator's submit.
     */
    @PostMapping("/payments")
    public PaymentDTO record(@Valid @RequestBody RecordPaymentRequest req) {
        /*
         * ⚠ FAIL CLOSED ON A MISSING TENANT.
         *
         * PaymentService reads the org from CurrentUser and stamps it on the row. With no identity on the
         * request that is silently NULL — an unscoped ledger row, visible to the NULL-fallback leg of every
         * scoped read in this service. Refusing is the only safe answer: a payment that belongs to no
         * tenant is not a payment, it is a leak.
         *
         * There is no anti-IDOR body check here (the shape InternalSalesController uses) for a simple
         * reason: RecordPaymentRequest carries no organizationId, so the body CANNOT name a tenant. The
         * authenticated org is the only org in play.
         */
        Long org = CurrentUser.organizationId();
        if (org == null) {
            LOG.warn("internal payment write refused: no tenant identity on the request");
            throw new IllegalStateException("No tenant identity on the request");
        }
        return paymentService.record(req);
    }
}
