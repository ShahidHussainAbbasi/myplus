package com.myplus.business_service.entity;

/**
 * B2B-P4b — where a sales quote is in its life.
 *
 * <p>Two DIFFERENT gates hide inside "quote → approval → order", and modelling them as one status is the
 * classic mistake:
 * <ul>
 *   <li>{@link #PENDING_APPROVAL} is an INTERNAL permission check — "may we offer this discount?" — answered by
 *       an owner/admin before the quote leaves the building. It only ever appears when the discount exceeds the
 *       org's threshold; a quote within policy goes straight to {@link #SENT}.</li>
 *   <li>{@link #ACCEPTED} / {@link #REJECTED} are the CUSTOMER's decision. We record it; we do not enforce it.</li>
 * </ul>
 *
 * <p>{@link #EXPIRED} is DERIVED from {@code validUntil} when the quote is read — never written by a scheduled
 * job. A quote nobody has looked at does not need a background thread, and a job that silently expires customer
 * documents is a support call waiting to happen.
 */
public enum QuoteStatus {

    /** Being built. Editable; no number has been promised to anyone yet. */
    DRAFT,

    /** Discount is over the org threshold — an owner/admin must approve before it can be sent. */
    PENDING_APPROVAL,

    /** Issued to the customer. The prices on it are now a promise until {@code validUntil}. */
    SENT,

    /** The customer said yes. Ready to convert into a sale. */
    ACCEPTED,

    /** The customer said no. Terminal — kept as a record of what was offered and declined. */
    REJECTED,

    /** Past {@code validUntil} without a decision. Derived on read, terminal, cannot convert. */
    EXPIRED,

    /** Converted into an invoice. Terminal — the quote now points at the sale it became. */
    CONVERTED;

    /** A quote that can still change: only these are editable or re-priceable. */
    public boolean isOpen() {
        return this == DRAFT || this == PENDING_APPROVAL || this == SENT;
    }

    /** Terminal states never transition again — conversion, refusal and lapse are all final. */
    public boolean isTerminal() {
        return this == REJECTED || this == EXPIRED || this == CONVERTED;
    }
}
