package com.myplus.common.installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns agreed {@link PlanTerms} into the dated amounts a customer will actually be asked for.
 *
 * <p>Pure: no Spring, no clock, no repository. The counter previews a schedule by calling this, and the sale
 * commits the schedule by calling the same method with the same terms — so what the customer was read out and
 * what was stored cannot differ.
 *
 * <h3>The one rule that matters: the parts sum to the whole, exactly</h3>
 * A shop's ledger must not be out by a paisa, and "out by a paisa" is precisely what naive division produces.
 * {@code 10,000 / 3} has no exact 2-decimal representation, so the schedule is built as
 * <b>({@code count − 1}) equal installments plus a remainder</b>, and the remainder carries whatever the
 * division could not.
 */
public final class ScheduleGenerator {

    /** Money is {@code BigDecimal(19,2)} platform-wide (build standard §1.5). */
    public static final int MONEY_SCALE = 2;

    /** For scaling a single value. <b>Not</b> for splitting one — see {@link #SPLIT_ROUNDING}. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * For dividing one amount into many: <b>{@code DOWN}, not {@code HALF_UP}</b>.
     *
     * <p>HALF_UP is the right default for a single figure and the wrong one for a split, because it can round
     * the per-installment amount <em>up</em> far enough that the remainder collapses. With 60 installments over
     * 17.70, HALF_UP gives 0.30 each; 59 × 0.30 is already the whole 17.70, so the last installment computes as
     * <b>0.00</b> — a schedule that ends with a zero payment and a customer asked for nothing on the final date.
     *
     * <p>Rounding DOWN makes the per-installment amount a floor, so the remainder is always at least as large as
     * the others and can never vanish. The same 17.70 over 60 gives 0.29 each and 0.59 last.
     */
    public static final RoundingMode SPLIT_ROUNDING = RoundingMode.DOWN;

    /** The smallest amount any single installment may be — one minor unit. */
    private static final BigDecimal MINOR_UNIT = new BigDecimal("0.01");

    private ScheduleGenerator() {
    }

    /**
     * Build the schedule for {@code terms}.
     *
     * @throws IllegalArgumentException with an operator-readable message when the terms cannot make a sound
     *         plan. Thrown rather than returned as an empty list: a caller that ignored an empty schedule would
     *         write a plan with no installments — a debt nobody is ever asked to pay.
     */
    public static List<ScheduledAmount> generate(PlanTerms terms) {
        if (terms == null) throw new IllegalArgumentException("No installment terms were supplied.");
        String invalid = terms.validate();
        if (invalid != null) throw new IllegalArgumentException(invalid);

        BigDecimal financed = terms.financedAmount();
        int count = terms.installmentCount();

        // Every installment must be worth asking for. Without this a tiny balance over many installments
        // produces rows of 0.00 that the customer is "due" — and that a reminder would text them about.
        BigDecimal smallestViable = MINOR_UNIT.multiply(BigDecimal.valueOf(count));
        if (financed.compareTo(smallestViable) < 0) {
            throw new IllegalArgumentException(
                    "That is too little to split into " + count + " installments — reduce the number of payments.");
        }

        BigDecimal each = financed.divide(BigDecimal.valueOf(count), MONEY_SCALE, SPLIT_ROUNDING);

        List<ScheduledAmount> schedule = new ArrayList<>(count);
        BigDecimal allocated = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
        for (int i = 0; i < count - 1; i++) {
            schedule.add(new ScheduledAmount(i + 1, terms.frequency().advance(terms.firstDueDate(), i), each));
            allocated = allocated.add(each);
        }
        // The last one carries the remainder, so the schedule reconciles to the financed amount by construction
        // rather than by a rounding rule that happens to work for the amounts someone tried.
        BigDecimal last = financed.subtract(allocated);
        schedule.add(new ScheduledAmount(count, terms.frequency().advance(terms.firstDueDate(), count - 1), last));

        return schedule;
    }

    /**
     * The sum of a schedule — the value that must equal {@link PlanTerms#financedAmount()} and, in turn, the
     * plan invoice's outstanding balance.
     *
     * <p>Public because it is the assertion, not an implementation detail: the service writing the plan checks
     * it before committing, and the INST-1 Cypress gate checks it afterwards.
     */
    public static BigDecimal total(List<ScheduledAmount> schedule) {
        BigDecimal t = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
        if (schedule == null) return t;
        for (ScheduledAmount s : schedule) {
            if (s != null && s.amount() != null) t = t.add(s.amount());
        }
        return t;
    }

    /**
     * The last due date in a schedule — when the plan finishes, for display and for "show me plans ending this
     * quarter". Null for an empty schedule.
     */
    public static LocalDate finalDueDate(List<ScheduledAmount> schedule) {
        if (schedule == null || schedule.isEmpty()) return null;
        return schedule.get(schedule.size() - 1).dueDate();
    }
}
