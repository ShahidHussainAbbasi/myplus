package com.myplus.finance.entity;

/** The five accounting account classes. Determines where an account rolls up (P&L: INCOME/EXPENSE; Balance Sheet:
 *  ASSET/LIABILITY/EQUITY) and its normal side. */
public enum AccountType {
    ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
}
