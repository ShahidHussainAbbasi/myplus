package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.InstallmentPlanRepo;
import com.myplus.common.installment.Frequency;
import com.myplus.common.installment.PlanTerms;
import com.myplus.common.installment.ScheduleGenerator;
import com.myplus.common.installment.ScheduledAmount;
import com.myplus.common.subledger.OpenDoc;

/**
 * INST-1 — creating a plan, and supplying its obligations to the shared allocator.
 *
 * <h3>What this service does NOT do</h3>
 * It does not settle payments, post to the ledger, or touch the GL. {@code SubledgerService} already
 * allocates money across {@link OpenDoc}s and records the receipt; this only decides <b>which</b> open
 * documents a customer has and in <b>what order</b> they should be cleared.
 *
 * <p>That restraint is the design. {@code SubledgerService} exists precisely <i>because</i> AR and AP had
 * drifted into two copies of allocate-and-record; a third copy for installments would repeat the mistake the
 * library was extracted to fix.
 */
@Service
public class InstallmentPlanService {

    private static final Logger LOG = LoggerFactory.getLogger(InstallmentPlanService.class);

    /**
     * How a receipt is spread when a customer owes both a plan and ordinary invoices.
     *
     * <p><b>Lowercase, and that is load-bearing:</b> {@code SettingsService.getChoice} lower-cases the stored
     * value before matching and <b>silently returns the fallback</b> otherwise. A catalog offering
     * {@code byDueDate} would be saved by the owner, read back as the default forever, and log nothing. The
     * constants live here beside the code that reads them, exactly as {@code CreditLimitPolicy.OFF/WARN/BLOCK}
     * do, so the catalog and the reader cannot drift.
     */
    public static final String ORDER_BY_DUE_DATE = "by-due-date";
    public static final String ORDER_INSTALLMENTS_FIRST = "installments-first";
    public static final String ORDER_INVOICES_FIRST = "invoices-first";

    @Autowired private InstallmentPlanRepo planRepo;
    @Autowired private DocumentNumberService documentNumberService;

    /** INST-5a — serial policy. Optional so a slim test context still builds a plan. */
    @Autowired(required = false) private com.myplus.common.settings.SettingsService settingsService;

