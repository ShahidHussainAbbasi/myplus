package com.myplus.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** F3 (GL): a chart-of-accounts line for the API (type/normalSide as plain strings). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AccountDTO {
    private Long id;
    private String code;
    private String name;
    private String type;        // ASSET | LIABILITY | EQUITY | INCOME | EXPENSE
    private String normalSide;  // DEBIT | CREDIT
}
