package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;

// The top-level flat DTO that `addFc` binds — NOT the nested EducationDTOs.FeeCollectionDTO.
import com.myplus.education.dto.FeeCollectionDTO;

import org.junit.jupiter.api.Test;

/**
 * Slice B — the fee validation rules.
 *
 * Pure, so every rule runs on `mvn test` with no database. The Cypress gate proves the validator is
 * WIRED IN; this proves it is RIGHT — including the domain distinction a browser test would be slow to
 * enumerate: a discount is bounded by the FEE when it is an amount, and by 100 when it is a percentage.
 */
class FeeValidatorTest {

    private static FeeCollectionDTO dto() {
        FeeCollectionDTO d = new FeeCollectionDTO();
        d.setEnrollNo("ENR-001");
        d.setFee(3000);
        d.setDueAmount(3000);
        d.setFeePaid(3000);
        return d;
    }

    @Test
    void a_normal_collection_is_valid() {
        assertThat(FeeValidator.validate(dto())).isEmpty();
    }

    @Test
    void every_money_field_refuses_a_negative() {
        // feePaid is the dangerous one: it reaches the shared subledger and posts a GL receipt.
        for (String field : new String[] { "fee", "dueAmount", "feePaid", "otherDues", "vehicleFee", "discount" }) {
            FeeCollectionDTO d = dto();
            switch (field) {
                case "fee" -> d.setFee(-1);
                case "dueAmount" -> d.setDueAmount(-1);
                case "feePaid" -> d.setFeePaid(-100);
                case "otherDues" -> d.setOtherDues(-5);
                case "vehicleFee" -> d.setVehicleFee(-5);
                case "discount" -> d.setDiscount(-5);
                default -> throw new IllegalStateException(field);
            }
            assertThat(FeeValidator.validate(d))
                    .as("a negative %s must be refused", field)
                    .isNotEmpty();
        }
    }

    @Test
    void the_refusal_names_the_field_and_the_value() {
        FeeCollectionDTO d = dto();
        d.setFeePaid(-100);
        assertThat(FeeValidator.validate(d).get(0)).contains("Fee paid").contains("-100");
    }

    @Test
    void every_problem_is_reported_at_once_not_just_the_first() {
        // A clerk fixing one field per round trip is how a form earns its reputation.
        FeeCollectionDTO d = dto();
        d.setFeePaid(-100);
        d.setDueAmount(-1);
        d.setVehicleFee(-7);
        assertThat(FeeValidator.validate(d)).hasSize(3);
    }

    // ── the domain distinction: amount vs percentage ────────────────────────────────────────────

    @Test
    void an_AMOUNT_discount_cannot_exceed_the_fee() {
        FeeCollectionDTO d = dto();
        d.setDiscountType("amount");
        d.setFee(3000);
        d.setDiscount(5000);
        assertThat(FeeValidator.validate(d))
                .as("5000 off a 3000 fee is a negative charge by another road")
                .isNotEmpty();
        assertThat(FeeValidator.validate(d).get(0)).contains("5000").contains("3000");
    }

    @Test
    void a_PERCENT_discount_is_bounded_by_100_not_by_the_fee() {
        // The trap: comparing a percentage against the fee would reject "10%" on a fee of 5.
        FeeCollectionDTO d = dto();
        d.setDiscountType("%");
        d.setFee(5);
        d.setDiscount(10);
        assertThat(FeeValidator.validate(d)).as("10% of a small fee is perfectly normal").isEmpty();

        d.setDiscount(150);
        assertThat(FeeValidator.validate(d)).as("but 150% is not").isNotEmpty();
    }

    @Test
    void a_100_percent_discount_is_allowed() {
        // A full scholarship is a real thing; the boundary must be inclusive.
        FeeCollectionDTO d = dto();
        d.setDiscountType("%");
        d.setDiscount(100);
        assertThat(FeeValidator.validate(d)).isEmpty();
    }

    @Test
    void a_discount_equal_to_the_fee_is_allowed() {
        FeeCollectionDTO d = dto();
        d.setDiscountType("amount");
        d.setFee(3000);
        d.setDiscount(3000);
        assertThat(FeeValidator.validate(d)).isEmpty();
    }

    @Test
    void an_unset_discount_type_is_treated_as_an_amount() {
        // The safer reading: an unbounded percentage would let a 5000 "discount" through on a 3000 fee.
        FeeCollectionDTO d = dto();
        d.setDiscountType(null);
        d.setFee(3000);
        d.setDiscount(5000);
        assertThat(FeeValidator.validate(d)).isNotEmpty();
    }

    // ── due day ────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_due_day_must_fall_inside_a_month() {
        FeeCollectionDTO d = dto();
        d.setDueDayOfMonth(45);
        assertThat(FeeValidator.validate(d)).isNotEmpty();
        d.setDueDayOfMonth(0);
        assertThat(FeeValidator.validate(d)).isNotEmpty();
        d.setDueDayOfMonth(31);
        assertThat(FeeValidator.validate(d)).as("31 is a legitimate due day").isEmpty();
    }

    @Test
    void an_unset_due_day_is_fine() {
        FeeCollectionDTO d = dto();
        d.setDueDayOfMonth(null);
        assertThat(FeeValidator.validate(d)).isEmpty();
    }

    // ── isChargingRow: which rows need a real student (B2) ──────────────────────────────────────

    @Test
    void a_row_carrying_money_is_a_charging_row() {
        FeeCollectionDTO due = new FeeCollectionDTO();
        due.setDueAmount(3000);
        assertThat(FeeValidator.isChargingRow(due)).as("a DUE needs a student, not just a payment").isTrue();

        FeeCollectionDTO paid = new FeeCollectionDTO();
        paid.setFeePaid(500);
        assertThat(FeeValidator.isChargingRow(paid)).isTrue();

        FeeCollectionDTO fee = new FeeCollectionDTO();
        fee.setFee(3000);
        assertThat(FeeValidator.isChargingRow(fee)).isTrue();
    }

    @Test
    void an_empty_or_zero_row_is_not_a_charging_row() {
        // Zero-value rows must stay creatable — refusing them would break the existing zero-collection
        // path that fees-to-gl asserts is "not an accounting event".
        assertThat(FeeValidator.isChargingRow(new FeeCollectionDTO())).isFalse();
        FeeCollectionDTO zero = new FeeCollectionDTO();
        zero.setFee(0);
        zero.setDueAmount(0);
        zero.setFeePaid(0);
        assertThat(FeeValidator.isChargingRow(zero)).isFalse();
        assertThat(FeeValidator.isChargingRow(null)).isFalse();
    }

    @Test
    void a_null_dto_is_refused_rather_than_throwing() {
        assertThat(FeeValidator.validate(null)).isNotEmpty();
    }
}
