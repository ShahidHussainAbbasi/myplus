package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** EX-3 — the voucher expense-service made (or already had) for a drawer movement. */
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class ExpenseVoucherRef {
    private Long id;
    private String voucherNo;
}
