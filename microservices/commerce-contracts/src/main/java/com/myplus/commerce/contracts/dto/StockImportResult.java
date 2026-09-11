package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a stock import DID — the rows created, and the on-hand each product now has.
 *
 * <h3>⭐ PERF-9: the write returns the resulting state, so nobody has to read it back</h3>
 * {@code /stock/import} used to answer with a bare count, so the caller knew how many rows were written and
 * nothing about the number a shopkeeper is actually looking at. The Product screen therefore did this:
 *
 * <pre>
 *   POST /addProductStock      → {"success":true,"created":"1"}
 *   GET  /productStock?...     → read the new on-hand back
 * </pre>
 *
 * <b>Two browser round trips to change one number on screen.</b> On a shop's own LAN that is ~85 ms and
 * nobody notices; over a real connection it is two full round trips, and the second one exists only because
 * the first threw its answer away.
 *
 * <p>{@code StockImportService} already computes the new on-hand — it sets it on the StockLevel it saves.
 * This carries it back instead of discarding it, which is the same thing
 * {@link com.myplus.commerce.contracts.client.InventoryClient#reconcilePurchase} has always done for a
 * purchase edit ("Returns the product's new on-hand"). The pattern is not new here; the import was the
 * outlier.
 *
 * <h3>Why a map rather than a single figure</h3>
 * The endpoint takes a LIST of lines and six callers use it in bulk — a sale return, a void, a repossession,
 * a purchase. One line per product is the interactive case, not the contract.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StockImportResult {

    /** Rows written. What this endpoint used to return on its own. */
    private int created;

    /**
     * Product id → the on-hand that product now has, for every product touched.
     *
     * <p>A {@code LinkedHashMap} so a bulk caller reading it back sees its own line order.
     */
    @Builder.Default
    private Map<Long, BigDecimal> onHand = new LinkedHashMap<>();

    /** The on-hand for one product, or {@code null} when this import did not touch it. */
    public BigDecimal onHandFor(Long productId) {
        return onHand == null ? null : onHand.get(productId);
    }
}
