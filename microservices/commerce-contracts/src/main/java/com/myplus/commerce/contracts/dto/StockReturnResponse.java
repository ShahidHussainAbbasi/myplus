package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Result of a sale return (G2 inverse saga, slice 34): how much was restored to inventory. Raw body (not an
 * ApiResponse envelope) so trade-service's {@code InventoryClient} deserializes it directly.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReturnResponse {
    private String reservationId;
    private BigDecimal restoredQuantity;
    private String message;
}
