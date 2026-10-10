package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EX-9b — a farm expense row recorded on the old farm screen that is not in the books yet, as agriculture-service keeps
 * it. expense-service lists these for the owner to bring in, by choice (R-4).
 */
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class FarmExpenseView {
    private Long id;
    private LocalDate date;
    private BigDecimal amount;
    private String expenseName;
    private String expenseType;
    private Long landId;
    private String landName;
    private String cropName;
    private String description;
}
