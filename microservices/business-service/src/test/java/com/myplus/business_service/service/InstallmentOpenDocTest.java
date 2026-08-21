package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.common.installment.Frequency;
import com.myplus.common.installment.PlanTerms;
import com.myplus.common.installment.ScheduleGenerator;
import com.myplus.common.installment.ScheduledAmount;

/**
 * INST-1 — an {@link Installment} behaving as an {@code OpenDoc}, which is the whole of requirement R3.
 *
 * <p>Pure: no Spring, no repository, no container. These are the rules the shared allocator will rely on when
 * a receipt lands on a plan, and standard D2a means a rule that only a Testcontainers test covers is a rule
 * whose test can silently skip.
 */
class InstallmentOpenDocTest {

    private static final LocalDate JAN_15 = LocalDate.of(2026, 1, 15);
    private static final LocalDate MAR_01 = LocalDate.of(2026, 3, 1);

    private static Installment scheduled(int seq, String amount, LocalDate due) {
        Installment i = new Installment();
        i.setSeqNo(seq);
        i.setDueDate(due);
        i.setAmount(new BigDecimal(amount));
        i.setPaidAmount(BigDecimal.ZERO);
        i.setOutstanding(new BigDecimal(amount));
        i.setStatus(Installment.SCHEDULED);
        return i;
    }

    // ── applying money ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_full_payment_settles_the_installment() {
        Installment i = scheduled(1, "8000.00", JAN_15);

        i.apply(new BigDecimal("8000.00"));

        assertThat(i.getStatus()).isEqualTo(Installment.PAID);
        assertThat(i.outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(i.getPaidAmount()).isEqualByComparingTo(new BigDecimal("8000.00"));
    }

