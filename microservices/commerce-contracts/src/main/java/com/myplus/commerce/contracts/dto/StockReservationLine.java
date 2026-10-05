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

    /**
     * PR-3a — which line of the caller's order this is (its position). Echoed on every pick taken for it, so the
     * caller records a line's batches by LINE, not by product: two lines of one product used to each record both.
     * Null for callers that do not send it; their picks match by product, exactly as before.
     */
    private Integer lineRef;

    /** The shape every caller used before PR-3a: no line reference. */
    public StockReservationLine(Long itemId, BigDecimal quantity) {
        this(itemId, quantity, null);
    }
}
