package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EX-1 — one journal line on an {@code EXPENSE} posting event: an account CODE and a debit or a credit.
 * Field names match finance-service's {@code JournalLineDTO}, so the JSON binds without a mapper.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PostingLine {
    private String accountCode;
    private BigDecimal debit;
    private BigDecimal credit;
    private String lineMemo;
}
