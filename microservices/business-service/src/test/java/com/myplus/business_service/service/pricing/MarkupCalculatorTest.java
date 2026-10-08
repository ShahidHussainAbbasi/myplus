package com.myplus.business_service.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-2 — the arithmetic, with the worked example the owner asked for (14.5% on a cost of 100). */
class MarkupCalculatorTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("markup on cost: 100 at 14.5% → 114.50")
    void markup_on_cost() {
        assertThat(MarkupCalculator.price(d("100"), d("14.5"), "markup", "exact")).isEqualByComparingTo("114.50");
    }

    @Test
    @DisplayName("margin of the price: 100 at 14.5% → 116.96 (100 ÷ 0.855 = 116.959…)")
    void margin_of_price() {
        assertThat(MarkupCalculator.price(d("100"), d("14.5"), "margin", "exact")).isEqualByComparingTo("116.96");
    }

    @Test
    @DisplayName("rounding: up to the next rupee never goes below the rule; nearest 5 / 10 are shelf prices")
    void rounding() {
        assertThat(MarkupCalculator.round(d("114.50"), "up1")).isEqualByComparingTo("115");
        assertThat(MarkupCalculator.round(d("114.00"), "up1")).isEqualByComparingTo("114");   // already whole
        assertThat(MarkupCalculator.round(d("114.50"), "near5")).isEqualByComparingTo("115");
        assertThat(MarkupCalculator.round(d("112.49"), "near5")).isEqualByComparingTo("110");
        assertThat(MarkupCalculator.round(d("114.50"), "near10")).isEqualByComparingTo("110");
        assertThat(MarkupCalculator.round(d("115.00"), "near10")).isEqualByComparingTo("120");   // half up
        assertThat(MarkupCalculator.round(d("114.505"), "exact")).isEqualByComparingTo("114.51");
        assertThat(MarkupCalculator.round(d("114.505"), "bogus")).isEqualByComparingTo("114.51");   // unknown = exact
    }

    @Test
    @DisplayName("nothing to compute: no cost, zero cost, no % or 0% → null, never a price of 0")
    void nothing_to_compute() {
        assertThat(MarkupCalculator.price(null, d("10"), "markup", "exact")).isNull();
        assertThat(MarkupCalculator.price(d("0"), d("10"), "markup", "exact")).isNull();
        assertThat(MarkupCalculator.price(d("100"), null, "markup", "exact")).isNull();
        assertThat(MarkupCalculator.price(d("100"), d("0"), "markup", "exact")).isNull();
    }

    @Test
    @DisplayName("a margin of 100% or more has no price — refused, never a division by zero or a negative price")
    void impossible_margin() {
        assertThatThrownBy(() -> MarkupCalculator.raw(d("100"), d("100"), "margin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarkupCalculator.raw(d("100"), d("150"), "margin")).isInstanceOf(IllegalArgumentException.class);
        // the same percentage as a MARKUP is an ordinary price
        assertThat(MarkupCalculator.price(d("100"), d("150"), "markup", "exact")).isEqualByComparingTo("250.00");
    }
}
