package com.myplus.business_service.service;

/**
 * B2B P1 (#9) — the transaction would take the party past their credit limit, the org's policy is
 * {@code warn}, and the operator has not yet confirmed.
 *
 * <p>Thrown <b>before anything is written</b>: no customer row touched, no invoice, and — crucially — no
 * stock reserved. The caller answers {@code CONFIRM} rather than {@code ERROR}, because nothing failed. The
 * operator is asked, and a re-submit carrying {@code creditAcknowledged} proceeds.
 *
 * <p>Distinct from {@link com.myplus.common.web.exception.ValidationException} on purpose. A validation
 * error means "this cannot be recorded"; this means "this can be recorded, once a human says so". Folding
 * the two together would either turn a question into a refusal, or teach the client to retry real errors.
 */
public class CreditConfirmationRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CreditConfirmationRequiredException(final String message) {
        super(message);
    }
}