    @Test
    void a_part_payment_leaves_it_PARTIAL_with_the_exact_residual() {
        // The state an invoice adapter never has to express, and the reason Installment implements OpenDoc
        // itself rather than through an inline anonymous class.
        Installment i = scheduled(1, "8000.00", JAN_15);

        i.apply(new BigDecimal("3000.00"));

        assertThat(i.getStatus()).isEqualTo(Installment.PARTIAL);
        assertThat(i.outstanding()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(i.getPaidAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
    }

    @Test
    void successive_part_payments_accumulate_and_settle() {
        Installment i = scheduled(1, "8000.00", JAN_15);

        i.apply(new BigDecimal("3000.00"));
        i.apply(new BigDecimal("5000.00"));

        assertThat(i.getStatus()).isEqualTo(Installment.PAID);
        assertThat(i.outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void outstanding_is_floored_at_zero_so_an_overpayment_never_reads_as_a_credit() {
        // Overpayment on a plan belongs in store credit (2200) through the existing path. A negative balance
        // here would quietly become a credit the ledger has never heard of.
        Installment i = scheduled(1, "8000.00", JAN_15);

        i.apply(new BigDecimal("9000.00"));

        assertThat(i.outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(i.outstanding().signum()).isNotNegative();
    }

    @Test
    void applying_nothing_changes_nothing() {
        Installment i = scheduled(1, "8000.00", JAN_15);

        i.apply(BigDecimal.ZERO);
        i.apply(null);

        assertThat(i.getStatus()).isEqualTo(Installment.SCHEDULED);
        assertThat(i.outstanding()).isEqualByComparingTo(new BigDecimal("8000.00"));
    }

    // ── waived ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_WAIVED_installment_offers_nothing_to_the_allocator() {
        Installment i = scheduled(1, "8000.00", JAN_15);
        i.setStatus(Installment.WAIVED);

        // The stored balance is left intact as the record of what was forgiven; outstanding() is what the
        // allocator sees, and it must be zero or money would land on a debt the owner cancelled.
        assertThat(i.outstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(i.getOutstanding()).as("the stored figure survives as the record")
                .isEqualByComparingTo(new BigDecimal("8000.00"));
    }

    @Test
    void money_cannot_silently_un_waive_an_installment() {
        Installment i = scheduled(1, "8000.00", JAN_15);
        i.setStatus(Installment.WAIVED);

        i.apply(new BigDecimal("1000.00"));

        assertThat(i.getStatus()).as("an owner's decision is not undone by a payment")
                .isEqualTo(Installment.WAIVED);
    }

    // ── overdue is DERIVED ──────────────────────────────────────────────────────────────────────────────

    @Test
    void overdue_is_a_predicate_over_the_date_and_the_balance_never_a_stored_status() {
        Installment past = scheduled(1, "8000.00", JAN_15);
        Installment future = scheduled(2, "8000.00", MAR_01.plusMonths(1));

        assertThat(past.isOverdue(MAR_01)).isTrue();
        assertThat(future.isOverdue(MAR_01)).isFalse();
        assertThat(past.daysOverdue(MAR_01)).isEqualTo(45L);   // 15 Jan → 1 Mar 2026

        // Paid in full, so no longer overdue however old it is — the balance is half the predicate.
        past.apply(new BigDecimal("8000.00"));
        assertThat(past.isOverdue(MAR_01)).isFalse();
        assertThat(past.daysOverdue(MAR_01)).isZero();
    }

    @Test
    void an_installment_due_TODAY_is_not_yet_overdue() {
        // Off-by-one at the boundary is how a shop texts a customer on the morning the money is due.
        Installment i = scheduled(1, "8000.00", MAR_01);

        assertThat(i.isOverdue(MAR_01)).isFalse();
        assertThat(i.isOverdue(MAR_01.plusDays(1))).isTrue();
    }

    // ── the receipt line ────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_doc_type_is_INSTALLMENT_which_the_finance_ledger_stores_as_is() {
        // payment_allocations.doc_type is a free-form VARCHAR(20), so this records with ZERO
        // finance-service change — the reason no new contract was needed for R3.
        assertThat(scheduled(1, "8000.00", JAN_15).docType()).isEqualTo("INSTALLMENT");
    }

    @Test
    void the_doc_no_names_the_invoice_and_the_position_within_it() {
        Installment i = scheduled(3, "8000.00", JAN_15);
        i.setDocNo("INV-000123/3");

        assertThat(i.docNo()).isEqualTo("INV-000123/3");
    }

    @Test
    void the_doc_no_falls_back_to_a_readable_label_rather_than_null() {
        // A receipt line reading "null" is worse than one reading "INSTALLMENT/3".
        assertThat(scheduled(3, "8000.00", JAN_15).docNo()).isEqualTo("INSTALLMENT/3");
    }

    // ── the plan totals ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_plan_reports_what_is_owed_paid_and_next_due_from_its_rows() {
        InstallmentPlan plan = planOf("30000", 3);

        assertThat(plan.getTotalOutstanding()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(plan.getNextDue().getSeqNo()).isEqualTo(1);

        plan.getInstallments().get(0).apply(new BigDecimal("10000.00"));

        assertThat(plan.getTotalPaid()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(plan.getTotalOutstanding()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(plan.getNextDue().getSeqNo()).as("the first still-owing row").isEqualTo(2);
    }

    @Test
    void a_settled_plan_has_no_next_due() {
        InstallmentPlan plan = planOf("30000", 3);
        plan.getInstallments().forEach(i -> i.apply(i.outstanding()));

        assertThat(plan.getTotalOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(plan.getNextDue()).isNull();
    }

    @Test
    void THE_INVARIANT_the_plans_rows_sum_to_the_financed_amount() {
        // Design D5: the plan holds no money the general ledger does not already know about. Σ(installments)
        // must equal the plan invoice's outstanding, and this is that equality at the point of construction.
        InstallmentPlan plan = planOf("12345.67", 7);

        assertThat(plan.getTotalOutstanding()).isEqualByComparingTo(plan.getFinancedAmount());
    }

    @Test
    void overdue_count_drives_the_collections_worklist() {
        InstallmentPlan plan = planOf("30000", 3);   // due 15 Jan, 15 Feb, 15 Mar

        assertThat(plan.overdueCount(LocalDate.of(2026, 2, 20))).isEqualTo(2L);
        assertThat(plan.overdueCount(LocalDate.of(2026, 1, 1))).isZero();
    }

    @Test
    void only_ACTIVE_and_DEFAULTED_plans_can_take_money() {
        InstallmentPlan plan = planOf("30000", 3);

        plan.setStatus(InstallmentPlan.ACTIVE);
        assertThat(plan.isCollectable()).isTrue();
        plan.setStatus(InstallmentPlan.DEFAULTED);
        assertThat(plan.isCollectable()).as("customers do come back").isTrue();

        for (String terminal : new String[] { InstallmentPlan.COMPLETED, InstallmentPlan.CANCELLED,
                InstallmentPlan.WRITTEN_OFF, InstallmentPlan.DRAFT }) {
            plan.setStatus(terminal);
            assertThat(plan.isCollectable()).as("%s must not accept money", terminal).isFalse();
        }
    }

    /** A plan carrying a real generated schedule, so the totals are the library's, not hand-written. */
    private static InstallmentPlan planOf(String financed, int count) {
        PlanTerms terms = PlanTerms.of(new BigDecimal(financed), BigDecimal.ZERO, count,
                Frequency.MONTHLY, JAN_15);

        InstallmentPlan plan = new InstallmentPlan();
        plan.setFinancedAmount(terms.financedAmount());
        plan.setInstallmentCount(count);
        plan.setFirstDueDate(JAN_15);
        plan.setStatus(InstallmentPlan.ACTIVE);

        for (ScheduledAmount s : ScheduleGenerator.generate(terms)) {
            // addInstallment, not getInstallments().add — it sets BOTH sides, which is what production
            // does. These cases never persist, so they cannot catch a JPA mapping fault (plan_id arriving
            // null did reach the gate). Building the graph the same way at least keeps the two honest.
            plan.addInstallment(scheduled(s.seqNo(), s.amount().toPlainString(), s.dueDate()));
        }
        return plan;
    }
}
