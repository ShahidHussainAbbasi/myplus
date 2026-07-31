package com.myplus.common.subledger;

import java.math.BigDecimal;

/**
 * The result of a subledger settlement: the ledger voucher/receipt number, how much of the payment was allocated
 * to open docs, any excess left on account (customer credit / vendor advance), and the party's fresh running
 * balance after recompute. Each caller maps this to its own response keys (receiptNo vs voucherNo, etc.).
 */
public record SettleOutcome(String voucherNo, BigDecimal allocated, BigDecimal onAccount, BigDecimal newDue) {}
