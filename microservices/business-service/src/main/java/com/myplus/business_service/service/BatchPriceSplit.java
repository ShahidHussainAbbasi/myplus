package com.myplus.business_service.service;

import com.myplus.commerce.contracts.dto.StockPick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PR-3c — how one sale line is priced from the batches its plan takes (Per batch mode). Pure: no Spring, no I/O.
 *
 * <p>Design: {@code docs/selling-price-per-purchase-analysis.md} §10.3–10.4. A batch carries its own selling price
 * (PR-3b, NULL = the product's price). When a line's units come from batches at DIFFERENT prices, the line becomes one
 * part per price, in the order the batches are taken, so {@code quantity × rate} stays exact on every row and a return
 * or receipt is per part. Batches at the SAME price stay one part.
 *
 * <p><b>Paid units are taken first, then the bonus.</b> A line issues {@code paid + bonus}; the plan picks cover both.
 * The paid quantity is priced from the first picks; what is left of the picks is the free goods, which ride on the
 * LAST part (it is the last part's batches they leave from). Bonus takes no part in any price.
 *
 * <p><b>A line that is not eligible</b> (the cashier typed a price, a contract price applied, a loose or serial line, a
 * made-to-order item) is never re-priced here — it keeps the price it was built with. Its picks are still returned,
 * so the reserve can pin exactly the batches that were planned.
 */
public final class BatchPriceSplit {

    private BatchPriceSplit() {}

    /** One slice of a batch: so much of it, for this part. */
    public record Piece(Long stockEntryId, String batchNo, BigDecimal quantity, BigDecimal unitCost) {}

    /**
     * One part of a split line.
     *
     * @param paidQuantity the units the customer pays for on this part
     * @param rate         the price of one unit — the batch's, or the product's for a batch without one;
     *                     null on a part that was not re-priced (keeps the line's own rate)
     * @param bonus        free goods carried on this part (only ever the last part)
     * @param pieces       every batch slice this part takes, paid and bonus, for the pinned reserve and sell_batch
     * @param batchLabel   the batch numbers priced, for the line's price reason ("Batch B-0912")
     * @param unitCost     what one PAID unit of this part cost (from its batches), null when any piece has no cost
     */
    public record Part(BigDecimal paidQuantity, BigDecimal rate, BigDecimal bonus, List<Piece> pieces,
                       String batchLabel, BigDecimal unitCost) {}

    /**
     * Split one line.
     *
     * @param paid          the paid quantity of the line, in selling units
     * @param bonus         the free goods on the line, or null
     * @param eligible      false = keep the line's own rate, one part
     * @param picks         this line's planned picks, in the order the plan takes them
     * @param productPrice  the product's price — what a batch with no price of its own sells at
     */
    public static List<Part> split(BigDecimal paid, BigDecimal bonus, boolean eligible, List<StockPick> picks,
                                   BigDecimal productPrice) {
        BigDecimal paidLeft = nz(paid);
        BigDecimal bonusQty = nz(bonus);
        List<Part> parts = new ArrayList<>();
        if (!eligible || picks == null || picks.isEmpty()) {
            List<Piece> pieces = new ArrayList<>();
            if (picks != null) for (StockPick p : picks) pieces.add(piece(p, nz(p.getQuantity())));
            parts.add(new Part(paidLeft, null, bonusQty.signum() > 0 ? bonusQty : null, pieces, null,
                    costOf(pieces, paidLeft)));
            return parts;
        }

        // Walk the picks: paid first, grouped by effective price; the rest is the bonus.
        List<BigDecimal> rates = new ArrayList<>();
        List<List<Piece>> paidPieces = new ArrayList<>();
        List<BigDecimal> paidQty = new ArrayList<>();
        List<Piece> bonusPieces = new ArrayList<>();
        for (StockPick p : picks) {
            BigDecimal q = nz(p.getQuantity());
            BigDecimal paidTake = q.min(paidLeft).max(BigDecimal.ZERO);
            BigDecimal rest = q.subtract(paidTake);
            if (paidTake.signum() > 0) {
                BigDecimal rate = p.getSellPrice() != null ? p.getSellPrice() : productPrice;
                int last = rates.size() - 1;
                if (last >= 0 && sameMoney(rates.get(last), rate)) {
                    paidPieces.get(last).add(piece(p, paidTake));
                    paidQty.set(last, paidQty.get(last).add(paidTake));
                } else {
                    rates.add(rate);
                    List<Piece> ps = new ArrayList<>();
                    ps.add(piece(p, paidTake));
                    paidPieces.add(ps);
                    paidQty.add(paidTake);
                }
                paidLeft = paidLeft.subtract(paidTake);
            }
            if (rest.signum() > 0) bonusPieces.add(piece(p, rest));
        }
        if (rates.isEmpty()) {
            // Nothing paid came from a batch (a bonus-only line): keep the line as it was.
            return split(paid, bonus, false, picks, productPrice);
        }
        for (int i = 0; i < rates.size(); i++) {
            boolean lastPart = i == rates.size() - 1;
            List<Piece> pieces = new ArrayList<>(paidPieces.get(i));
            BigDecimal cost = costOf(pieces, paidQty.get(i));
            if (lastPart) pieces.addAll(bonusPieces);
            parts.add(new Part(paidQty.get(i), rates.get(i),
                    lastPart && bonusQty.signum() > 0 ? bonusQty : null,
                    pieces, label(paidPieces.get(i)), cost));
        }
        return parts;
    }

    /**
     * Share a whole-line AMOUNT discount across the parts in proportion to their totals, to the paisa; the last part
     * takes the remainder so the shares always add back to the discount exactly.
     */
    public static List<BigDecimal> shareDiscount(BigDecimal discount, List<BigDecimal> partTotals) {
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal t : partTotals) total = total.add(nz(t));
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < partTotals.size(); i++) {
            BigDecimal share;
            if (i == partTotals.size() - 1) share = nz(discount).subtract(given);
            else if (total.signum() == 0) share = BigDecimal.ZERO;
            else share = nz(discount).multiply(nz(partTotals.get(i))).divide(total, 2, RoundingMode.HALF_UP);
            out.add(share);
            given = given.add(share);
        }
        return out;
    }

    private static Piece piece(StockPick p, BigDecimal qty) {
        return new Piece(p.getStockEntryId(), p.getBatchNo(), qty, p.getUnitCost());
    }

    /** Weighted cost of one paid unit over the pieces; null if any piece's cost is unknown (never guess). */
    private static BigDecimal costOf(List<Piece> pieces, BigDecimal paidQty) {
        if (pieces.isEmpty() || paidQty == null || paidQty.signum() <= 0) return null;
        BigDecimal sum = BigDecimal.ZERO, qty = BigDecimal.ZERO;
        for (Piece p : pieces) {
            if (p.unitCost() == null) return null;
            sum = sum.add(p.unitCost().multiply(p.quantity()));
            qty = qty.add(p.quantity());
        }
        return qty.signum() == 0 ? null : sum.divide(qty, 6, RoundingMode.HALF_UP);
    }

    private static String label(List<Piece> pieces) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Piece p : pieces) if (p.batchNo() != null && !p.batchNo().isBlank()) seen.put(p.batchNo(), true);
        return seen.isEmpty() ? null : "Batch " + String.join(", ", seen.keySet());
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return Objects.equals(a, b);
        return a.compareTo(b) == 0;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
