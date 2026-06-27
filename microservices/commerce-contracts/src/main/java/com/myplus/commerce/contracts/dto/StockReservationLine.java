package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One requested line of a stock reservation: how much of a given item the caller wants to hold.
 * Tenant (org) and actor travel as gateway/propagated headers, never in the body.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReservationLine {
    /** Inventory item/product identifier (system-of-record id in inventory-service). */
    private Long itemId;
    /** Quantity requested. BigDecimal to avoid float drift across the boundary. */
    private BigDecimal quantity;
}
