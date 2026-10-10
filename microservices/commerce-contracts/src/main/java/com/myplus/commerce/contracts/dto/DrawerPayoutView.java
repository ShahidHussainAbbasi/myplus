package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EX-9a — a till pay-out that never became an expense (made before Expense management was switched on), as
 * business-service records it. expense-service lists these for the owner to bring into the books, by choice.
 */
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class DrawerPayoutView {
    private Long movementId;
    private LocalDate date;
    private BigDecimal amount;
    private String reason;
    private Long storeId;
}
