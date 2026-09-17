package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U13 — turning "10 tablets" into what leaves the shelf.
 *
 * <p>A loose line stores both views of one sale: {@code quantity} = 0.25 of a box, {@code soldQuantity} = 10 tablets.
 * The Sale Return dialog now asks in tablets, and the SERVER converts — so this is the arithmetic that decides how
 * much stock goes back and how much money is refunded. Pure, no Spring, no database.
 *
 * <p>The rule under test: a proportion of what the sale STORED, never {@code pieces / packSize}. The sale derived
 * the shelf figure once; deriving it a second time here would disagree with it whenever the division does not
 * terminate, and the invoice would keep a sliver of a box nobody can return.
 */
class LooseReturnQuantityTest {

    @Test
    @DisplayName("⭐ the user's case: 10 tablets of a 40-box, stored as 0.25 — returning all 10 gives back 0.25")
    void returningEveryPieceGivesBackTheStoredQuantity() {
        assertThat(SellController.packsForLooseReturn(0.25f, 10f, 10f)).isEqualTo(0.25f);
    }

    @Test
    @DisplayName("⭐ half the tablets return half the shelf quantity")
    void aPartialReturnIsProportional() {
        assertThat(SellController.packsForLooseReturn(0.25f, 10f, 5f)).isEqualTo(0.125f);
        assertThat(SellController.packsForLooseReturn(0.5f, 5f, 3f)).isEqualTo(0.3f);
    }

    @Test
    @DisplayName("⭐⭐ a pack that does not divide cleanly still closes EXACTLY on a full return")
    void anAwkwardPackSizeStillCloses() {
        /*
         * A box of 3, one tablet: the sale stored 0.3333333. pieces / packSize here would give 0.33333334 — a
         * different float — and the line would be left holding a sliver of a box that no return could clear,
         * while "cannot return more than the sold quantity" refused the difference.
         */
        float stored = 1f / 3f;
        assertThat(SellController.packsForLooseReturn(stored, 1f, 1f))
                .as("returning the only piece returns the stored figure itself")
                .isEqualTo(stored);
        assertThat(SellController.packsForLooseReturn(0.6666667f, 2f, 2f)).isEqualTo(0.6666667f);
    }

    @Test
    @DisplayName("more pieces than were sold is capped at the whole line — the caller validates, this cannot overshoot")
    void neverMoreThanTheLine() {
        assertThat(SellController.packsForLooseReturn(0.25f, 10f, 11f)).isEqualTo(0.25f);
    }

    @Test
    @DisplayName("a line with no pieces recorded converts to nothing rather than dividing by zero")
    void noPiecesMeansNoConversion() {
        assertThat(SellController.packsForLooseReturn(0.25f, 0f, 5f)).isZero();
    }

    @Test
    @DisplayName("the refusal message reads '10', not '10.0' — a person is standing at the counter")
    void quantitiesReadLikeQuantities() {
        assertThat(SellController.fmtPieces(10f)).isEqualTo("10");
        assertThat(SellController.fmtPieces(2.5f)).isEqualTo("2.5");
    }
}
