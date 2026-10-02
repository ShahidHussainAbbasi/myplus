package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EX-3 — a till pay-out, sent by business-service to expense-service so the ledger learns of it.
 * {@code movementId} is the drawer movement's id: expense-service keys the voucher on it, so a redelivery
 * returns the first voucher instead of making a second expense.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class DrawerExpenseRequest {
    private Long movementId;
    private Long categoryId;
    private BigDecimal amount;
    private LocalDate date;
    private Long storeId;
    private String reason;
}
