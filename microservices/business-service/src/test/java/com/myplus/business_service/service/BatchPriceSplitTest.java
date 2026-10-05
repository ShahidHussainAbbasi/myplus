package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.commerce.contracts.dto.StockPick;

import org.junit.jupiter.api.Test;

/** PR-3c — the pure arithmetic of a line priced from its batches. */
class BatchPriceSplitTest {

    private static StockPick pick(long entry, String batch, String qty, String price, String cost) {
        return new StockPick(1L, batch, new BigDecimal(qty), null, cost == null ? null : new BigDecimal(cost), 0, entry,
                price == null ? null : new BigDecimal(price));
    }

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void two_prices_make_two_parts_in_pick_order() {
        var parts = BatchPriceSplit.split(d("10"), null, true,
                List.of(pick(1, "A", "7", "200", "150"), pick(2, "B", "3", "250", "210")), d("250"));
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).paidQuantity()).isEqualByComparingTo("7");
        assertThat(parts.get(0).rate()).isEqualByComparingTo("200");
        assertThat(parts.get(0).unitCost()).isEqualByComparingTo("150");
        assertThat(parts.get(0).batchLabel()).isEqualTo("Batch A");
        assertThat(parts.get(1).paidQuantity()).isEqualByComparingTo("3");
        assertThat(parts.get(1).rate()).isEqualByComparingTo("250");
    }

    @Test
    void same_price_batches_stay_one_part_with_a_weighted_cost() {
        var parts = BatchPriceSplit.split(d("4"), null, true,
                List.of(pick(1, "A", "2", "200", "100"), pick(2, "B", "2", "200.00", "120")), d("250"));
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).paidQuantity()).isEqualByComparingTo("4");
        assertThat(parts.get(0).unitCost()).isEqualByComparingTo("110");
        assertThat(parts.get(0).pieces()).hasSize(2);
        assertThat(parts.get(0).batchLabel()).isEqualTo("Batch A, B");
    }

    @Test
    void a_batch_without_a_price_uses_the_product_price_and_merges_with_an_equal_one() {
        var parts = BatchPriceSplit.split(d("5"), null, true,
                List.of(pick(1, "OLD", "2", null, "100"), pick(2, "NEW", "3", "250", "120")), d("250"));
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).rate()).isEqualByComparingTo("250");
    }

    @Test
    void the_bonus_is_taken_after_the_paid_units_and_rides_on_the_last_part() {
        var parts = BatchPriceSplit.split(d("8"), d("2"), true,
                List.of(pick(1, "A", "7", "200", "150"), pick(2, "B", "3", "250", "210")), d("250"));
        assertThat(parts).hasSize(2);
        assertThat(parts.get(1).paidQuantity()).isEqualByComparingTo("1");
        assertThat(parts.get(1).bonus()).isEqualByComparingTo("2");
        BigDecimal pieces = parts.get(1).pieces().stream().map(BatchPriceSplit.Piece::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pieces).as("1 paid + 2 free from B").isEqualByComparingTo("3");
        assertThat(parts.get(1).unitCost()).as("cost of the PAID unit only").isEqualByComparingTo("210");
    }

    @Test
    void an_ineligible_line_is_one_unpriced_part_carrying_every_pick() {
        var parts = BatchPriceSplit.split(d("10"), null, false,
                List.of(pick(1, "A", "7", "200", "150"), pick(2, "B", "3", "250", "210")), d("250"));
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).rate()).isNull();
        assertThat(parts.get(0).pieces()).hasSize(2);
    }

    @Test
    void an_unknown_cost_is_never_guessed() {
        var parts = BatchPriceSplit.split(d("4"), null, true,
                List.of(pick(1, "A", "2", "200", null), pick(2, "B", "2", "200", "120")), d("250"));
        assertThat(parts.get(0).unitCost()).isNull();
    }

    @Test
    void a_discount_is_shared_to_the_paisa_and_adds_back_exactly() {
        var shares = BatchPriceSplit.shareDiscount(d("100"), List.of(d("1400"), d("750")));
        assertThat(shares.get(0)).isEqualByComparingTo("65.12");
        assertThat(shares.get(1)).isEqualByComparingTo("34.88");
        var thirds = BatchPriceSplit.shareDiscount(d("10"), List.of(d("1"), d("1"), d("1")));
        assertThat(thirds.get(0).add(thirds.get(1)).add(thirds.get(2))).isEqualByComparingTo("10");
    }
}
