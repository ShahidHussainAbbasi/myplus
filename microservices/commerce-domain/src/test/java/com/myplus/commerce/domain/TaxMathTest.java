package com.myplus.commerce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one tax engine, pinned. Pure logic with no Spring, so it runs on every {@code mvn test}.
 *
 * <p>These cases are written from the DEFECT they exist to prevent: business-service and marketplace each had
 * their own copy of this arithmetic and the copies disagreed. Whichever service calls this now, the answers
 * below are the answers it gets.
 */
class TaxMathTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    // ── the tenant switch ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tax disabled: the whole line is net, whatever rate the product carries")
    void disabled_tenant_pays_no_tax() {
        // The storefront's private engine had no switch at all. A shop with tax off was quoted a tax line its
        // own invoice then contradicted — quoted 22, invoiced 20.
        TaxMath.TaxAmounts t = TaxMath.forLine(bd("20.00"), bd("10"), false, bd("17"), false);

        assertThat(t.net()).isEqualByComparingTo("20.00");
        assertThat(t.tax()).isEqualByComparingTo("0");
        assertThat(t.rate()).isEqualByComparingTo("0");
        assertThat(t.gross()).isEqualByComparingTo("20.00");
    }

    // ── rate resolution ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a product's own rate wins over the org default")
    void product_rate_wins() {
        assertThat(TaxMath.resolveRate(bd("5"), bd("17"))).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("rate 0 or null means UNSET, not zero-rated — the org default applies")
    void unset_product_rate_falls_back_to_the_org_default() {
        // Reading 0 as "zero-rated" would silently re-rate every product that never had a rate set, which is
        // most of them. The books have always treated it as unset; this is that rule, once.
        assertThat(TaxMath.resolveRate(BigDecimal.ZERO, bd("17"))).isEqualByComparingTo("17");
        assertThat(TaxMath.resolveRate(null, bd("17"))).isEqualByComparingTo("17");
    }

    @Test
    @DisplayName("no product rate and no org default is genuinely untaxed")
    void nothing_configured_means_no_tax() {
        assertThat(TaxMath.resolveRate(null, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a negative rate is refused rather than credited")
    void negative_rates_clamp_to_zero() {
        assertThat(TaxMath.resolveRate(bd("-5"), bd("-17"))).isEqualByComparingTo("0");
    }

    // ── the arithmetic ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EXCLUSIVE adds tax on top of the line")
    void exclusive_adds_on_top() {
        TaxMath.TaxAmounts t = TaxMath.compute(bd("20.00"), bd("5"), false);

        assertThat(t.net()).isEqualByComparingTo("20.00");
        assertThat(t.tax()).isEqualByComparingTo("1.00");
        assertThat(t.gross()).isEqualByComparingTo("21.00");
    }

    @Test
    @DisplayName("INCLUSIVE backs the tax out of a price that already contains it")
    void inclusive_backs_the_tax_out() {
        // 20.00 gross at 5% → net 19.05, tax 0.95. The storefront had no inclusive branch and would have
        // ADDED 1.00 to a price that already contained the tax, over-charging every shopper at such a store.
        TaxMath.TaxAmounts t = TaxMath.compute(bd("20.00"), bd("5"), true);

        assertThat(t.net()).isEqualByComparingTo("19.05");
        assertThat(t.tax()).isEqualByComparingTo("0.95");
        assertThat(t.gross()).isEqualByComparingTo("20.00");   // the shelf price, unchanged
    }

    @Test
    @DisplayName("net + tax == gross exactly, in both modes — no rounding crumb")
    void the_parts_always_sum_to_the_whole() {
        // A third of a penny lost here becomes a general-ledger imbalance later, so this is checked as an
        // identity rather than against literals: whatever the rounding does, the three figures must agree.
        for (String amount : new String[] { "0.01", "9.99", "33.33", "1234.56" }) {
            for (String rate : new String[] { "3", "5", "7.5", "17.5" }) {
                for (boolean inclusive : new boolean[] { false, true }) {
                    TaxMath.TaxAmounts t = TaxMath.compute(bd(amount), bd(rate), inclusive);
                    assertThat(t.net().add(t.tax()))
                            .as("%s @ %s%% %s", amount, rate, inclusive ? "INCLUSIVE" : "EXCLUSIVE")
                            .isEqualByComparingTo(t.gross());
                }
            }
        }
    }

    @Test
    @DisplayName("a zero rate is a no-op in either mode, not a division")
    void zero_rate_is_a_no_op() {
        for (boolean inclusive : new boolean[] { false, true }) {
            TaxMath.TaxAmounts t = TaxMath.compute(bd("20.00"), BigDecimal.ZERO, inclusive);
            assertThat(t.net()).isEqualByComparingTo("20.00");
            assertThat(t.tax()).isEqualByComparingTo("0");
            assertThat(t.gross()).isEqualByComparingTo("20.00");
        }
    }

    @Test
    @DisplayName("null amounts are zero, not a crash")
    void nulls_are_absorbed() {
        assertThat(TaxMath.compute(null, bd("5"), false).gross()).isEqualByComparingTo("0.00");
        assertThat(TaxMath.forLine(null, null, true, null, false).gross()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("everything is scale 2 — a total can never disagree with Money by a rounding step")
    void money_is_always_two_decimal_places() {
        TaxMath.TaxAmounts t = TaxMath.forLine(bd("33.333"), bd("7.5"), true, null, false);

        assertThat(t.net().scale()).isEqualTo(Money.SCALE);
        assertThat(t.tax().scale()).isEqualTo(Money.SCALE);
    }

    // ── the entry point channels actually call ───────────────────────────────────────────────────────

    @Test
    @DisplayName("forLine is switch + resolve + compute, composed")
    void for_line_composes_the_whole_rule() {
        // Enabled, product has no rate of its own, org default 10% → 4.00 × 10% = 0.40.
        TaxMath.TaxAmounts t = TaxMath.forLine(bd("4.00"), BigDecimal.ZERO, true, bd("10"), false);

        assertThat(t.rate()).isEqualByComparingTo("10");
        assertThat(t.tax()).isEqualByComparingTo("0.40");
        assertThat(t.gross()).isEqualByComparingTo("4.40");
    }
}
