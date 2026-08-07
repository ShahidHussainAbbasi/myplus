package com.myplus.marketplace.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OMS O5b — the header status derived from line quantities.
 *
 * <p>The point of deriving it is that a partly-shipped order cannot disagree with its own lines. These cases
 * pin the boundaries where a header could start lying: nothing shipped, the last unit shipped, and a decision
 * (DELIVERED, CANCELLED, RETURNED) that a later projection must not quietly undo.
 */
class FulfilmentProjectionTest {

    @Test
    @DisplayName("nothing shipped yields NO projection — NEW and PACKED are the caller's business")
    void nothingShippedProjectsNothing() {
        // Null, not NEW: an order that has shipped nothing may legitimately be NEW or PACKED, and the
        // quantities cannot tell which. Returning NEW here would silently un-pack a packed order.
        assertThat(FulfilmentStatus.project(5, 0)).isNull();
        assertThat(FulfilmentStatus.project(0, 0)).isNull();
    }

    @Test
    @DisplayName("some shipped is PARTIALLY_SHIPPED")
    void someShipped() {
        assertThat(FulfilmentStatus.project(5, 1)).isEqualTo(FulfilmentStatus.PARTIALLY_SHIPPED);
        assertThat(FulfilmentStatus.project(5, 4)).isEqualTo(FulfilmentStatus.PARTIALLY_SHIPPED);
    }

    @Test
    @DisplayName("the last unit flips it to SHIPPED")
    void allShipped() {
        assertThat(FulfilmentStatus.project(5, 5)).isEqualTo(FulfilmentStatus.SHIPPED);
        assertThat(FulfilmentStatus.project(1, 1)).isEqualTo(FulfilmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("over-shipping still reads SHIPPED rather than something impossible")
    void overShipped() {
        // The service refuses to ship more than is outstanding, so this should be unreachable — but if some
        // future path lets it through, the header must degrade to SHIPPED, not to PARTIALLY_SHIPPED, which
        // would leave a fully-dispatched order looking like it still owed the customer goods.
        assertThat(FulfilmentStatus.project(5, 6)).isEqualTo(FulfilmentStatus.SHIPPED);
    }

    // ── OMS O5c: what is OWED changes the projection ───────────────────────────────────────────────────

    @Test
    @DisplayName("nothing shipped but something owed is BACKORDERED")
    void nothingShippedButOwed() {
        assertThat(FulfilmentStatus.project(10, 0, 2)).isEqualTo(FulfilmentStatus.BACKORDERED);
    }

    @Test
    @DisplayName("an order is NOT SHIPPED while anything is still owed")
    void neverShippedWhileOwed() {
        // 8 of 10 invoiced and all 8 dispatched — but 2 are still owed. Calling that SHIPPED would mark
        // complete an order the shop still has to deliver, and the waiting customer would vanish from
        // every report.
        assertThat(FulfilmentStatus.project(10, 8, 2)).isEqualTo(FulfilmentStatus.PARTIALLY_SHIPPED);
    }

    @Test
    @DisplayName("once the shortfall is invoiced and dispatched the order completes")
    void completesWhenNothingIsOwed() {
        assertThat(FulfilmentStatus.project(10, 10, 0)).isEqualTo(FulfilmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("a backordered order is still cancellable — nothing has left the building")
    void backorderedIsCancellable() {
        assertThat(FulfilmentStatus.BACKORDERED.allowedTransitions())
                .containsExactly(FulfilmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("BACKORDERED is derived too — it cannot be typed")
    void backorderedIsDerived() {
        assertThat(FulfilmentStatus.BACKORDERED.isDerived()).isTrue();
    }

    // ── which states are derived, and which are decisions ──────────────────────────────────────────────

    @Test
    @DisplayName("only the shipping states are derived; the rest are decisions")
    void derivedStates() {
        assertThat(FulfilmentStatus.PARTIALLY_SHIPPED.isDerived()).isTrue();
        assertThat(FulfilmentStatus.SHIPPED.isDerived()).isTrue();
        for (FulfilmentStatus s : new FulfilmentStatus[] { FulfilmentStatus.NEW, FulfilmentStatus.PACKED,
                FulfilmentStatus.DELIVERED, FulfilmentStatus.CANCELLED,
                FulfilmentStatus.RETURN_REQUESTED, FulfilmentStatus.RETURNED }) {
            assertThat(s.isDerived()).as("%s is a decision someone makes", s).isFalse();
        }
    }

    // ── the whitelist, after O5b ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a part-shipped order cannot be CANCELLED — goods on a van are not back on the shelf")
    void partiallyShippedCannotBeCancelled() {
        // Same reason SHIPPED has no path to CANCELLED (O2): cancelling triggers the O1 void, which returns
        // stock. A part-shipped order that fails goes DELIVERED -> RETURNED, putting stock back when it
        // physically arrives.
        assertThat(FulfilmentStatus.PARTIALLY_SHIPPED.allowedTransitions())
                .doesNotContain(FulfilmentStatus.CANCELLED)
                .containsExactly(FulfilmentStatus.DELIVERED);
        assertThat(FulfilmentStatus.SHIPPED.allowedTransitions())
                .doesNotContain(FulfilmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("no manual path leads INTO a derived state")
    void noManualPathIntoDerivedStates() {
        // If any state offered SHIPPED as a move, the back office would draw a button for it and the header
        // could advance with no parcel behind it — exactly the disagreement the projection prevents.
        for (FulfilmentStatus from : FulfilmentStatus.values()) {
            assertThat(from.allowedTransitions().stream().noneMatch(FulfilmentStatus::isDerived))
                    .as("%s must not offer a derived state as a manual move: %s", from, from.allowedTransitions())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("PACKED still leads somewhere — it just no longer leads to SHIPPED")
    void packedStillCancellable() {
        assertThat(FulfilmentStatus.PACKED.allowedTransitions())
                .containsExactly(FulfilmentStatus.CANCELLED);
        assertThat(FulfilmentStatus.NEW.allowedTransitions())
                .containsExactly(FulfilmentStatus.PACKED, FulfilmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("what is published still equals what is enforced, for every pair")
    void publishedMatchesEnforced() {
        // O4's invariant, re-checked now that a value has been added to the enum.
        for (FulfilmentStatus from : FulfilmentStatus.values())
            for (FulfilmentStatus to : FulfilmentStatus.values())
                assertThat(from.allowedTransitions().contains(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(from.canMoveTo(to));
    }
}
