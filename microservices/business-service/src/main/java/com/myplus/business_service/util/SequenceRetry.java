package com.myplus.business_service.util;

import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A backstop for per-org document numbers, and — more importantly now — the record of which constraint
 * violation means what.
 *
 * <h3>⚠ This should no longer fire, and that is deliberate</h3>
 * Every per-org number is allocated by {@code DocumentNumberService} against a serialised counter, so
 * collisions are <b>prevented</b> rather than recovered from. The retry below is defence in depth: if a future
 * path ever allocates without the allocator, a sale is retried instead of lost. It is kept for that reason and
 * not because anything currently needs it.
 *
 * <p>Its lasting value is the <b>recogniser</b>. {@code SagaSellService} still has to tell an idempotency-key
 * duplicate (return the winner's invoice) from anything else, and getting that wrong lost sales. The table
 * below is that knowledge, and it stays true whatever allocates the numbers.
 *
 * <h3>Historically: what it was built for</h3>
 * Numbers used to be allocated {@code MAX(seq) + 1}. Two tills selling in the same moment read the same
 * maximum and one lost.
 *
 * <h3>The constraint is not the bug — the missing retry is</h3>
 * {@code uq_ch_org_invoice_seq} and its siblings exist precisely because {@code MAX+1} is racy; V42 says so in
 * as many words. They stop two sales minting the same invoice number, which is the outcome that would corrupt
 * the books. What was missing is the other half: <b>nothing caught the violation and took the next number.</b>
 *
 * <p>So the loser's sale died with {@code "Transaction silently rolled back because it has been marked as
 * rollback-only"} — a sentence that means nothing to somebody standing at a counter, for a situation (two
 * cashiers, two tills) that is not an edge case but the normal shape of any shop with more than one till.
 *
 * <h3>⚠ It only retries collisions it RECOGNISES, and that is the point</h3>
 * Retrying every {@link org.springframework.dao.DataIntegrityViolationException} would be worse than not
 * retrying at all. Three constraints in this service look similar and mean completely different things:
 *
 * <ul>
 *   <li>{@code uq_ch_org_invoice_seq} — lost a race for a number. <b>Retry.</b>
 *   <li>{@code idempotency_key} — this exact sale was already recorded. Retrying would write it TWICE; the
 *       caller must return the winner's invoice instead.
 *   <li>{@code uq_installment_plan_seq} — {@code (plan_id, seq_no)}: the schedule generator produced two
 *       instalments in the same position. That is a real defect, and retrying it would hide the one thing
 *       that reveals it.
 * </ul>
 *
 * <h3>⚠ CALL THIS WHERE A NEW TRANSACTION BEGINS</h3>
 * A constraint violation marks its transaction <b>rollback-only</b>. Retrying inside that transaction cannot
 * work — every subsequent statement fails and the commit throws regardless, which is exactly the trap that
 * made {@code createInstallmentPlan} log "the SALE stands" while the sale did not. The supplied action must
 * therefore start its own transaction ({@code REQUIRES_NEW}) or be invoked before one is open.
 */
public final class SequenceRetry {

    private static final Logger LOG = LoggerFactory.getLogger(SequenceRetry.class);

    /**
     * Per-org running-number constraints, and nothing else.
     *
     * <p>Deliberately a list of names rather than a pattern: {@code uq_installment_plan_seq} also ends in
     * "seq" and must NOT be retried, so a match on {@code .*seq} would silently swallow a schedule-generation
     * bug. A name added here is a decision; a name matched by a wildcard is an accident.
     */
    private static final Set<String> RETRYABLE = Set.of(
            "uq_ch_org_invoice_seq",   // customer_history.invoice_seq — every sale
            "uq_plan_org_seq",         // installment_plan.plan_seq — every financed sale
            "uq_quote_org_seq");       // sales_quote.quote_seq

    /** Enough to clear a realistic pile-up at a counter; short enough that a real defect still surfaces. */
    private static final int DEFAULT_ATTEMPTS = 5;

    private SequenceRetry() {}

    /**
     * Is this failure a lost race for a per-org document number?
     *
     * <p>Walks the whole cause chain: Spring wraps Hibernate wraps the JDBC exception, and only the innermost
     * message names the constraint.
     */
    public static boolean isCollision(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase();
            for (String name : RETRYABLE) {
                if (lower.contains(name)) return true;
            }
            if (c.getCause() == c) break;   // defensive: a self-referential cause would spin forever
        }
        return false;
    }

    /**
     * Run {@code action}, re-running it if it loses a race for a document number.
     *
     * @param what   what is being numbered, for the log — "invoice", "installment plan"
     * @param action must begin its OWN transaction; see the class note on rollback-only
     * @throws RuntimeException the last failure, if every attempt loses. A caller that has run out of
     *         attempts is looking at contention no retry can fix, and hiding that would be worse.
     */
    public static <T> T withRetry(String what, Supplier<T> action) {
        return withRetry(what, DEFAULT_ATTEMPTS, action);
    }

    static <T> T withRetry(String what, int attempts, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isCollision(e)) throw e;   // not ours — never swallow somebody else's constraint
                last = e;
                LOG.info("{} number collided (attempt {} of {}) — taking the next one", what, attempt, attempts);
                pause(attempt);
            }
        }
        throw last;
    }

    /**
     * A short, growing, jittered pause.
     *
     * <p>Retrying instantly is what turns two colliding tills into four: both losers re-read the same maximum
     * at the same moment and collide again. The jitter is what breaks the lockstep — without it a fixed delay
     * simply moves the collision rather than resolving it.
     */
    private static void pause(int attempt) {
        try {
            Thread.sleep(5L * attempt + (long) (Math.random() * 10));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();   // never swallow an interrupt
            throw new IllegalStateException("Interrupted while retrying a document number", ie);
        }
    }
}
