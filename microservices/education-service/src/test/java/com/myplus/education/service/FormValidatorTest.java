package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import com.myplus.education.dto.DiscountDTO;
import com.myplus.education.dto.GradeDTO;

import org.junit.jupiter.api.Test;

/**
 * Slice B §8 — validation for the other education forms that carry money.
 *
 * Pure, so it runs on every `mvn test`. The sharpest case is the percentage discount: a value above 100
 * discounts more than the fee, and `monthlyDue` floors that at 0 — so nothing downstream ever reports it.
 */
class FormValidatorTest {

    // ── Grade: the class fee is the base of every opening due ───────────────────────────────────

    @Test
    void a_valid_class_passes() {
        GradeDTO g = new GradeDTO();
        g.setName("Class 5");
        g.setFee(1500);
        assertThat(FormValidator.validateGrade(g, LocalTime.of(8, 0), LocalTime.of(13, 0))).isEmpty();
    }

    @Test
    void a_negative_class_fee_is_refused() {
        // It would reach every student in the class through gradeFee() → monthlyDue().
        GradeDTO g = new GradeDTO();
        g.setFee(-1);
        assertThat(FormValidator.validateGrade(g, null, null)).isNotEmpty();
        assertThat(FormValidator.validateGrade(g, null, null).get(0)).contains("Class fee").contains("-1");
    }

    @Test
    void a_free_class_is_allowed() {
        // Zero is legitimate — and B3 now skips the empty opening due it used to generate.
        GradeDTO g = new GradeDTO();
        g.setFee(0);
        assertThat(FormValidator.validateGrade(g, null, null)).isEmpty();
    }

    @Test
    void a_class_ending_before_it_starts_is_refused() {
        GradeDTO g = new GradeDTO();
        g.setFee(100);
        assertThat(FormValidator.validateGrade(g, LocalTime.of(13, 0), LocalTime.of(8, 0))).isNotEmpty();
    }

    @Test
    void missing_times_are_fine() {
        GradeDTO g = new GradeDTO();
        g.setFee(100);
        assertThat(FormValidator.validateGrade(g, null, null)).isEmpty();
        assertThat(FormValidator.validateGrade(g, LocalTime.of(8, 0), null)).isEmpty();
    }

    // ── Discount: amount vs percentage, the same domain rule as the fee form ────────────────────

    @Test
    void a_negative_discount_amount_is_refused() {
        DiscountDTO d = new DiscountDTO();
        d.setDi("amount");
        d.setAmount(-5);
        assertThat(FormValidator.validateDiscount(d, null, null)).isNotEmpty();
    }

    @Test
    void a_percentage_discount_above_100_is_refused() {
        // discountAmount() computes base * 150 / 100 — more than the fee. monthlyDue floors it at 0, so
        // without this check the guardian is billed nothing and nothing reports why.
        DiscountDTO d = new DiscountDTO();
        d.setDi("%");
        d.setAmount(150);
        assertThat(FormValidator.validateDiscount(d, null, null)).isNotEmpty();
        assertThat(FormValidator.validateDiscount(d, null, null).get(0)).contains("150");
    }

    @Test
    void a_100_percent_discount_is_allowed() {
        DiscountDTO d = new DiscountDTO();
        d.setDi("%");
        d.setAmount(100);
        assertThat(FormValidator.validateDiscount(d, null, null)).as("a full scholarship is real").isEmpty();
    }

    @Test
    void a_large_AMOUNT_discount_is_allowed_because_there_is_no_fee_in_context() {
        // A discount is DEFINED without a fee; bounding it here would refuse a legitimate high-value
        // discount for an expensive class. Slice B bounds it where a fee exists — on the collection.
        DiscountDTO d = new DiscountDTO();
        d.setDi("amount");
        d.setAmount(50000);
        assertThat(FormValidator.validateDiscount(d, null, null)).isEmpty();
    }

    @Test
    void an_unset_discount_type_is_not_treated_as_a_percentage() {
        // The percentage cap must not fire on an amount discount that merely omitted its type.
        DiscountDTO d = new DiscountDTO();
        d.setDi(null);
        d.setAmount(5000);
        assertThat(FormValidator.validateDiscount(d, null, null)).isEmpty();
    }

    @Test
    void a_discount_period_ending_before_it_starts_is_refused() {
        DiscountDTO d = new DiscountDTO();
        d.setDi("amount");
        d.setAmount(100);
        assertThat(FormValidator.validateDiscount(d, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1)))
                .isNotEmpty();
    }

    @Test
    void a_valid_discount_passes() {
        DiscountDTO d = new DiscountDTO();
        d.setName("Sibling");
        d.setDi("%");
        d.setAmount(10);
        assertThat(FormValidator.validateDiscount(d, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .isEmpty();
    }

    @Test
    void null_dtos_are_refused_rather_than_throwing() {
        assertThat(FormValidator.validateGrade(null, null, null)).isNotEmpty();
        assertThat(FormValidator.validateDiscount(null, null, null)).isNotEmpty();
    }
}
