package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D5 — what a receipt actually did, answered by the side that owns the receivable.
 *
 * <p>The caller gets back the SERVER's view rather than an echo of what it sent, for the same reason
 * {@link SaleRecordResult} carries the server's grand total: the channel then reports what was really applied
 * instead of what it hoped would be.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceiptResult {

    /** The receipt/voucher number the shared ledger assigned. */
    private String receiptNo;

    /** How much of the amount landed on open invoices. */
    private BigDecimal allocated;

    /** Any excess that matched no open invoice and is now credit the shop holds. */
    private BigDecimal onAccount;

    /** The customer's running balance after the allocation. */
    private BigDecimal newDue;

    /** True when a prior call with the same idempotency key had already recorded this receipt. */
    private boolean replay;
}
