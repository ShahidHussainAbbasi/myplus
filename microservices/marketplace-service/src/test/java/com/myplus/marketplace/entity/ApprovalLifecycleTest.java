package com.myplus.marketplace.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OMS O7 D1 — the approval phase in front of the fulfilment lifecycle.
 *
 * <p>Pure logic, no Spring, so it runs on every {@code mvn test}. The whitelist is the only thing standing
 * between a booked order and the warehouse, so the cases that matter are the ones it must REFUSE.
 */
class ApprovalLifecycleTest {

    @Test
    @DisplayName("a booked order can be confirmed, rejected, or withdrawn — and nothing else")
    void pendingApprovalLeadsOnlyWhereItShould() {
        assertThat(FulfilmentStatus.PENDING_APPROVAL.allowedTransitions())
                .containsExactly(FulfilmentStatus.NEW, FulfilmentStatus.REJECTED, FulfilmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("a booked order can NEVER jump the review — not to packed, not to shipped, not to delivered")
    void pendingApprovalCannotSkipReview() {
        // This is the whole control. If any of these were legal, an order booker could push goods out of the
        // warehouse without anyone reviewing what they promised, at what price.
        assertThat(FulfilmentStatus.PENDING_APPROVAL.canMoveTo(FulfilmentStatus.PACKED)).isFalse();
        assertThat(FulfilmentStatus.PENDING_APPROVAL.canMoveTo(FulfilmentStatus.SHIPPED)).isFalse();
        assertThat(FulfilmentStatus.PENDING_APPROVAL.canMoveTo(FulfilmentStatus.DELIVERED)).isFalse();
        assertThat(FulfilmentStatus.PENDING_APPROVAL.canMoveTo(FulfilmentStatus.RETURNED)).isFalse();
    }

    @Test
    @DisplayName("a rejected order goes BACK for review, never straight to the floor")
    void rejectedReturnsToReview() {
        // D-2: rejection is not terminal — the booker revises and resubmits. But it must return to
        // PENDING_APPROVAL, not to NEW: a path REJECTED → NEW would make "reject" a one-click bypass of the
        // approval it exists to enforce.
        assertThat(FulfilmentStatus.REJECTED.allowedTransitions())
                .containsExactly(FulfilmentStatus.PENDING_APPROVAL, FulfilmentStatus.CANCELLED);
        assertThat(FulfilmentStatus.REJECTED.canMoveTo(FulfilmentStatus.NEW)).isFalse();
        assertThat(FulfilmentStatus.REJECTED.canMoveTo(FulfilmentStatus.PACKED)).isFalse();
    }

    @Test
    @DisplayName("rejection is not a reversal — it must not trigger the O1 void")
    void rejectionReversesNothing() {
        // CANCELLED and RETURNED reverse money and stock, so they carry the admin gate and run the void. A
        // rejected order never took stock or raised an invoice; treating it as a reversal would have it try to
        // void an invoice that does not exist.
        assertThat(FulfilmentStatus.REJECTED.isReversal()).isFalse();
        assertThat(FulfilmentStatus.REJECTED.isTerminal()).isFalse();
        assertThat(FulfilmentStatus.PENDING_APPROVAL.isReversal()).isFalse();
    }

    @Test
    @DisplayName("neither new state is DERIVED — both are decisions someone makes")
    void approvalStatesAreDecisions() {
        // O5b's rule: a derived state is reached by recording a shipment, never by being marked. Approval is
        // the opposite — it is precisely a human decision, so it must stay settable (through its own endpoint).
        assertThat(FulfilmentStatus.PENDING_APPROVAL.isDerived()).isFalse();
        assertThat(FulfilmentStatus.REJECTED.isDerived()).isFalse();
    }

    @Test
    @DisplayName("the existing lifecycle is untouched — POS and storefront orders still start at NEW")
    void existingLifecycleUnchanged() {
        // The approval phase is added IN FRONT of the old one. Orders with no approval step (POS, storefront)
        // are born NEW and behave exactly as they did before D1.
        assertThat(FulfilmentStatus.NEW.allowedTransitions())
                .containsExactly(FulfilmentStatus.PACKED, FulfilmentStatus.CANCELLED);
        assertThat(FulfilmentStatus.NEW.canMoveTo(FulfilmentStatus.PENDING_APPROVAL))
                .as("a confirmed order cannot fall back into the review queue")
                .isFalse();
    }

    @Test
    @DisplayName("the approval states come FIRST, so the UI draws them in lifecycle order")
    void declarationOrder() {
        // allowedTransitions() returns an EnumSet, which iterates in declaration order — that is what makes the
        // buttons render in a sensible sequence without the client sorting anything.
        assertThat(FulfilmentStatus.values()[0]).isEqualTo(FulfilmentStatus.PENDING_APPROVAL);
        // NEW before REJECTED: the buttons render Confirm, Reject, Cancel — affirmative action first.
        assertThat(FulfilmentStatus.values()[1]).isEqualTo(FulfilmentStatus.NEW);
        assertThat(FulfilmentStatus.values()[2]).isEqualTo(FulfilmentStatus.REJECTED);
        assertThat(FulfilmentStatus.PENDING_APPROVAL.allowedTransitionNames())
                .containsExactly("NEW", "REJECTED", "CANCELLED");
    }
}
