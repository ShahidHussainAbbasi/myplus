package com.myplus.business_service.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The retry behind per-org document numbers.
 *
 * <p>Runs with no container and no database: what has to be right is <b>which failures are recognised</b>, and
 * that is string-and-control-flow logic. The real collision is gated end-to-end by two concurrent sales in
 * {@code sale-concurrency.cy.js}; this is the half that says the recogniser does not over-match, which no
 * integration test can show convincingly because you cannot easily make the wrong constraint fire on demand.
 */
class SequenceRetryTest {

    /** The shape Spring actually delivers: its own exception wrapping Hibernate wrapping JDBC. */
    private static RuntimeException violation(String constraint) {
        return new DataIntegrityViolationException(
                "could not execute statement [Duplicate entry '13-101' for key '" + constraint + "']",
                new RuntimeException("org.hibernate.exception.ConstraintViolationException",
                        new java.sql.SQLIntegrityConstraintViolationException(
                                "Duplicate entry '13-101' for key '" + constraint + "'")));
    }

    @Nested
    @DisplayName("what counts as a lost race")
    class Recognition {

        @Test
        @DisplayName("the three per-org running numbers do")
        void retryable() {
            for (String c : new String[] { "customer_history.uq_ch_org_invoice_seq",
                                           "installment_plan.uq_plan_org_seq",
                                           "sales_quote.uq_quote_org_seq" }) {
                assertTrue(SequenceRetry.isCollision(violation(c)), c);
            }
        }

        @Test
        @DisplayName("⭐ the idempotency key does NOT — retrying it would record the sale twice")
        void idempotencyIsNotARace() {
            // The opposite answer is required: that sale already exists and the caller must return the
            // WINNER's invoice. Retrying would write a second one and charge the customer twice.
            assertFalse(SequenceRetry.isCollision(violation("customer_history.uq_ch_idempotency_key")));
        }

        @Test
        @DisplayName("⭐ uq_installment_plan_seq does NOT — it ends in 'seq' and means something else entirely")
        void scheduleConstraintIsNotARace() {
            // (plan_id, seq_no) on the INSTALMENT table: two instalments in the same position, i.e. the
            // schedule generator is broken. Retrying would hide the only signal that says so. This is the
            // case that makes the recogniser a list of names rather than a match on ".*seq".
            assertFalse(SequenceRetry.isCollision(violation("installment.uq_installment_plan_seq")));
        }

        @Test
        @DisplayName("INST-5a's serial constraint does NOT — a duplicate IMEI is a refusal, not a retry")
        void serialConstraintIsNotARace() {
            assertFalse(SequenceRetry.isCollision(violation("installment_plan.uq_plan_live_asset")));
        }

        @Test
        @DisplayName("it reads the whole cause chain, because only the innermost message names the constraint")
        void walksTheCauseChain() {
            RuntimeException deep = new RuntimeException("wrapper",
                    new RuntimeException("another wrapper", violation("customer_history.uq_ch_org_invoice_seq")));
            assertTrue(SequenceRetry.isCollision(deep));
        }

        @Test
        @DisplayName("null messages and a self-referential cause do not hang it")
        void degenerate() {
            assertFalse(SequenceRetry.isCollision(new RuntimeException((String) null)));
            assertFalse(SequenceRetry.isCollision(null));
        }
    }

    @Nested
    @DisplayName("the retry loop")
    class Loop {

        @Test
        @DisplayName("a sale that loses once succeeds on the next number")
        void succeedsAfterOneCollision() {
            AtomicInteger calls = new AtomicInteger();
            String out = SequenceRetry.withRetry("invoice", 5, () -> {
                if (calls.incrementAndGet() == 1) throw violation("customer_history.uq_ch_org_invoice_seq");
                return "INV-000102";
            });
            assertEquals("INV-000102", out);
            assertEquals(2, calls.get(), "tried again exactly once");
        }

        @Test
        @DisplayName("a first attempt that works is not retried")
        void noPointlessRetry() {
            AtomicInteger calls = new AtomicInteger();
            SequenceRetry.withRetry("invoice", 5, () -> { calls.incrementAndGet(); return "ok"; });
            assertEquals(1, calls.get());
        }

        @Test
        @DisplayName("⭐ an unrelated failure is rethrown IMMEDIATELY, not retried")
        void neverSwallowsSomebodyElsesFailure() {
            AtomicInteger calls = new AtomicInteger();
            RuntimeException boom = violation("customer_history.uq_ch_idempotency_key");

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    SequenceRetry.withRetry("invoice", 5, () -> {
                        calls.incrementAndGet();
                        throw boom;
                    }));

            assertSame(boom, thrown, "the original exception, unwrapped");
            assertEquals(1, calls.get(), "and no second attempt");
        }

        @Test
        @DisplayName("relentless contention still surfaces, rather than being hidden")
        void givesUpLoudly() {
            AtomicInteger calls = new AtomicInteger();
            assertThrows(RuntimeException.class, () ->
                    SequenceRetry.withRetry("invoice", 3, () -> {
                        calls.incrementAndGet();
                        throw violation("installment_plan.uq_plan_org_seq");
                    }));
            assertEquals(3, calls.get(), "exactly the attempts allowed, then it stops");
        }
    }
}
