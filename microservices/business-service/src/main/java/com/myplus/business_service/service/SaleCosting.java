package com.myplus.business_service.service;

import com.myplus.business_service.entity.SellBatch;
import com.myplus.business_service.repository.SellBatchRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * #17 P3 — the ONE definition of what a sale cost. Design: {@code docs/slices/bonus-schemes-p3.md}.
 *
 * <h3>Why this type exists</h3>
 * COGS reached the GL from three places — {@code SagaSellService} (new sale), {@code SellController} (edit
 * repost and sale return) and {@code RepossessionService} — each computing {@code costPrice x quantity}
 * inline. Three copies of a money formula is not a style problem: it is three things that must change
 * together and will not.
 *
 * <p>(Two further copies in {@code SagaSellService} are margin POLICY checks that run before the sale is
 * written. They keep the snapshot formula, correctly: at that point nothing has been reserved, so there are
 * no batches to cost from. {@link #snapshotCogs} is the shared definition for them.)
 *
 * <p>The bonus work proved it. {@code quantity} excludes free goods, so making bonus units cost anything
 * meant editing the same formula in five places and keeping them in step forever. Deriving cost from the
 * batches the sale actually consumed removes the special case entirely — the reservation covers paid plus
 * bonus, the batches record what left, and the cost follows.
 *
 * <h3>The rule</h3>
 * <b>Cost follows the goods.</b> Not a proxy rate, and not the latest purchase price: the cost of the
 * specific batches FEFO took, as recorded on the sale at the moment it was written.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SaleCosting {

    private final SellBatchRepo sellBatchRepo;

    /**
     * COGS for a set of sale lines.
     *
     * <p>Prefers the batches recorded against each sale — the true cost of the goods that left. Falls back to
     * the line's own {@code costPrice x quantity} snapshot for any line with no recorded batches, which is
     * every sale written before P3 and any line whose reservation carried no cost.
     *
     * <p><b>The fallback is not a nicety.</b> Returning zero for an uncosted line would post a sale with no
     * cost at all, showing 100% margin on an old invoice — far worse than the approximation it replaces.
     *
     * @param lines   the sale's lines, carrying the pre-P3 cost snapshot
     * @param sellIds the persisted sell ids for those lines, or null before they exist
     */
    public BigDecimal cogs(List<SagaLine> lines, List<Long> sellIds) {
        BigDecimal fromBatches = BigDecimal.ZERO;
        boolean anyBatches = false;

        int costlessBatches = 0;
        if (sellIds != null && !sellIds.isEmpty()) {
            // ONE batched read for the whole sale — never a query per line.
            for (SellBatch b : sellBatchRepo.findBySellIds(sellIds)) {
                if (b.getQuantity() == null) continue;
                if (b.getUnitCost() == null) { costlessBatches++; continue; }
                anyBatches = true;
                fromBatches = fromBatches.add(b.getUnitCost().multiply(b.getQuantity()));
            }
        }
        /*
         * ⚠ COGS-2 — the same all-or-nothing trap {@link #cogsFromPicks} carried, on the edit/return path.
         *
         * A sale whose batches are PARTLY costed returned only the costed ones and said nothing, because
         * `anyBatches` was true. The uncosted rows silently contributed zero. Here the read is per-BATCH and
         * the batch does not carry the line's snapshot, so the honest repair is to say the total is
         * incomplete rather than to pretend otherwise — the caller then sees a warning naming the shortfall
         * instead of a clean-looking figure.
         */
        if (anyBatches) {
            if (costlessBatches > 0) {
                log.warn("COGS: {} recorded batch(es) on this sale carry NO unit cost and contributed zero; "
                        + "the posted cost ({}) is INCOMPLETE. sellIds={}",
                        costlessBatches, fromBatches, sellIds);
            }
            return fromBatches.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        /*
         * The fallback is CORRECT for a sale written before P3 — but on a NEW sale it means the batch costs
         * never arrived, and the books would quietly carry an approximation while looking fine. A silent
         * fallback is the failure mode this slice exists to remove, so it says so.
         */
        BigDecimal snap = snapshotCogs(lines);
        log.warn("COGS fell back to the line snapshot ({}): sellIds={}, no batch costs found. "
                + "Expected for a pre-P3 sale; on a new sale it means unit costs did not reach sell_batch.",
                snap, sellIds);
        return snap;
    }

    /**
     * COGS straight from the FEFO picks the reservation returned.
     *
     * <p><b>Preferred on the sale path.</b> The picks are already in memory — they are what was just written
     * to {@code sell_batch} — so re-reading that table a moment later is both slower and, as the gate proved,
     * unreliable: the GL enqueue ran before those rows were visible to it, silently fell back to the line
     * snapshot, and posted a cost that disagreed with the sale own record of what it consumed.
     *
     * <p>Write-then-read-back is the mistake. Cost the goods from the same data that decided which goods left.
     */
    public BigDecimal cogsFromPicks(List<com.myplus.commerce.contracts.dto.StockPick> picks,
                                    List<SagaLine> lines) {
        if (picks == null || picks.isEmpty()) {
            // LOUD. A reservation with no picks means the sale costed itself from the line snapshot and
            // recorded no batches at all — the receipt then shows no traceability and COGS is an
            // approximation. This case was silent while the "picks without cost" case warned, so an empty
            // picks list looked identical to a correct sale.
            log.warn("COGS: the reservation returned NO PICKS; using the line snapshot and recording no batches");
            return snapshotCogs(lines);
        }
        /*
         * ⚠ COGS-2 — THE FALLBACK IS PER PRODUCT, NEVER PER INVOICE.
         *
         * This summed only the picks that HAD a cost and fell back only when NONE did. A sale mixing the two
         * therefore posted a PARTIAL cost and said nothing: the `any` flag was true, so no fallback ran and
         * no warning was logged.
         *
         * INV-000011 (org 13, 2026-09-23) is the worked example. Two lines, costing 250 and 350. FEFO drew
         * the first from a batch carrying NO purchase price, so its pick had no unit cost and was skipped —
         * while the second line's 350 kept `any` true. COGS posted 350 against a true cost of 600, and the
         * invoice reported a profit of 330 where 80 was right. The trial balance still balanced to the penny,
         * because the entry was internally consistent; it was simply missing a quarter of the cost.
         *
         * So an uncosted pick now falls back to ITS OWN LINE's snapshot (SF-10's latest purchase rate), which
         * on that invoice held the correct 250. Same principle as {@link #cogs}: never return zero cost for
         * goods that left the shelf — that is a 100% margin on real stock.
         */
        java.util.Map<Long, BigDecimal> snapshotByProduct = new java.util.HashMap<>();
        if (lines != null) {
            for (SagaLine l : lines) {
                if (l == null || l.productId() == null || l.costPrice() == null) continue;
                snapshotByProduct.put(l.productId(), l.costPrice());
            }
        }

        BigDecimal cost = BigDecimal.ZERO;
        java.util.List<Long> uncosted = new java.util.ArrayList<>();
        java.util.List<Long> unpriceable = new java.util.ArrayList<>();
        for (com.myplus.commerce.contracts.dto.StockPick p : picks) {
            if (p == null || p.getQuantity() == null) continue;
            if (p.getUnitCost() != null) {
                cost = cost.add(p.getUnitCost().multiply(p.getQuantity()));
                continue;
            }
            // This batch recorded no purchase price. Cost the goods from the line's own snapshot rather than
            // dropping them: the units physically left, so they must carry a cost.
            BigDecimal snap = snapshotByProduct.get(p.getItemId());
            if (snap != null) {
                cost = cost.add(snap.multiply(p.getQuantity()));
                uncosted.add(p.getItemId());
            } else {
                // Nothing knows what this cost — neither the batch nor the product's purchase history. It
                // contributes zero, which is wrong, but it is the only honest answer available and it is
                // said out loud rather than buried in a total.
                unpriceable.add(p.getItemId());
            }
        }
        if (!uncosted.isEmpty()) {
            log.warn("COGS: {} pick(s) had no batch cost and were costed from the line snapshot instead "
                    + "(products {}). The batches they drew from carry no purchase price.",
                    uncosted.size(), uncosted);
        }
        if (!unpriceable.isEmpty()) {
            log.error("COGS: {} pick(s) have NO COST AT ALL — neither a batch cost nor a line snapshot "
                    + "(products {}). Those goods are booked at zero cost and the margin on this sale is "
                    + "overstated. Record a purchase price for them.",
                    unpriceable.size(), unpriceable);
        }
        return cost.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * The pre-P3 formula, kept as the fallback and as the single place it is still written.
     *
     * <p>{@code SagaLine.costPrice} is the "latest purchase rate" snapshot (SF-10). It is an approximation —
     * it does not know which batch left — but it is the only figure a sale written before P3 has.
     */
    public BigDecimal snapshotCogs(List<SagaLine> lines) {
        BigDecimal cost = BigDecimal.ZERO;
        if (lines == null) return cost;
        for (SagaLine l : lines) {
            if (l.costPrice() == null) continue;
            cost = cost.add(l.costPrice().multiply(BigDecimal.valueOf(l.quantity())));
        }
        return cost.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * COGS for whole persisted SELL rows — the repossession and any other path holding entities rather than
     * saga lines.
     *
     * <p>Same rule: the batches the sale consumed, falling back to each row own cost snapshot where a sale
     * predates P3.
     */
    public BigDecimal cogsFromSells(List<com.myplus.business_service.entity.Sell> sells) {
        if (sells == null || sells.isEmpty()) return BigDecimal.ZERO;
        List<Long> ids = sells.stream().map(com.myplus.business_service.entity.Sell::getSellId)
                .filter(java.util.Objects::nonNull).toList();

        BigDecimal fromBatches = BigDecimal.ZERO;
        boolean any = false;
        if (!ids.isEmpty()) {
            for (SellBatch b : sellBatchRepo.findBySellIds(ids)) {
                if (b.getUnitCost() == null || b.getQuantity() == null) continue;
                any = true;
                fromBatches = fromBatches.add(b.getUnitCost().multiply(b.getQuantity()));
            }
        }
        if (any) return fromBatches.setScale(2, java.math.RoundingMode.HALF_UP);

        BigDecimal snapshot = BigDecimal.ZERO;
        for (com.myplus.business_service.entity.Sell s : sells) {
            if (s.getCostPrice() == null || s.getQuantity() == null) continue;
            snapshot = snapshot.add(s.getCostPrice().multiply(BigDecimal.valueOf(s.getQuantity())));
        }
        return snapshot.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * COGS for a PORTION of ONE sale line — a partial return.
     *
     * <p>Allocated from the batch cost RECORDED ON THE SALE, in proportion to what is coming back: returning
     * three of eleven units reverses three units of what those goods cost when they left. Recomputing from a
     * current rate would move the margin of a sale that already happened, every time a purchase price changed.
     *
     * @param snapshotUnitCost the line's own cost snapshot, used when the sale recorded no batch costs
     *                         (every sale written before P3). Returning zero instead would credit a return
     *                         with no cost at all and overstate the reversal.
     */
    public BigDecimal cogsForPortionOfSell(Long sellId, BigDecimal returnedQty, BigDecimal soldQty,
                                           BigDecimal snapshotUnitCost) {
        BigDecimal fallback = (snapshotUnitCost == null ? BigDecimal.ZERO : snapshotUnitCost)
                .multiply(returnedQty == null ? BigDecimal.ZERO : returnedQty)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        if (sellId == null || returnedQty == null || soldQty == null || soldQty.signum() <= 0) return fallback;

        BigDecimal lineCost = BigDecimal.ZERO;
        boolean any = false;
        for (SellBatch b : sellBatchRepo.findBySellIds(List.of(sellId))) {
            if (b.getUnitCost() == null || b.getQuantity() == null) continue;
            any = true;
            lineCost = lineCost.add(b.getUnitCost().multiply(b.getQuantity()));
        }
        if (!any) return fallback;

        // ALLOCATE the recorded total, never round a unit cost and multiply back.
        return lineCost.multiply(returnedQty).divide(soldQty, 2, java.math.RoundingMode.HALF_UP);
    }

}
