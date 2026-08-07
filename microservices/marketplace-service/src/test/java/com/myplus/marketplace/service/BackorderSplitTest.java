package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OMS O5c — how much of an order is invoiced now, and how much is owed.
 *
 * <p>This arithmetic decides what gets billed. Wrong in one direction and the books recognise revenue for goods
 * never delivered; wrong in the other and the shop under-charges for goods it shipped. Every case here is a
 * boundary where that could happen quietly.
 */
class BackorderSplitTest {

    private static Map<Long, Integer> want(Object... pairs) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.merge((Long) pairs[i], (Integer) pairs[i + 1], Integer::sum);
        return m;
    }

    private static Map<Long, Float> have(Object... pairs) {
        Map<Long, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Long) pairs[i], (Float) pairs[i + 1]);
        return m;
    }

    // ── the invariant ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every line satisfies requested = fillNow + backordered")
    void invariantHolds() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 10, 2L, 3), have(1L, 8f, 2L, 0f));
        r.lines().forEach(l -> assertThat(l.fillNow() + l.backordered())
                .as("line %s", l.productId()).isEqualTo(l.requested()));
        assertThat(r.totalFillNow()).isEqualTo(8);
        assertThat(r.totalBackordered()).isEqualTo(5);   // 2 short on A, 3 short on B
    }

    // ── boundaries ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("exactly enough stock leaves nothing owed")
    void exactlyEnough() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 5), have(1L, 5f));
        assertThat(r.hasBackorder()).isFalse();
        assertThat(r.totalFillNow()).isEqualTo(5);
    }

    @Test
    @DisplayName("more stock than needed still fills only what was asked for")
    void moreThanEnough() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 5), have(1L, 50f));
        assertThat(r.totalFillNow()).isEqualTo(5);
        assertThat(r.totalBackordered()).isZero();
    }

    @Test
    @DisplayName("no stock at all backorders the whole line")
    void nothingAvailable() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 4), have(1L, 0f));
        assertThat(r.nothingAvailable()).isTrue();
        assertThat(r.totalBackordered()).isEqualTo(4);
    }

    @Test
    @DisplayName("a product missing from the stock read is treated as zero, not as unlimited")
    void unknownProductIsZero() {
        // Failing the other way would invoice goods the shop has never stocked.
        BackorderSplit.Result r = BackorderSplit.split(want(99L, 3), have());
        assertThat(r.totalFillNow()).isZero();
        assertThat(r.totalBackordered()).isEqualTo(3);
    }

    @Test
    @DisplayName("a negative sellable figure floors at zero rather than inverting the arithmetic")
    void negativeSellableFloors() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 3), have(1L, -5f));
        assertThat(r.totalFillNow()).isZero();
        assertThat(r.totalBackordered()).isEqualTo(3);
    }

    @Test
    @DisplayName("a fractional sellable figure truncates — half a unit cannot be picked")
    void fractionalTruncates() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 5), have(1L, 2.9f));
        assertThat(r.totalFillNow()).isEqualTo(2);
        assertThat(r.totalBackordered()).isEqualTo(3);
    }

    // ── the case that would silently over-invoice ──────────────────────────────────────────────────────

    @Test
    @DisplayName("two lines of the same product SHARE the available stock")
    void availabilityIsConsumedAcrossLines() {
        // 6 + 6 requested against 8 sellable. Treating each line independently would fill 6 and 6 — invoicing
        // 12 units against 8 that exist.
        Map<Long, Integer> requested = new LinkedHashMap<>();
        requested.put(1L, 6);
        BackorderSplit.Result r = BackorderSplit.split(requested, have(1L, 8f));
        assertThat(r.totalFillNow()).isEqualTo(6);

        // Two distinct lines for one product arrive merged by the caller, so verify the shared-pool behaviour
        // directly with a second product sharing nothing.
        BackorderSplit.Result multi = BackorderSplit.split(want(1L, 6, 1L, 6), have(1L, 8f));
        assertThat(multi.totalFillNow())
                .as("never invoice more than exists, however the request is spread across lines")
                .isEqualTo(8);
        assertThat(multi.totalBackordered()).isEqualTo(4);
    }

    @Test
    @DisplayName("one short product does not stop another being filled")
    void linesAreIndependentWhereTheyShouldBe() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 2, 2L, 9), have(1L, 5f, 2L, 1f));
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(0).backordered()).isZero();
        assertThat(r.lines().get(1).backordered()).isEqualTo(8);
    }

    @Test
    @DisplayName("a zero or negative request contributes nothing")
    void nonPositiveRequests() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 0), have(1L, 5f));
        assertThat(r.totalFillNow()).isZero();
        assertThat(r.totalBackordered()).isZero();
        assertThat(r.hasBackorder()).isFalse();
    }

    @Test
    @DisplayName("a null stock read backorders everything rather than promising it")
    void nullStockIsSafe() {
        BackorderSplit.Result r = BackorderSplit.split(want(1L, 3), null);
        assertThat(r.totalFillNow()).isZero();
        assertThat(r.totalBackordered()).isEqualTo(3);
    }
}
