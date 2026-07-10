package com.myplus.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** F3 (GL): one journal line for the API — target account (by id OR code) + a debit or a credit. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class JournalLineDTO {
    private Long accountId;
    private String accountCode;   // alternative to accountId — resolved to the tenant's account
    private BigDecimal debit;
    private BigDecimal credit;
    private String lineMemo;
}
