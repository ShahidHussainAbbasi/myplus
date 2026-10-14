package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

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
 *
 * <p>NUM-1 (2026-10-14): exact {@code BigDecimal} at the column's scale 4 — the float version answered 0.075000003.
 */
class LooseReturnQuantityTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("⭐ the user's case: 10 tablets of a 40-box, stored as 0.25 — returning all 10 gives back 0.25")
    void returningEveryPieceGivesBackTheStoredQuantity() {
        assertThat(SellController.packsForLooseReturn(d("0.25"), d("10"), d("10"))).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("⭐ half the tablets return half the shelf quantity")
    void aPartialReturnIsProportional() {
        assertThat(SellController.packsForLooseReturn(d("0.25"), d("10"), d("5"))).isEqualByComparingTo("0.125");
        assertThat(SellController.packsForLooseReturn(d("0.5"), d("5"), d("3"))).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("⭐⭐ NUM-1: 3 of 10 tablets from 0.25 of a box is EXACTLY 0.075 — the float gave 0.075000003")
    void aLooseShareIsExact() {
        assertThat(SellController.packsForLooseReturn(d("0.25"), d("10"), d("3"))).isEqualByComparingTo("0.075");
    }

    @Test
    @DisplayName("⭐⭐ a pack that does not divide cleanly still closes EXACTLY on a full return")
    void anAwkwardPackSizeStillCloses() {
        /*
         * A box of 3, one tablet: the sale stored 0.3333 (DECIMAL(19,4)). pieces / packSize here would be computed
         * again — and the line would be left holding a sliver of a box that no return could clear, while "cannot
         * return more than the sold quantity" refused the difference.
         */
        BigDecimal stored = d("0.3333");
        assertThat(SellController.packsForLooseReturn(stored, d("1"), d("1")))
                .as("returning the only piece returns the stored figure itself")
                .isEqualByComparingTo(stored);
        assertThat(SellController.packsForLooseReturn(d("0.6667"), d("2"), d("2"))).isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("more pieces than were sold is capped at the whole line — the caller validates, this cannot overshoot")
    void neverMoreThanTheLine() {
        assertThat(SellController.packsForLooseReturn(d("0.25"), d("10"), d("11"))).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("a line with no pieces recorded converts to nothing rather than dividing by zero")
    void noPiecesMeansNoConversion() {
        assertThat(SellController.packsForLooseReturn(d("0.25"), d("0"), d("5"))).isZero();
    }

    @Test
    @DisplayName("the refusal message reads '10', not '10.0' or '10.0000' — a person is standing at the counter")
    void quantitiesReadLikeQuantities() {
        assertThat(SellController.fmtPieces(d("10"))).isEqualTo("10");
        assertThat(SellController.fmtPieces(d("10.0000"))).isEqualTo("10");
        assertThat(SellController.fmtPieces(d("2.5"))).isEqualTo("2.5");
    }
}
