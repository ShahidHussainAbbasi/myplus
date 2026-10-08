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

    /**
     * PR-3c — the stock batch (its {@code stock_entries} id) this line is about.
     *
     * <ul>
     *   <li>On a <b>reserve</b> it is a PIN: the line is held from that batch and nothing else. If the batch no longer
     *       has the quantity (another till sold it since the plan), the whole reserve is refused — the sale was priced
     *       from that batch and must never be silently re-priced from another.</li>
     *   <li>On a <b>plan</b> it is a PREFERENCE: the cashier's chosen batch is taken first and the rest follows FEFO.</li>
     * </ul>
     * Null for every caller before PR-3c: plain FEFO, exactly as before. Scoped to the caller's tenant and the line's
     * product — an id from anywhere else is simply not found.
     */
    private Long stockEntryId;

    /** The shape every caller used before PR-3a: no line reference. */
    public StockReservationLine(Long itemId, BigDecimal quantity) {
        this(itemId, quantity, null, null);
    }

    /** The PR-3a shape: a line reference, no batch. */
    public StockReservationLine(Long itemId, BigDecimal quantity, Integer lineRef) {
        this(itemId, quantity, lineRef, null);
    }
}
