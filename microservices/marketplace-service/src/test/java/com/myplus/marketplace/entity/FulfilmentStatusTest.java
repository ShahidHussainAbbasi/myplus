package com.myplus.marketplace.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OMS O2 — the order state machine.
 *
 * <p>Pure logic: no Spring, no database, no Docker, so it runs on every {@code mvn test}. That matters here
 * because the Testcontainers suites SKIP on the dev machine — without this the transition rules would have no
 * executed coverage at all.
 *
 * <p>Before O2 {@code updateStatus} accepted ANY transition. Each case below is a move that was possible then
 * and is a real-world failure: shipping a cancelled order, reviving a completed return, cancelling goods already
 * on a van.
 */
class FulfilmentStatusTest {

    // ── the happy path ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the normal fulfilment path is legal end to end")
    void forwardPathIsLegal() {
        assertThat(FulfilmentStatus.NEW.canMoveTo(FulfilmentStatus.PACKED)).isTrue();
        assertThat(FulfilmentStatus.PACKED.canMoveTo(FulfilmentStatus.SHIPPED)).isTrue();
        assertThat(FulfilmentStatus.SHIPPED.canMoveTo(FulfilmentStatus.DELIVERED)).isTrue();
        assertThat(FulfilmentStatus.DELIVERED.canMoveTo(FulfilmentStatus.RETURN_REQUESTED)).isTrue();
        assertThat(FulfilmentStatus.RETURN_REQUESTED.canMoveTo(FulfilmentStatus.RETURNED)).isTrue();
    }

    @Test
    @DisplayName("an order can be cancelled before it ships, but not after")
    void cancellationWindow() {
        assertThat(FulfilmentStatus.NEW.canMoveTo(FulfilmentStatus.CANCELLED)).isTrue();
        assertThat(FulfilmentStatus.PACKED.canMoveTo(FulfilmentStatus.CANCELLED)).isTrue();
        // Cancelling triggers the O1 void, which returns stock. Goods on a van are NOT back on the shelf, so
        // allowing this would inflate on-hand by whatever is in transit. A failed delivery goes
        // SHIPPED → DELIVERED → RETURNED, which restores stock only when it physically arrives.
        assertThat(FulfilmentStatus.SHIPPED.canMoveTo(FulfilmentStatus.CANCELLED)).isFalse();
    }

    // ── the moves that used to be possible ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a CANCELLED order can never ship — its money and stock are already reversed")
    void cancelledCannotShip() {
        assertThat(FulfilmentStatus.CANCELLED.canMoveTo(FulfilmentStatus.SHIPPED)).isFalse();
        assertThat(FulfilmentStatus.CANCELLED.canMoveTo(FulfilmentStatus.PACKED)).isFalse();
        assertThat(FulfilmentStatus.CANCELLED.canMoveTo(FulfilmentStatus.DELIVERED)).isFalse();
    }

    @Test
    @DisplayName("a RETURNED order is finished — it cannot be re-delivered or re-returned")
    void returnedIsTerminal() {
        assertThat(FulfilmentStatus.RETURNED.canMoveTo(FulfilmentStatus.DELIVERED)).isFalse();
        assertThat(FulfilmentStatus.RETURNED.canMoveTo(FulfilmentStatus.RETURN_REQUESTED)).isFalse();
        assertThat(FulfilmentStatus.RETURNED.isTerminal()).isTrue();
        assertThat(FulfilmentStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("an order cannot skip fulfilment steps — DELIVERED must be reached, not declared")
    void noSkipping() {
        assertThat(FulfilmentStatus.NEW.canMoveTo(FulfilmentStatus.DELIVERED)).isFalse();
        assertThat(FulfilmentStatus.NEW.canMoveTo(FulfilmentStatus.SHIPPED)).isFalse();
        assertThat(FulfilmentStatus.PACKED.canMoveTo(FulfilmentStatus.DELIVERED)).isFalse();
    }

    @Test
    @DisplayName("fulfilment does not run backwards")
    void noReversing() {
        assertThat(FulfilmentStatus.DELIVERED.canMoveTo(FulfilmentStatus.SHIPPED)).isFalse();
        assertThat(FulfilmentStatus.SHIPPED.canMoveTo(FulfilmentStatus.PACKED)).isFalse();
        assertThat(FulfilmentStatus.PACKED.canMoveTo(FulfilmentStatus.NEW)).isFalse();
    }

    @Test
    @DisplayName("a return can only be raised against something delivered")
    void returnNeedsDelivery() {
        assertThat(FulfilmentStatus.NEW.canMoveTo(FulfilmentStatus.RETURN_REQUESTED)).isFalse();
        assertThat(FulfilmentStatus.PACKED.canMoveTo(FulfilmentStatus.RETURNED)).isFalse();
        assertThat(FulfilmentStatus.SHIPPED.canMoveTo(FulfilmentStatus.RETURNED)).isFalse();
    }

    @Test
    @DisplayName("null target is refused rather than throwing")
    void nullTargetIsRefused() {
        assertThat(FulfilmentStatus.NEW.canMoveTo(null)).isFalse();
    }

    // ── the authority split ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only CANCELLED and RETURNED count as reversals — they are what the admin gate protects")
    void reversalsAreTheGatedMoves() {
        // These reverse money and stock (they drive the O1 void), so they carry the same gate as /refund.
        assertThat(FulfilmentStatus.CANCELLED.isReversal()).isTrue();
        assertThat(FulfilmentStatus.RETURNED.isReversal()).isTrue();
        // Forward fulfilment is shop-floor work — gating it would put the check where the risk is not.
        assertThat(FulfilmentStatus.PACKED.isReversal()).isFalse();
        assertThat(FulfilmentStatus.SHIPPED.isReversal()).isFalse();
        assertThat(FulfilmentStatus.DELIVERED.isReversal()).isFalse();
        assertThat(FulfilmentStatus.RETURN_REQUESTED.isReversal()).isFalse();
    }
}
