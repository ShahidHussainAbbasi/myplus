package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FP-5b — one expense bill still owed to a supplier, as expense-service sees it NOW: {@code open} is what may still be
 * applied (total − paid − payments reserved but not yet confirmed), so Pay Supplier never plans against money already
 * on its way from the Expenses screen.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class OpenBillView {
    private Long id;
    private String voucherNo;
    private LocalDate voucherDate;
    private LocalDate dueDate;
    private BigDecimal open;
}
