package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OMS O7 D1c — set stock aside for a CONFIRMED ORDER, through the service that owns stock.
 *
 * <h3>Why this goes through business-service and not straight to inventory</h3>
 * The last time a channel held inventory on its own account it produced holds with no invoice behind them, and
 * **O1 deleted that saga**. business-service is the single authority on what stock means for a trade sale, so
 * the hold is taken there — the same rule that put {@code recordSale} and {@code returnLines} on this contract
 * rather than teaching marketplace to do accounting.
 *
 * <p>That a hold now has a deadline (O5a) makes this safe to attempt; it does not make a second stock
 * authority a good idea.
 *
 * <h3>The key is the ORDER, and that is deliberate</h3>
 * {@code holdKey} is derived from the order, not from the request that happens to be making it — so confirm,
 * re-confirm, and a retry after a timeout all address the SAME hold. inventory-service is idempotent on it
 * ("a retried reserve with the same key returns the existing hold, never double-holds"), which is what makes
 * a repeated confirm harmless rather than a way to sterilise stock twice over.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHoldRequest {

    /**
     * The tenant this hold belongs to.
     *
     * <p>Checked against the org the caller authenticated as — a hold is a claim on somebody's stock, and a
     * caller may only claim its own tenant's. Same guard as {@link SaleRecordRequest}.
     */
    private Long organizationId;

    /** Stable, order-derived idempotency key — e.g. {@code SO-42-HOLD}. */
    private String holdKey;

    /** What to set aside. Quantities are the WHOLE order's outstanding amounts, not one parcel's. */
    private List<StockReservationLine> lines;
}
