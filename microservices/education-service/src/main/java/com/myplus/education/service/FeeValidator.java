package com.myplus.education.service;

// NOTE: education has TWO FeeCollectionDTO classes — the top-level flat/legacy shape that `addFc`
// actually binds, and a nested REST shape in EducationDTOs. This must be the FORMER; validating the
// wrong one compiles into a type mismatch at the call site rather than an obviously wrong import.
import com.myplus.education.dto.FeeCollectionDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Slice B — validates a fee collection before anything is written.
 * Design: microservices/docs/slices/edu-B-fee-validation.md
 *
 * Pure and static so every rule is testable without a database, matching {@code MarksValidator} (1.3)
 * and {@code ExamLockGuard} (1.2).
 *
 * Why a validator rather than Bean Validation annotations alone: the rules here are RELATIONAL — a
 * discount is bounded by the fee, and a percentage discount is bounded by 100 instead. A {@code @Min(0)}
 * on each field would let those through while making the DTO look validated. Annotations are still added
 * as a cheap second layer; this is the thing that is trusted.
 *
 * Money in education fees is deliberately whole-number {@code Integer} (the whole module is), so these
 * rules work in ints — that is the domain's choice, not an oversight.
 */
public final class FeeValidator {

    private FeeValidator() { }

    /** Discount expressed as a percentage rather than an amount — the UI's other option is "amount". */
    private static final String PERCENT = "%";

    /**
     * @return every problem found, in field order. Empty means valid.
     *
     * Returns ALL problems rather than the first: a clerk fixing one field at a time, with a round trip
     * each, is how a form earns its reputation. One response, every problem.
     */
    public static List<String> validate(FeeCollectionDTO dto) {
        List<String> problems = new ArrayList<>();
        if (dto == null) {
            problems.add("No fee details were submitted");
            return problems;
        }

        // D2 — there is no legitimate negative fee in this system. Refunds and carry-forward live in the
        // credit ledger (0.2b); a correction is a different operation with a different audit meaning.
        // Allowing negatives as a back-door adjustment makes a negative receipt and a refund
        // indistinguishable in the ledger afterwards.
        Validations.negative(problems, "Fee", dto.getFee());
        Validations.negative(problems, "Due amount", dto.getDueAmount());
        Validations.negative(problems, "Fee paid", dto.getFeePaid());
        Validations.negative(problems, "Other dues", dto.getOtherDues());
        Validations.negative(problems, "Vehicle fee", dto.getVehicleFee());
        Validations.negative(problems, "Discount", dto.getDiscount());

        // B4 — a discount is bounded, but by WHAT depends on how it is expressed. Comparing a percentage
        // against the fee would wrongly reject "10%" on a fee of 5, and comparing an amount against 100
        // would wrongly accept a 5,000 discount on a 3,000 fee.
        Integer discount = dto.getDiscount();
        if (discount != null && discount >= 0) {
            if (isPercent(dto.getDiscountType())) {
                if (discount > 100) {
                    problems.add("Discount " + discount + "% cannot exceed 100%");
                }
            } else {
                Integer fee = dto.getFee();
                if (fee != null && fee >= 0 && discount > fee) {
                    problems.add("Discount " + discount + " exceeds the fee " + fee);
                }
            }
        }

        // A due day outside a month is unusable: the voucher would never come due.
        Integer day = dto.getDueDayOfMonth();
        if (day != null && (day < 1 || day > 31)) {
            problems.add("Due day of month must be between 1 and 31 (got " + day + ")");
        }

        return problems;
    }

    /** True when this collection carries money — a charge or a payment — and so needs a real student (D3). */
    public static boolean isChargingRow(FeeCollectionDTO dto) {
        if (dto == null) return false;
        return positive(dto.getDueAmount()) || positive(dto.getFeePaid()) || positive(dto.getFee());
    }

    private static boolean positive(Integer v) {
        return v != null && v > 0;
    }

    /** The UI offers "amount" and "%"; anything unset is treated as an amount, which is the safer reading. */
    private static boolean isPercent(String discountType) {
        return discountType != null && PERCENT.equals(discountType.trim());
    }
}
