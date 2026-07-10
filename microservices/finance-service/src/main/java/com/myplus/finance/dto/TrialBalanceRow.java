package com.myplus.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** F3 (GL): one account's totals in the trial balance. Σ(debit) across all rows must equal Σ(credit). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TrialBalanceRow {
    private Long accountId;
    private String code;
    private String name;
    private String type;
    private BigDecimal debit;
    private BigDecimal credit;
}
