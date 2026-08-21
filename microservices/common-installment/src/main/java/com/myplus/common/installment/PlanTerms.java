package com.myplus.common.installment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the shop and the customer agreed: a price, what was paid at the counter, and how the rest is dated.
 *
 * <p>A record, and immutable, because these terms are quoted to a customer before the sale commits and must
 * be the same terms that are stored afterwards. The preview and the commit read one object.
 *
 * <h3>Markup is present but MUST be zero in INST-1</h3>
 * If a shop charges more on terms than for cash, that difference is <b>finance income</b> — not goods revenue
 * — and it is usually not taxable as a supply of goods. Folding it into the invoice value would overstate
 * sales and put tax on financing, corrupting two reports at once (design D12, option A1: "never").
 *
 * <p>Doing it correctly needs a {@code 4400 Finance Income} account, a new {@code PostingEventRequest} field
 * and the five {@code gl_outbox} copy points — <b>which is precisely the change shape that left
 * {@code 4200 Sales Discount} empty in every tenant for months while three specs stayed green.</b> So INST-1
 * ships zero-markup plans only, {@code pos.installment.markupEnabled} stays false, and INST-6 adds it with a
 * <b>trial-balance</b> gate rather than an invoice gate.
 *
 * <p>The field exists here so the shape does not change when INST-6 lands; {@link #validate()} refuses a
 * non-zero value until then, which is honest about what is supported rather than accepting a number and
 * quietly ignoring it.
 */
public record PlanTerms(
        BigDecimal cashPrice,
        BigDecimal downPayment,
        int installmentCount,
        Frequency frequency,
        LocalDate firstDueDate,
        BigDecimal markupAmount) {

    /**
     * Compact constructor: absent money becomes ZERO, once, here.
     *
     * <p>A client that omits {@code markupAmount} — which every INST-1 client does, since markup is not
     * supported — otherwise carries a null all the way to an INSERT against a {@code NOT NULL} column, and
     * the plan fails <b>after the sale has committed</b>. Found by the gate: the sale succeeded, the plan
     * did not, and the caller's transaction came back "marked as rollback-only".
     *
     * <p>Normalising in the record rather than at each call site means there is one place this can be got
     * wrong instead of one per caller. {@code validate()} still refuses a non-zero markup — absent and zero
     * mean the same thing, and neither is "financing at a margin".
     */
    public PlanTerms {
        cashPrice = nz(cashPrice);
        downPayment = nz(downPayment);
        markupAmount = nz(markupAmount);
    }

    /** Terms with no markup — the only shape INST-1 supports. */
    public static PlanTerms of(BigDecimal cashPrice, BigDecimal downPayment, int installmentCount,
                               Frequency frequency, LocalDate firstDueDate) {
        return new PlanTerms(cashPrice, downPayment, installmentCount, frequency, firstDueDate,
                BigDecimal.ZERO);
    }

    /**
     * What the customer still owes after the down payment: {@code cashPrice − downPayment + markup}.
     *
     * <p>This is the number the schedule must sum to, and the number that must equal the plan invoice's
     * outstanding balance (design D5). The plan holds no money the general ledger does not already know
     * about — that invariant is the INST-1 gate.
     */
    public BigDecimal financedAmount() {
        BigDecimal price = nz(cashPrice);
        BigDecimal down = nz(downPayment);
        return price.subtract(down).add(nz(markupAmount))
                .setScale(ScheduleGenerator.MONEY_SCALE, ScheduleGenerator.ROUNDING);
    }

    /**
     * Refuse terms that cannot make a sound plan, with the reason a cashier can act on.
     *
     * @return null when the terms are usable, otherwise the message to show
     */
    public String validate() {
        if (nz(cashPrice).signum() <= 0) return "The price must be more than zero.";
        if (nz(downPayment).signum() < 0) return "The down payment cannot be negative.";
        if (nz(downPayment).compareTo(nz(cashPrice)) > 0)
            return "The down payment cannot be more than the price.";
        if (installmentCount < 1) return "A plan needs at least one installment.";
        if (frequency == null) return "Choose how often payments fall due.";
        if (firstDueDate == null) return "Choose the first due date.";
        if (nz(markupAmount).signum() != 0)
            // Refused rather than ignored — see the class javadoc. A number accepted and silently dropped is
            // how a shop discovers months later that it financed at cost.
            return "Markup is not supported yet — enter the installment price as the item price instead.";
        if (financedAmount().signum() <= 0)
            return "Nothing is left to finance — the down payment covers the whole price.";
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
