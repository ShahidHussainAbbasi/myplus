package com.myplus.marketplace.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OMS O5c — how much of an order can be filled now, and how much is owed.
 *
 * <h3>Why this is pure arithmetic in its own class</h3>
 * It decides what gets invoiced. Getting it wrong in either direction is expensive — invoice too much and the
 * books recognise undelivered revenue; invoice too little and the shop under-charges for goods it shipped. So
 * it is separated from the HTTP and persistence around it and pinned by {@code BackorderSplitTest}.
 *
 * <h3>The shortfall never touches inventory</h3>
 * Backordered units live on the ORDER, uninvoiced. Inventory is told nothing about them, so stock never goes
 * negative and no phantom reservation exists. That is what lets O1's "record the sale at placement" stay true:
 * the sale is recorded for what can be filled, and the remainder is invoiced when it ships.
 */
public final class BackorderSplit {

    private BackorderSplit() {}

    /** One line's decision: how many to invoice now, how many to owe. */
    public record LineSplit(Long productId, int requested, int fillNow, int backordered) {
        public boolean hasShortfall() { return backordered > 0; }
    }

    /** The whole order's decision. */
    public record Result(List<LineSplit> lines, int totalFillNow, int totalBackordered) {
        /** Nothing at all can be filled — the order is worth accepting only as a pure backorder. */
        public boolean nothingAvailable() { return totalFillNow == 0; }
        public boolean hasBackorder() { return totalBackordered > 0; }
    }

    /**
     * Split requested quantities against what is sellable.
     *
     * @param requested  productId → quantity the shopper wants
     * @param sellable   productId → sellable quantity (NOT on-hand: on-hand includes expired batches and stock
     *                   held by a checkout in flight, so measuring against it would promise unpickable goods)
     *
     * <p>Availability is consumed across duplicate lines for the same product: two lines of 6 against 8
     * sellable fill 6 and 2, not 6 and 6. Without that, an order could be invoiced for more than exists.
     */
    public static Result split(Map<Long, Integer> requested, Map<Long, Float> sellable) {
        List<LineSplit> out = new ArrayList<>();
        Map<Long, Integer> remaining = new LinkedHashMap<>();
        int fill = 0, back = 0;

        for (Map.Entry<Long, Integer> e : requested.entrySet()) {
            Long pid = e.getKey();
            int want = Math.max(0, e.getValue() == null ? 0 : e.getValue());

            int avail = remaining.computeIfAbsent(pid, k -> {
                Float s = sellable == null ? null : sellable.get(k);
                // Floor at zero: a negative sellable (an inconsistency elsewhere) must not become negative
                // backorder arithmetic. Truncate rather than round — half a unit cannot be picked.
                return s == null ? 0 : Math.max(0, (int) Math.floor(s));
            });

            int now = Math.min(want, avail);
            remaining.put(pid, avail - now);
            out.add(new LineSplit(pid, want, now, want - now));
            fill += now;
            back += want - now;
        }
        return new Result(out, fill, back);
    }
}
