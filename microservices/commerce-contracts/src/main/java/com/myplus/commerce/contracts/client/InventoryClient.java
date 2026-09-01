package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.StockBatch;
import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.commerce.contracts.dto.StockPurchaseAdjust;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnRequest;
import com.myplus.commerce.contracts.dto.StockReturnResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * Declarative client for the inventory-service stock-reservation API — the three saga steps of the
 * sell↔stock flow (slice 33). The implementing proxy is built from a load-balanced {@code RestClient}
 * (base URL {@code lb://inventory-service}) in the consuming service in Phase 6; this module ships only the
 * contract so caller (trade/pharma) and provider (inventory) compile against one source of truth.
 *
 * <p>Idempotency is via {@link StockReservationRequest#getIdempotencyKey()}; org/actor propagate as headers.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface InventoryClient {

    /** Saga step 1 — hold stock (FEFO). Returns RESERVED + picks, or OUT_OF_STOCK (nothing held). */
    /**
     * Task #20 — the tenant's stock valued at cost, for the dashboard KPI.
     *
     * <p>Returns the whole summary rather than a bare number so the dashboard can grow the low-stock and
     * out-of-stock counts onto the same call later instead of opening a second round trip for each.
     *
     * <p>⚠ The value is stock at LAST PURCHASE RATE — {@code StockLevel.costPrice} is stamped from
     * {@code bpurchaseRate} by the purchase path. It is not a weighted average and it is not the GL
     * inventory balance, so whatever displays it must say which number it is.
     */
    @GetExchange("/stock/summary")
    java.util.Map<String, Object> stockSummary();

    @PostExchange("/reservations")
    StockReservationResponse reserve(@RequestBody StockReservationRequest request);

    /** Saga step 3 — confirm a held reservation: stock is decremented. Idempotent on reservationId. */
    @PostExchange("/reservations/{reservationId}/confirm")
    StockReservationResponse confirm(@PathVariable String reservationId);

    /**
     * O7 D1c — release a hold by the CALLER'S key (e.g. an order's {@code SO-42-HOLD}).
     *
     * <p>An order has its own key and nowhere sensible to keep inventory's reservation id; storing ours on
     * their table would strand stock whenever that write failed. Silent when nothing matches — the expiry
     * sweeper may have collected it, and either way the stock is free.
     */
    @PostExchange("/reservations/release-by-key")
    StockReservationResponse releaseByKey(@RequestParam("key") String key);

    /** Compensation — release a held reservation (sale failed/abandoned): held stock returns. Idempotent. */
    @PostExchange("/reservations/{reservationId}/release")
    StockReservationResponse release(@PathVariable String reservationId);

    /** G2 inverse saga (slice 34) — return sold stock for a CONFIRMED reservation: restore each product to its
     *  original batches (the reservation picks, capped), falling back to a fresh batch when picks are unavailable. */
    @PostExchange("/reservations/{reservationId}/return")
    StockReturnResponse returnStock(@PathVariable String reservationId, @RequestBody StockReturnRequest request);

    /** Seed opening stock for migrated products (item→product, slice 33 U2b). Returns the number created. */
    @PostExchange("/stock/import")
    Integer importStock(@RequestBody List<StockImportLine> lines);

    /** Reconcile a purchase EDIT: apply the signed quantity delta to the purchase's own batch + StockLevel,
     *  keeping batch totals and on-hand consistent. Returns the product's new on-hand. */
    @PostExchange("/stock/purchase-adjust")
    Float reconcilePurchase(@RequestBody StockPurchaseAdjust adjust);

    /** Current on-hand for a product (slice 33, U4) — lets the trade UI show inventory's stock, not local. */
    @GetExchange("/stock/level/{productId}")
    Float getStockLevel(@PathVariable Long productId);

    /** Batch on-hand for the whole tenant (slice 62, M3.1): productId → currentStock, one call. */
    @GetExchange("/stock/levels")
    java.util.Map<Long, Float> getStockLevels();

    /*
     * U1's BOUNDARY OBLIGATION - CLOSED by U2, and closed by a DECISION rather than by code.
     * Design: docs/slices/u2-loose-sale-arithmetic.md section 2.
     *
     * The obligation recorded here was: "stock is stored in BASE UNITS (U0), so the moment a shop sets
     * packSize = 10, on-hand of one pack becomes 10 base units and every caller below must convert or start
     * showing tablets where the shelf holds packs."
     *
     * IT WAS BUILT ON A PREMISE THAT WAS NEVER TRUE. U0 changed the column TYPE (Float -> DECIMAL(19,4)) and
     * multiplied nothing: at the time every packSize was null, so the migration was an identity and every
     * stock row in the database is in SELLING UNITS to this day.
     *
     * U2 faced the fork for real - it is the first code that sends a quantity to inventory for a product with
     * a pack size - and chose to keep it that way. Selling 5 tablets of a 10-pack decrements 0.5 PACKS.
     * So there is nothing to convert here, and the two callers below are correct exactly as they stand:
     *
     *   business-service  StockController      - the Stock screen's on-hand      (packs, as always)
     *   marketplace       BackorderPolicy      - sellable, for the shortfall split (packs, as always)
     *
     * ⚠ IF A LATER SLICE MOVES STOCK TO TRUE BASE UNITS - which is where SAP and Odoo both sit, and remains
     * open because Sell.packSizeSnapshot keeps historical lines interpretable across the change - then this
     * obligation comes BACK, and it must land in the same deploy as the purchase, adjustment, transfer,
     * import and count conversions. Its failure mode is a shop's on-hand out by a factor of packSize.
     *
     * Kept rather than deleted, because an obligation that quietly evaporates is how a real one gets missed.
     */

    /**
     * OMS O5c — per-product {@code {onHand, sellable, expired, held}} for the whole tenant, in one call.
     *
     * <p>{@link #getStockLevels()} returns ON-HAND, which overstates what can actually be sold: it includes
     * expired batches and stock held by a checkout in flight. A backorder split has to be measured against
     * SELLABLE, or the shop would promise goods it cannot pick.
     *
     * <p>Advisory only. {@link #reserve} remains authoritative — if stock is taken between this read and the
     * sale, the reserve still refuses, which is the safe direction.
     */
    @GetExchange("/stock/levels/detail")
    java.util.Map<Long, java.util.Map<String, Float>> getStockLevelDetail();

    /** FEFO batches (batch/expiry + sellable qty) a sale/dispense would draw from next (slice 54, P10). */
    @GetExchange("/stock/batches/{productId}")
    List<StockBatch> getBatches(@PathVariable Long productId);
}
