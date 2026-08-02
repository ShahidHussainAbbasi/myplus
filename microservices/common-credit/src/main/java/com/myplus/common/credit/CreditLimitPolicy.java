package com.myplus.common.credit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B2B Phase 1 (= OMS B4, customer requirement #9) — how much a party may owe, and what to do when a
 * transaction would take them past it.
 *
 * <h3>Why this is a shared library and not a business-service class</h3>
 * Pharmacy, marketplace and education all sell on account through their own tables. Duplicating this
 * arithmetic per service is how three subtly different answers to "is this customer over their limit?"
 * appear — and the differences would only surface in someone's ledger. The rules are shared here; the
 * <em>data</em> stays in each service, exactly as {@link CreditService} shares rules while
 * {@link CreditStore} keeps the ledger local.
 *
 * <p><b>No SPI, deliberately.</b> Unlike {@link CreditService}, this needs no storage port: every caller
 * already holds the balance and the limit on the row it just loaded. An interface for data the caller
 * already has would be ceremony, not decoupling.
 *
 * <p>Note the two credit concepts in this library point opposite ways: {@code CreditService} is credit the
 * customer <b>has</b> (a liability the shop owes them); this is credit the customer <b>may take</b> (a cap
 * on the receivable).
 *
 * <h3>Everything here is pure</h3>
 * No repository, no settings lookup, no clock. The caller owns the I/O and passes the numbers in. That is
 * what makes the two genuinely tricky cases — an edit that must not double-count, and store credit that
 * must reduce exposure — testable without a Spring context.
 */
public final class CreditLimitPolicy {

    /** Policy values, shared with the settings catalog so the strings cannot drift apart. */
    public static final String OFF = "off";
    public static final String WARN = "warn";
    public static final String BLOCK = "block";

    /** What the caller must do about a transaction. */
    public enum Action {
        /** Nothing to say — no limit, policy off, or comfortably within. */
        PROCEED,
        /** Over the limit under {@code warn}: ask the operator FIRST, write nothing until they accept. */
        CONFIRM,
        /** Over the limit under {@code block}: refuse. No confirmation is offered. */
        REFUSE
    }

    /**
     * The outcome of the arithmetic, independent of policy.
     *
     * @param breached  true when a limit exists and the projected exposure exceeds it
     * @param exposure  what the party would owe once this transaction is recorded
     * @param over      how far past the limit ({@code exposure − limit}), zero when not breached
     * @param limit     the limit that applied, null when the party has none
     */
    public record Verdict(boolean breached, BigDecimal exposure, BigDecimal over, BigDecimal limit) {
    }

    private CreditLimitPolicy() {
    }

    /**
     * Project what the party will owe and decide whether that breaks their limit.
     *
     * @param currentBalance what they owe right now (the maintained running balance; null treated as zero)
     * @param thisUnpaid     the unpaid portion of the transaction being recorded, store credit and any
     *                       payment already deducted (null or negative treated as zero)
     * @param editingDue     when EDITING an existing document, the unpaid amount that document currently
     *                       contributes to {@code currentBalance} — subtracted so it is not counted twice.
     *                       Null/zero for a new transaction.
     * @param limit          the party's limit; <b>null means no limit and can never breach</b>
     */
    public static Verdict evaluate(BigDecimal currentBalance, BigDecimal thisUnpaid,
                                   BigDecimal editingDue, BigDecimal limit) {
        BigDecimal balance = nz(currentBalance);
        BigDecimal unpaid = nz(thisUnpaid);
        if (unpaid.signum() < 0) {
            unpaid = BigDecimal.ZERO;   // an overpayment reduces the balance; it never adds exposure
        }
        // An edit's own current contribution is already inside `balance`. Without removing it, reducing an
        // over-limit invoice would still look like a breach — warning on the very act of fixing it.
        BigDecimal exposure = balance.add(unpaid).subtract(nz(editingDue));
        if (exposure.signum() < 0) {
            exposure = BigDecimal.ZERO;
        }
        if (limit == null) {
            return new Verdict(false, exposure, BigDecimal.ZERO, null);
        }
        // At the limit is WITHIN it — a limit is the most they may owe, not the most minus a paisa.
        boolean breached = exposure.compareTo(limit) > 0;
        BigDecimal over = breached ? exposure.subtract(limit) : BigDecimal.ZERO;
        return new Verdict(breached, exposure, over, limit);
    }

    /**
     * Turn a verdict into an action under the org's policy.
     *
     * <p>{@code acknowledged} is the operator having already said yes. It is honoured only under
     * {@code warn} — under {@code block} the flag is ignored entirely, because {@code block} means nobody
     * on the till can consent past it. That distinction is the whole difference between the two values.
     */
    public static Action decide(Verdict verdict, String policy, boolean acknowledged) {
        if (verdict == null || !verdict.breached()) {
            return Action.PROCEED;
        }
        String p = (policy == null || policy.isBlank()) ? WARN : policy.trim().toLowerCase();
        if (OFF.equals(p)) {
            return Action.PROCEED;
        }
        if (BLOCK.equals(p)) {
            return Action.REFUSE;
        }
        // warn (and any unrecognised value — fail toward asking rather than silently allowing)
        return acknowledged ? Action.PROCEED : Action.CONFIRM;
    }

    /**
     * The due date implied by net payment terms.
     *
     * @return {@code null} when there are no terms or no base date, so the caller keeps whatever behaviour
     *         it has today (a hand-entered due date) rather than inventing one.
     */
    public static LocalDate dueDateFrom(LocalDate baseDate, Integer termsDays) {
        if (baseDate == null || termsDays == null || termsDays < 0) {
            return null;
        }
        return baseDate.plusDays(termsDays);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
