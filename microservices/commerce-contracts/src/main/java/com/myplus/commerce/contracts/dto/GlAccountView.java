package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** EX-1 — one account of a tenant's chart, as finance-service's AccountDTO serialises it (extra fields ignored). */
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class GlAccountView {
    private Long id;
    private String code;
    private String name;
    private String type;   // ASSET | LIABILITY | EQUITY | INCOME | EXPENSE
}
