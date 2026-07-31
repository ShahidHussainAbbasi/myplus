package com.myplus.education.service;

import com.myplus.education.dto.DiscountDTO;
import com.myplus.education.dto.GradeDTO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Slice B §8 — validates the other education forms that carry money.
 *
 * Scope is deliberately TWO forms, not four. §6 assumed `addVehicle` carried a fare; it does not —
 * {@code Vehicle} has no fare column, and the vehicle charge lives on {@code Student.vf}. Neither
 * {@code Student.fee} nor {@code Student.vf} is exposed by {@code StudentDTO}, so neither is reachable
 * through the API. Validating money that cannot be submitted would be theatre.
 *
 * Dates and times arrive as STRINGS on these DTOs, parsed by the controller with different helpers per
 * field. The parsed values are passed in rather than re-parsed here: one parse, in the place that
 * already owns the format.
 */
public final class FormValidator {

    private FormValidator() { }

    /** Discount expressed as a percentage of the fee — {@code FeeService.discountAmount} keys on this exact value. */
    private static final String PERCENT = "%";

    /**
     * A class. {@code Grade.fee} is the base of every opening due via {@code monthlyDue()}, so a negative
     * one would propagate into the fee ledger for every student in the class.
     *
     * @param from parsed {@code timeFromStr}, or null
     * @param to   parsed {@code timeToStr}, or null
     */
    public static List<String> validateGrade(GradeDTO dto, LocalTime from, LocalTime to) {
        List<String> problems = new ArrayList<>();
        if (dto == null) {
            problems.add("No class details were submitted");
            return problems;
        }
        // Zero is legitimate — a free class is a real thing, and B3 now skips its empty opening due.
        Validations.negative(problems, "Class fee", dto.getFee());
        Validations.timeOrder(problems, "Class timing", from, to);
        return problems;
    }

    /**
     * A discount definition. {@code FeeService.discountAmount} reads it as:
     * <pre>"%".equals(di) ? round(base * amount / 100.0) : amount</pre>
     * so an amount above 100 under {@code di = "%"} discounts MORE than the fee. {@code monthlyDue} floors
     * the result at 0, which is precisely why it must be refused here — the parent is simply billed nothing
     * and no screen ever reports that the discount was nonsense.
     *
     * An {@code amount} discount is checked only for {@code >= 0}: a discount is defined with no fee in
     * context, so there is nothing to bound it against. Slice B already bounds it where a fee exists — on
     * the collection itself.
     */
    public static List<String> validateDiscount(DiscountDTO dto, LocalDate start, LocalDate end) {
        List<String> problems = new ArrayList<>();
        if (dto == null) {
            problems.add("No discount details were submitted");
            return problems;
        }
        Validations.negative(problems, "Discount amount", dto.getAmount());
        if (isPercent(dto.getDi())) {
            Validations.percentOver100(problems, "Discount", dto.getAmount());
        }
        Validations.dateOrder(problems, "Discount period", start, end);
        return problems;
    }

    private static boolean isPercent(String di) {
        return di != null && PERCENT.equals(di.trim());
    }
}
