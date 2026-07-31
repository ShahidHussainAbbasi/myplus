package com.myplus.common.subledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * F2: one line of a statement of account — a document (BILL: debit, increases the balance owed) or a payment
 * (PAYMENT: credit, decreases it). {@code balance} is the running balance filled by {@link StatementBuilder}.
 * Party-agnostic: for AR the balance is what the customer owes; for AP what we owe the vendor.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StatementLine {
    private LocalDate date;
    private String docNo;
    private String type;        // BILL | PAYMENT
    private BigDecimal debit;   // bill amount (BILL lines)
    private BigDecimal credit;  // payment amount (PAYMENT lines)
    private BigDecimal balance; // running balance after this line (set by StatementBuilder)
}
