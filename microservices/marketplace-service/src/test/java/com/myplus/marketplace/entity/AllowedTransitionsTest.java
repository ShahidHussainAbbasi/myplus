package com.myplus.marketplace.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OMS O4 — the lifecycle whitelist, published to the client.
 *
 * <p>Before O4 the browser kept its OWN copy of these rules ({@code NEXT} in {@code ecommerce.js}) and the two
 * had already drifted. This class exists to keep the published list and the enforced list the same thing: every
 * case here compares {@link FulfilmentStatus#allowedTransitions()} against {@link FulfilmentStatus#canMoveTo},
 * so a future edit to one that misses the other fails here rather than in a merchant's back office.
 */
class AllowedTransitionsTest {

    @Test
    @DisplayName("what is PUBLISHED is exactly what is ENFORCED, for every state")
    void publishedMatchesEnforced() {
        for (FulfilmentStatus from : FulfilmentStatus.values()) {
            for (FulfilmentStatus to : FulfilmentStatus.values()) {
                assertThat(from.allowedTransitions().contains(to))
                        .as("%s -> %s: the button the UI draws and the move the server permits must agree", from, to)
                        .isEqualTo(from.canMoveTo(to));
            }
        }
    }

    @Test
    @DisplayName("SHIPPED never offers CANCELLED — the phantom button O4 removed")
    void shippedNeverOffersCancel() {
        // The old UI showed Cancel on any order that was not CANCELLED or DELIVERED, which included SHIPPED.
        // The server refuses that move (goods on a van are not back on the shelf), so the operator clicked a
        // button and got a 409. If this ever passes again, that defect is back.
        assertThat(FulfilmentStatus.SHIPPED.allowedTransitions())
                .doesNotContain(FulfilmentStatus.CANCELLED)
                .containsExactly(FulfilmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("RETURN_REQUESTED is actionable — it used to render as inert text")
    void returnRequestedIsActionable() {
        // Absent from the old NEXT map entirely, so a customer's return request landed in a state the back
        // office was offered no way to act on.
        assertThat(FulfilmentStatus.RETURN_REQUESTED.allowedTransitions())
                .isNotEmpty()
                .containsExactly(FulfilmentStatus.RETURNED);
    }

    @Test
    @DisplayName("a terminal order offers nothing, and says so with an empty list rather than null")
    void terminalStatesOfferNothing() {
        for (FulfilmentStatus s : new FulfilmentStatus[] { FulfilmentStatus.CANCELLED, FulfilmentStatus.RETURNED }) {
            assertThat(s.isTerminal()).isTrue();
            assertThat(s.allowedTransitions()).isEmpty();
            // Empty, never null — the client renders straight from this without a guard.
            assertThat(s.allowedTransitionNames()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("the forward path a packer actually walks is offered at each step")
    void forwardPathIsOffered() {
        assertThat(FulfilmentStatus.NEW.allowedTransitions())
                .containsExactly(FulfilmentStatus.PACKED, FulfilmentStatus.CANCELLED);
        // OMS O5b: SHIPPED left the manual set — it is derived from recorded parcels now, so the back office
        // draws a Ship action instead of a "Mark SHIPPED" button. Cancelling is still offered while nothing
        // has gone out.
        assertThat(FulfilmentStatus.PACKED.allowedTransitions())
                .containsExactly(FulfilmentStatus.CANCELLED);
        assertThat(FulfilmentStatus.DELIVERED.allowedTransitions())
                .containsExactly(FulfilmentStatus.RETURN_REQUESTED, FulfilmentStatus.RETURNED);
    }

    @Test
    @DisplayName("the names are what the browser gets, in lifecycle order")
    void namesAreOrdered() {
        // EnumSet iterates in declaration order, so PACKED comes before CANCELLED and the buttons render in a
        // sensible order without the client sorting anything.
        assertThat(FulfilmentStatus.NEW.allowedTransitionNames()).containsExactly("PACKED", "CANCELLED");
    }

    @Test
    @DisplayName("the published set cannot be edited by a caller")
    void publishedSetIsImmutable() {
        // It is derived from the authoritative map; handing out a mutable view would let one caller's mistake
        // change what every later caller is allowed to do.
        assertThatThrownBy(() -> FulfilmentStatus.NEW.allowedTransitions().add(FulfilmentStatus.SHIPPED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
