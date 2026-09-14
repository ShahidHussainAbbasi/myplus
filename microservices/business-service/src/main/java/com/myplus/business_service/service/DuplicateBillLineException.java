package com.myplus.business_service.service;

/**
 * DOC-INT C — this purchase line repeats one the bill already has, and the operator has not confirmed it.
 *
 * <p>An ANSWER, not a failure: thrown before anything is written or any stock comes in, and turned into a
 * {@code CONFIRM} envelope by {@code PurchaseController} — the same shape as
 * {@link CreditConfirmationRequiredException}, but answered by its OWN flag ({@code duplicateBillAcknowledged}),
 * so confirming one prompt can never silently confirm the other.
 */
public class DuplicateBillLineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateBillLineException(final String message) {
        super(message);
    }
}
