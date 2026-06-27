package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A FEFO pick the inventory service allocated for one reservation line: which batch, how much, and its
 * expiry — so the sale (and any pharmacy controlled-substance register) records exact batch traceability.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockPick {
    private Long itemId;
    private String batchNo;
    private BigDecimal quantity;
    private LocalDate expiryDate;
}
