package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D5 — a payment received from a trade customer, handed to business-service to allocate and post.
 *
 * <p><b>One body, no query parameters.</b> A Spring HTTP-interface client encodes {@code @RequestParam} as FORM
 * DATA on a POST, which cannot coexist with a {@code @RequestBody} — D4's first draft mixed the two and every
 * call failed at runtime. {@link SaleRecordRequest} and {@link SaleReturnRequest} both carry everything in one
 * body, and so does this.
 *
 * <p><b>The caller does not say which invoices this pays.</b> That is deliberate: business-service owns the
 * receivable and allocates FIFO across the customer's open invoices through the one shared allocator AR and AP
 * already use. A channel that nominated invoices would be a second allocation rule, and two rules disagreeing
 * about the same money is what the trade contract exists to prevent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceiptRequest {

    /**
     * The trade account paying. <b>Resolved within the CALLER's tenant by the receiver</b> — the id arrives off
     * the wire, so it is scoped there rather than trusted; another tenant's customer reads as absent, exactly
     * as a missing one does.
     */
    private Long customerId;

    /** How much. Positive; the receiver refuses anything else. */
    private BigDecimal amount;

    /** CASH / CARD / BANK / CHEQUE — decides the cash-side GL account on the receipt journal. */
    private String method;

    /**
     * The date the money is recognised on. Carried rather than defaulted downstream because a day-end
     * remittance is dated on the day it was counted, and that period must be OPEN — the receiver's period lock
     * checks this value.
     */
    private LocalDate paidOn;

    /** A human trace back to what caused this receipt, e.g. {@code DS-7 / INV-1042}. */
    private String reference;

    /**
     * What makes a retry safe. The receipt commits REMOTELY, so a caller whose own transaction rolls back after
     * a successful receipt must be able to replay rather than double-charge — which means this key has to be
     * derived from something the remote side already committed against, not from the retrying batch.
     */
    private String idempotencyKey;
}