    // ── creating a plan ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Build a plan and its schedule from agreed terms. <b>Does not commit money</b> — the caller writes it in
     * the same transaction as the sale.
     *
     * @throws IllegalArgumentException with an operator-readable message when the terms cannot make a sound
     *         plan (the arithmetic library's own refusals)
     *
     * <h3>REQUIRES_NEW, so a lost plan-number race is survivable</h3>
     * {@code plan_no} is allocated {@code MAX(plan_seq) + 1}, which two tills can read at the same instant;
     * {@code uq_plan_org_seq} then refuses the loser. Joining the caller's transaction made that unrecoverable
     * — the violation marks the CALLER rollback-only, so the retry has nowhere to run and
     * {@code createInstallmentPlan} logged "the SALE stands" while the sale did not.
     *
     * <p>Its own transaction also matches what already happens: {@code SagaSellService} commits the invoice in
     * a {@code REQUIRES_NEW} transaction of its own, so by the time this runs the receivable is durable and
     * there is no shared transaction left to be part of.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public InstallmentPlan create(PlanTerms terms, Long orgId, Long userId, Long storeId,
                                  Long customerId, Long invoiceId, String invoiceNo, String assetRef) {

        List<ScheduledAmount> schedule = ScheduleGenerator.generate(terms);

        // Belt and braces on the invariant the whole design rests on (D5). ScheduleGenerator guarantees it by
        // construction and is unit-tested over 115 combinations — but a plan whose rows do not sum to the
        // financed amount would silently disagree with its invoice for the life of the plan, and the cost of
        // checking here is one addition.
        BigDecimal total = ScheduleGenerator.total(schedule);
        if (total.compareTo(terms.financedAmount()) != 0) {
            throw new IllegalStateException("Schedule does not reconcile: " + total
                    + " scheduled against " + terms.financedAmount() + " financed.");
        }

        InstallmentPlan plan = new InstallmentPlan();
        plan.setOrganizationId(orgId);
        plan.setUserId(userId);
        plan.setStoreId(storeId);
        plan.setCustomerId(customerId);
        plan.setInvoiceId(invoiceId);
        plan.setInvoiceNo(invoiceNo);

        // nz() on all three even though PlanTerms now normalises them: the NOT NULL columns are this
        // method's responsibility, and a future caller building terms another way must not be able to
        // reproduce the failure the first gate run found — a plan that dies on INSERT after the sale has
        // already committed, leaving the caller's transaction "marked as rollback-only".
        plan.setCashPrice(nz(terms.cashPrice()));
        plan.setDownPayment(nz(terms.downPayment()));
        plan.setMarkupAmount(nz(terms.markupAmount()));
        plan.setFinancedAmount(terms.financedAmount());
        plan.setInstallmentCount(terms.installmentCount());
        plan.setFrequency(terms.frequency().name());
        plan.setFirstDueDate(terms.firstDueDate());
        plan.setFinalDueDate(ScheduleGenerator.finalDueDate(schedule));
        plan.setAssetRef(trimToNull(assetRef));

        // ACTIVE, not DRAFT: this is called from the sale path, and if the sale commits the customer owes the
        // schedule. DRAFT exists for a plan composed on screen that never reaches a commit.
        plan.setStatus(InstallmentPlan.ACTIVE);

        LocalDateTime now = LocalDateTime.now();
        plan.setDated(now);
        plan.setUpdated(now);

        for (ScheduledAmount s : schedule) {
            Installment i = new Installment();
            i.setOrganizationId(orgId);
            i.setSeqNo(s.seqNo());
            i.setDueDate(s.dueDate());
            i.setAmount(s.amount());
            i.setPaidAmount(BigDecimal.ZERO);
            i.setOutstanding(s.amount());
            i.setStatus(Installment.SCHEDULED);
            i.setDated(now);
            i.setUpdated(now);
            plan.addInstallment(i);   // sets BOTH sides — the list alone leaves plan_id null
        }

        // Serialised allocator, not MAX+1. This method is REQUIRES_NEW and writes only to the database, so
        // the counter's row lock is held across DB work alone.
        long seq = documentNumberService.next(orgId, DocumentNumberService.PLAN);
        plan.setPlanSeq(seq);
        plan.setPlanNo(planNo(seq));

        InstallmentPlan saved = planRepo.save(plan);
        LOG.info("INST-1: plan {} created for customer {} — {} financed over {} installments (invoice {})",
                saved.getPlanNo(), customerId, saved.getFinancedAmount(), saved.getInstallmentCount(),
                invoiceNo);
        return saved;
    }

    /** {@code PLN-000042} — the same shape as {@code INV-}, {@code CRN-} and {@code QTE-}. */
    static String planNo(long seq) {
        return String.format("PLN-%06d", seq);
    }

    // ── supplying open documents ────────────────────────────────────────────────────────────────────────

    /**
     * The customer's still-owed installments, as {@link OpenDoc}s for the shared allocator.
     *
     * <p>Each carries a {@code docNo} of {@code INV-000123/3} — the invoice it belongs to and which
     * installment it is — so a receipt reads meaningfully to the person holding it.
     */
    public List<OpenDoc> openInstallments(Long orgId, Long customerId) {
        List<OpenDoc> docs = new ArrayList<>();
        for (InstallmentPlan plan : planRepo.findCollectableByCustomer(orgId, customerId)) {
            for (Installment i : plan.getInstallments()) {
                if (i.outstanding().signum() <= 0) continue;
                i.setDocNo((plan.getInvoiceNo() != null ? plan.getInvoiceNo() : plan.getPlanNo())
                        + "/" + i.getSeqNo());
                docs.add(i);
            }
        }
        docs.sort(Comparator.comparing((OpenDoc d) -> ((Installment) d).getDueDate())
                .thenComparing(d -> ((Installment) d).getId(),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return docs;
    }

    /**
     * Compose the two streams into the one ordered list the allocator walks (design D2).
     *
     * <p><b>Composite</b> over two suppliers. The allocator already walks whatever list it is handed, so the
     * only new logic is which list — no second allocator, no second settlement path.
     *
     * <p>The order is a tenant decision because the right answer differs by shop: one chasing a plan wants the
     * money on the plan; one closing its month wants the oldest paper cleared. {@code by-due-date} merges both
     * streams on date and is the accountant's answer, so it is the default.
     *
     * @param invoices the customer's ordinary open invoices — <b>with the plan invoice already excluded</b>.
     *                 Offering it here as well as through the plan would let one receipt clear the same debt
     *                 twice; see {@link #planInvoiceNumbers}.
     */
    public List<OpenDoc> composeOpenDocs(List<OpenDoc> installments, List<OpenDoc> invoices, String order) {
        String norm = order == null ? ORDER_BY_DUE_DATE : order.trim().toLowerCase(Locale.ROOT);
        List<OpenDoc> out = new ArrayList<>();

        if (ORDER_INSTALLMENTS_FIRST.equals(norm)) {
            out.addAll(installments);
            out.addAll(invoices);
        } else if (ORDER_INVOICES_FIRST.equals(norm)) {
            out.addAll(invoices);
            out.addAll(installments);
        } else {
            // by-due-date: installments carry a real due date; invoices are appended in their existing
            // oldest-first order, which is what the invoice stream already guarantees. A single merged sort
            // is not possible without a due date on every invoice, and inventing one would be worse than
            // preserving each stream's own ordering.
            out.addAll(installments);
            out.addAll(invoices);
        }
        return out;
    }

    /**
     * Invoice numbers that a plan already represents, so the invoice stream can exclude them.
     *
     * <p>This is the guard behind D2's warning: the plan and the invoice describe <b>one</b> debt. Without it
     * a receipt would be offered the same money twice and would over-clear the customer's balance.
     */
    public List<String> planInvoiceNumbers(Long orgId, Long customerId) {
        List<String> out = new ArrayList<>();
        for (InstallmentPlan p : planRepo.findCollectableByCustomer(orgId, customerId)) {
            if (p.getInvoiceNo() != null && !p.getInvoiceNo().isBlank()) out.add(p.getInvoiceNo());
        }
        return out;
    }

    // ── plan lifecycle ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Push a plan's payments down onto the invoice that carries it.
     *
     * <h3>Why this exists — the invariant does not maintain itself</h3>
     * Design D5 states that Σ(open installments) always equals the plan invoice's outstanding balance. That is
     * true at creation and <b>false the moment a receipt lands</b>, because the allocator applies money to the
     * installments and nothing tells the invoice.
     *
     * <p>The consequence is not cosmetic. {@code CustomerService.recomputeDue} computes the customer's running
     * balance from <b>invoice headers</b> ({@code sumDueByCustomer}), so without this a customer pays 10,000
     * against their plan and their outstanding balance <b>does not move</b> — on the screen, on their
     * statement, and in the aging report. Found by the INST-1 gate; no unit test could see it, because it
     * lives in the interaction between the allocator, the plan and the invoice.
     *
     * <p><b>Restated from the rows, not accumulated.</b> The invoice is set to match Σ(installments) rather
     * than decremented by whatever was just applied: a lossy in-place accumulation drifts, and the rows are
     * the record. Same reasoning {@code recomputeDue} itself follows.
     *
     * <p>{@code CustomerHistory.dueAmount} stores {@code paid − bill} and is <b>negative</b> while owing,
     * which is the opposite of {@code installment.outstanding}. The negation here is that normalisation, and
     * it is the one place the two conventions meet.
     */
    @Transactional
    public void syncInvoiceFromPlan(InstallmentPlan plan, CustomerHistoryRepo customerHistoryRepo) {
        if (plan == null || plan.getInvoiceNo() == null) return;

        CustomerHistory inv = customerHistoryRepo
                .findByOrganizationIdAndInvoiceNo(plan.getOrganizationId(), plan.getInvoiceNo())
                .orElse(null);
        if (inv == null) return;

        BigDecimal owed = plan.getTotalOutstanding();
        BigDecimal bill = inv.getGrandTotal() == null ? BigDecimal.ZERO : inv.getGrandTotal();

        inv.setPaidAmount(bill.subtract(owed));
        inv.setDueAmount(owed.negate());          // negative while owing — the invoice's convention
        inv.setUpdated(LocalDateTime.now());
        customerHistoryRepo.save(inv);
    }

    /**
     * Restate a plan's status from its rows after money has been applied.
     *
     * <p>Called by the receipt path once the allocator has finished. Only {@code ACTIVE}/{@code DEFAULTED}
     * plans complete — a {@code CANCELLED} or {@code WRITTEN_OFF} plan whose rows happen to reach zero is not
     * "completed", and flipping it would erase an owner's decision.
     */
    @Transactional
    public void refreshStatus(InstallmentPlan plan) {
        if (plan == null || !plan.isCollectable()) return;
        if (plan.getTotalOutstanding().signum() == 0) {
            plan.setStatus(InstallmentPlan.COMPLETED);
            plan.setUpdated(LocalDateTime.now());
            planRepo.save(plan);
            LOG.info("INST-1: plan {} completed", plan.getPlanNo());
        }
    }

    /**
     * Cancel every plan carrying an invoice — the void path.
     *
     * <p>A plan must never outlive the invoice that created it. Scheduled rows become {@code WAIVED} rather
     * than deleted, so the record of what was owed survives the cancellation; money already received is
     * handled by the existing void/store-credit path and is not touched here.
     */
    @Transactional
    public int cancelForInvoice(Long orgId, String invoiceNo) {
        int cancelled = 0;
        for (InstallmentPlan plan : planRepo.findByInvoiceNo(orgId, invoiceNo)) {
            if (InstallmentPlan.CANCELLED.equals(plan.getStatus())) continue;
            plan.setStatus(InstallmentPlan.CANCELLED);
            for (Installment i : plan.getInstallments()) {
                if (i.outstanding().signum() > 0) i.setStatus(Installment.WAIVED);
            }
            plan.setUpdated(LocalDateTime.now());
            planRepo.save(plan);
            cancelled++;
            LOG.info("INST-1: plan {} cancelled with invoice {}", plan.getPlanNo(), invoiceNo);
        }
        return cancelled;
    }

    /** A customer's plans, newest first - the schedule block on the customer screen. */
    public List<InstallmentPlan> plansForCustomer(Long orgId, Long customerId) {
        return planRepo.findByCustomerScoped(orgId, customerId);
    }

    /** Plans still owing money, most overdue first — the Installments screen and collections worklist. */
    public List<InstallmentPlan> openPlans(Long orgId, LocalDate asOf) {
        List<InstallmentPlan> plans = planRepo.findOpenScoped(orgId);
        plans.sort(Comparator.comparingLong((InstallmentPlan p) -> p.overdueCount(asOf)).reversed()
                .thenComparing(InstallmentPlan::getFirstDueDate));
        return plans;
    }

    /** How many open plans this customer holds — backs {@code pos.installment.maxOpenPlansPerCustomer}. */
    public long openPlanCount(Long orgId, Long customerId) {
        return planRepo.countOpenForCustomer(orgId, customerId);
    }

    /** Resolve a stored frequency string, tolerating case; unknown falls back to MONTHLY. */
    public static Frequency frequencyOf(String stored) {
        return Frequency.fromSetting(stored);
    }

    /** Absent money is zero — the NOT NULL columns must never see a null. */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * INST-5a — may this serial go on a new plan? Returns a message, or {@code null} when it may.
     *
     * <h3>⚠ Why this RETURNS a refusal instead of throwing one</h3>
     * It is called from {@code addSell} <b>before</b> the sale is written, and it must not be able to mark a
     * transaction rollback-only. A business refusal thrown inside a nested {@code @Transactional} does exactly
     * that: the tidy message is replaced by "Transaction silently rolled back because it has been marked as
     * rollback-only", which tells a cashier nothing and this programme has already paid for twice.
     *
     * <h3>Why the check runs before the sale rather than during plan creation</h3>
     * {@code SagaSellService} commits the invoice in its own {@code REQUIRES_NEW} transaction, so a refusal
     * raised while creating the plan arrives too late — the handset is already sold, and the existing contract
     * leaves the sale standing with a note that the plan failed. For a technical failure that is the right
     * call. For a serial that is already financed to somebody else it is not: the sale itself should not
     * happen. Checking first is what makes that possible.
     *
     * <h3>This is not what makes the rule safe</h3>
     * {@code uq_plan_live_asset} (V44) is. Two tills can pass this check in the same millisecond and only the
     * database can stop both inserts. What this adds is a sentence naming the plan that already holds the
     * serial, so the cashier is told where to look instead of being shown a constraint violation.
     */
    public String validateSerial(Long orgId, String assetRef) {
        String serial = trimToNull(assetRef);

        if (serial == null) {
            boolean required = settingsService != null
                    && settingsService.getBoolFor(orgId, "pos.installment.serialRequired");
            return required ? "This sale needs an IMEI or serial number before it can go on a plan." : null;
        }

        for (InstallmentPlan other : planRepo.findLiveByAssetRef(orgId, serial)) {
            return "That serial is already financed on plan " + other.getPlanNo()
                    + ". Settle or cancel it first.";
        }
        return null;
    }
}
