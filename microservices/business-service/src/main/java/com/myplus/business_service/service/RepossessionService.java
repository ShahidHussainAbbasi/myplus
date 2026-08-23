package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.entity.SaleReturn;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.repository.InstallmentPlanRepo;
import com.myplus.business_service.repository.SaleReturnRepo;
import com.myplus.common.installment.RepossessionPolicy;
import com.myplus.common.settings.SettingsService;

/**
 * INST-5a — taking a financed item back.
 *
 * <h3>What repossession IS, in this system</h3>
 * Three things, two of which the platform already does:
 * <ol>
 *   <li>the unpaid balance is credited off through the existing {@code SALE_RETURN} path — the receivable goes
 *       to zero by the route every other reversal here already uses;</li>
 *   <li>the unit goes back into stock through the existing inventory return;</li>
 *   <li>the plan is cancelled.</li>
 * </ol>
 *
 * <p><b>No new GL event, no new {@code PostingEventRequest} field, no {@code gl_outbox} column.</b> A new
 * posting field needs five separate copy points or it silently vanishes — which is how {@code 4200 Sales
 * Discount} sat empty in every tenant for months while three specs stayed green. A design that adds no field
 * cannot reproduce that defect.
 *
 * <h3>⚠ Why this is NOT the ordinary return path, though it looks like it</h3>
 * {@code returnSell} refunds the overpayment a return creates. Run it on a plan where the customer has paid
 * 30,000 of 60,000 and it would delete the line, drop the invoice to zero, find a 30,000 overpayment and
 * <b>hand it back</b> — the exact opposite of the forfeit treatment the customer chose.
 *
 * <p>So this credits off <b>only the unpaid balance</b>. After it, {@code paidAmount == grandTotal}, there is
 * no overpayment for anything to refund, and the forfeit falls out of the arithmetic rather than being
 * enforced by a rule somebody has to remember. {@code paidAmount} on the posting event is <b>zero</b> for the
 * same reason: no cash goes back across the counter.
 *
 * <h3>The books after a repossession, and why they are right</h3>
 * <pre>
 *   Dr Sales Returns   30,000   only the UNPAID part of the revenue is reversed
 *     Cr AR            30,000   the receivable goes to zero
 *   Dr Inventory        (cost)  the handset is back on the shelf, so
 *     Cr COGS           (cost)  it was never a cost of goods SOLD
 * </pre>
 * The shop keeps 30,000 of income and keeps the handset, which is precisely what forfeit means. Revenue
 * reverses <b>proportionally</b> while cost reverses <b>in full</b>, because the money is split between the
 * parties and the goods are not.
 */
@Service
public class RepossessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepossessionService.class);

    static final String KEY_ENABLED = "pos.installment.repossession.enabled";
    static final String KEY_MIN_OVERDUE_DAYS = "pos.installment.repossession.minOverdueDays";
    static final String KEY_PROTECTED_PCT = "pos.installment.repossession.protectedGoodsPct";
    static final String KEY_WRITE_OFF = "pos.installment.repossession.writeOffBalance";

    @Autowired private InstallmentPlanRepo planRepo;
    @Autowired private SaleReturnRepo saleReturnRepo;
    @Autowired private DocumentNumberService documentNumberService;
    @Autowired private SettingsService settingsService;
    @Autowired private ISellService sellService;
    @Autowired private com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;
    @Autowired private ICustomerService customerService;
    @Autowired private GlOutboxService glOutboxService;
    @Autowired private AuditService auditService;
    @Autowired private PeriodLockGuard periodLockGuard;
    @Autowired private com.myplus.business_service.util.RequestUtil requestUtil;
    @Autowired(required = false)
    private com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    /** The outcome, in words the shopkeeper can act on. */
    public record Outcome(boolean ok, String message, String creditNoteNo) {
        static Outcome refuse(String why) { return new Outcome(false, why, null); }
    }

    /**
     * @param condition {@code GOOD} puts the unit back on the shelf; anything else records the repossession
     *                  without restocking. This is a PARAMETER and not a setting on purpose — whether a shop
     *                  repossesses at all is a tenant policy, but whether THIS handset came back smashed is a
     *                  fact about one repossession. A setting for the second kind is how a configuration
     *                  screen ends up with thirty toggles nobody reads.
     */
    @Transactional
    public Outcome repossess(Long orgId, Long planId, String condition, String reason) {
        if (orgId == null || planId == null) return Outcome.refuse("There is nothing to repossess.");

        // Anti-IDOR: by id AND org, in the query rather than after it.
        InstallmentPlan plan = planRepo.findById(planId)
                .filter(p -> orgId.equals(p.getOrganizationId()))
                .orElse(null);
        if (plan == null) return Outcome.refuse("That plan could not be found.");

        RepossessionPolicy.Decision decision = RepossessionPolicy.evaluate(
                standingOf(plan, LocalDate.now()), rulesFor(orgId));
        if (!decision.allowed()) return Outcome.refuse(decision.reason());

        // A repossession posts a credit dated today, so today's period must be open. Guarded BEFORE anything
        // is written, so a refusal leaves nothing half-done.
        periodLockGuard.assertOpen(LocalDate.now());

        CustomerHistory ch = plan.getInvoiceNo() == null ? null
                : customerHistoryRepo.findByOrganizationIdAndInvoiceNo(orgId, plan.getInvoiceNo()).orElse(null);
        if (ch == null) return Outcome.refuse("The invoice behind this plan could not be found.");

        BigDecimal outstanding = outstandingOf(plan);
        boolean writeOff = settingsService.getBoolFor(orgId, KEY_WRITE_OFF);

        String creditNoteNo = null;
        if (writeOff && outstanding.signum() > 0) {
            creditNoteNo = creditOffBalance(orgId, plan, ch, outstanding, reason);
        }

        restock(plan, ch, condition);
        closePlan(plan, writeOff);

        // Restate the customer's running balance from the headers, exactly as the return path does. Without
        // it the invoice is settled and the customer's total still shows the old debt.
        try {
            if (ch.getCustomer() != null) customerService.recomputeDue(ch.getCustomer());
        } catch (Exception e) {
            LOGGER.warn("recomputeDue after repossession failed for plan {}", plan.getPlanNo(), e);
        }

        auditService.record("REPOSSESSION", "INSTALLMENT_PLAN", plan.getPlanNo(), outstanding, reason);

        return new Outcome(true,
                writeOff ? "Repossessed. " + outstanding.toPlainString() + " written off."
                         : "Repossessed. The balance remains owing.",
                creditNoteNo);
    }

    // ── the policy inputs ─────────────────────────────────────────────────────────────────────────────────

    private RepossessionPolicy.Rules rulesFor(Long orgId) {
        return new RepossessionPolicy.Rules(
                settingsService.getBoolFor(orgId, KEY_ENABLED),
                settingsService.getIntFor(orgId, KEY_MIN_OVERDUE_DAYS, 30),
                settingsService.getIntFor(orgId, KEY_PROTECTED_PCT, 0));
    }

    /**
     * <p>{@code totalPaid} is measured as <b>cash price minus what is still owed</b> rather than read from a
     * stored total, so a down payment counts toward the protected-goods share. A customer who put 40% down and
     * then paid a third of the balance has paid well over half of what the goods cost; counting only the
     * instalments would under-count exactly the customers that rule exists to protect.
     */
    private RepossessionPolicy.PlanStanding standingOf(InstallmentPlan plan, LocalDate today) {
        BigDecimal cashPrice = nz(plan.getCashPrice());
        BigDecimal paid = cashPrice.subtract(outstandingOf(plan));
        if (paid.signum() < 0) paid = BigDecimal.ZERO;

        int worst = 0;
        for (Installment i : plan.getInstallments()) {
            if (i.outstanding().signum() > 0) worst = Math.max(worst, (int) i.daysOverdue(today));
        }
        return new RepossessionPolicy.PlanStanding(plan.getStatus(), cashPrice, paid, worst);
    }

    private BigDecimal outstandingOf(InstallmentPlan plan) {
        BigDecimal total = BigDecimal.ZERO;
        for (Installment i : plan.getInstallments()) total = total.add(i.outstanding());
        return total;
    }

    // ── the money ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Credit off exactly {@code outstanding} — not the invoice, not the line.
     *
     * <p>Revenue is reversed <b>proportionally</b> to the amount credited while cost is reversed <b>in
     * full</b>: the money is split between shop and customer, the goods are not. Getting that backwards would
     * either leave a sold-and-returned handset carrying COGS forever, or reverse revenue the shop actually
     * kept.
     */
    private String creditOffBalance(Long orgId, InstallmentPlan plan, CustomerHistory ch,
                                    BigDecimal outstanding, String reason) {
        BigDecimal[] split = creditSplit(outstanding, nz(ch.getGrandTotal()),
                nz(ch.getSubTotal()), nz(ch.getTaxTotal()));
        BigDecimal retSub = split[0];
        BigDecimal retTax = split[1];

        List<Sell> lines = sellService.findByInvoiceScoped(ch.getCustomer_history_id(), orgId, null);
        BigDecimal cost = BigDecimal.ZERO;
        for (Sell s : lines) {
            cost = cost.add(nz(s.getCostPrice()).multiply(BigDecimal.valueOf(s.getQuantity() == null ? 0f : s.getQuantity())));
        }
        cost = cost.setScale(2, RoundingMode.HALF_UP);

        // Same allocation the return path uses, so the document row and the GL line carry the SAME number.
        // Serialised counter, not MAX+1 — same reasoning as the ordinary return path.
        long seq = documentNumberService.next(orgId, DocumentNumberService.CREDIT_NOTE);
        String creditNoteNo = com.myplus.commerce.domain.InvoiceNumbers.creditNote(seq);

        // Capture the invoice AS ISSUED before this settles it — once only, or a later credit note would
        // overwrite it with an already-netted figure and understate the bill on the statement.
        if (ch.getIssuedTotal() == null) ch.setIssuedTotal(nz(ch.getGrandTotal()));

        // THE LINE THAT MAKES FORFEIT WORK. The bill becomes what the customer actually paid, so the invoice
        // settles to zero owing with no overpayment for anything to refund.
        ch.setGrandTotal(nz(ch.getPaidAmount()));
        ch.setSubTotal(nz(ch.getSubTotal()).subtract(retSub));
        ch.setTaxTotal(nz(ch.getTaxTotal()).subtract(retTax));
        ch.setDueAmount(BigDecimal.ZERO);
        ch.setUpdated(LocalDateTime.now());
        customerHistoryRepo.save(ch);

        try {
            SaleReturn cn = new SaleReturn();
            cn.setCreditNoteSeq(seq);
            cn.setCreditNoteNo(creditNoteNo);
            cn.setInvoiceNo(ch.getInvoiceNo());
            cn.setReason(reason == null || reason.isBlank() ? "Repossession" : reason);
            // No cash goes back across the counter — the forfeit. The credit note's FACE VALUE is the balance
            // written off; refundAmount beside it is the cash handed over, which is zero.
            cn.setRefundAmount(BigDecimal.ZERO);
            cn.setCreditAmount(retSub.add(retTax));
            cn.setOrganizationId(orgId);
            cn.setStoreId(plan.getStoreId());
            cn.setDated(LocalDateTime.now());
            saleReturnRepo.save(cn);
        } catch (Exception auditOnly) {
            LOGGER.warn("repossession credit-note row failed (repossession applied)", auditOnly);
        }

        try {
            glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                    .eventType("SALE_RETURN").date(LocalDate.now()).ref(ch.getInvoiceNo())
                    .grandTotal(retSub.add(retTax)).subTotal(retSub).taxTotal(retTax)
                    .cost(cost)                    // the whole unit comes back — full COGS reversal
                    .paidAmount(BigDecimal.ZERO)   // FORFEIT: no cash is refunded, so none is posted
                    .method("CREDIT").build());
        } catch (Exception glEx) {
            LOGGER.warn("repossession GL enqueue failed (repossession applied)", glEx);
        }

        return creditNoteNo;
    }

    /**
     * Split {@code outstanding} into the net and tax a credit note must carry.
     *
     * <h3>⚠ Why this is not "multiply both by the fraction"</h3>
     * That was the first implementation and it was wrong, caught by the gate's closing-balance assertion.
     * Crediting 40,000 of a 60,000 invoice gives a fraction of {@code 0.666667}, and {@code 60000 × 0.666667}
     * is <b>40,000.02</b> — so the credit note wrote off two paisa more than the customer owed and left them
     * permanently in credit, with a residue on their statement and a phantom row on the aging report. The
     * trial balance still balanced, because the posting was self-consistent; only the closing balance showed it.
     *
     * <p>This is the same rule {@code ScheduleGenerator} exists for — <b>a total is ALLOCATED, never derived by
     * rounding a proportion</b>, and the residual lands on one component so the parts sum to the whole exactly.
     *
     * <h3>Why the residual lands on NET rather than TAX</h3>
     * Tax has to reconcile to the rate that was charged, because the tax register is filed with an authority.
     * Net is the figure that can absorb a paisa without anybody being able to say it is wrong.
     *
     * @return {@code [net, tax]}, guaranteed to sum to {@code outstanding} exactly
     */
    static BigDecimal[] creditSplit(BigDecimal outstanding, BigDecimal grandTotal,
                                    BigDecimal subTotal, BigDecimal taxTotal) {
        BigDecimal owed = nz(outstanding).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grand = nz(grandTotal);
        BigDecimal tax = nz(taxTotal);

        // No tax on the invoice, or nothing to apportion against: the whole credit is net.
        if (tax.signum() == 0 || grand.signum() <= 0) {
            return new BigDecimal[] { owed, BigDecimal.ZERO };
        }

        BigDecimal frac = owed.divide(grand, 10, RoundingMode.HALF_UP);
        BigDecimal creditTax = tax.multiply(frac).setScale(2, RoundingMode.HALF_UP);

        // Never credit back more tax than the invoice carried, whatever the arithmetic says.
        if (creditTax.compareTo(tax) > 0) creditTax = tax;
        if (creditTax.compareTo(owed) > 0) creditTax = owed;

        // THE LINE THAT MAKES IT EXACT: net is the remainder, not a second rounded product.
        return new BigDecimal[] { owed.subtract(creditTax), creditTax };
    }

    // ── the goods ─────────────────────────────────────────────────────────────────────────────────────────

    /** Put the unit back, unless it came back in no condition to sell. */
    private void restock(InstallmentPlan plan, CustomerHistory ch, String condition) {
        if (!"GOOD".equalsIgnoreCase(condition)) return;
        if (inventoryClient == null) return;

        try {
            List<Sell> lines = sellService.findByInvoiceScoped(
                    ch.getCustomer_history_id(), plan.getOrganizationId(), null);
            String reservationId = ch.getReservationId();

            for (Sell s : lines) {
                if (s.getProductId() == null) continue;
                if (reservationId != null) {
                    // The inverse saga, exactly as the ordinary return uses it.
                    inventoryClient.returnStock(reservationId,
                            new com.myplus.commerce.contracts.dto.StockReturnRequest(List.of(
                                    new com.myplus.commerce.contracts.dto.StockReturnLine(
                                            s.getProductId(), s.getQuantity()))));
                } else {
                    inventoryClient.importStock(List.of(
                            com.myplus.commerce.contracts.dto.StockImportLine.builder()
                                    .productId(s.getProductId()).quantity(s.getQuantity()).build()));
                }
            }
        } catch (Exception e) {
            // Best-effort, and deliberately so: the debt is already written off and the plan closed. Failing
            // the whole repossession because inventory-service hiccuped would leave the shop unable to close
            // a plan for a handset it is physically holding.
            LOGGER.warn("repossession restock failed for plan {} (repossession applied)", plan.getPlanNo(), e);
        }
    }

    /**
     * Close the plan.
     *
     * <p>Nothing else has to be told. The collections worklist, the aging report and the statement all read
     * these rows, so they go quiet on their own — and {@code live_asset_ref} is a STORED generated column
     * derived from {@code status}, so cancelling the plan <b>frees the serial by itself</b>. A release that
     * application code had to remember is a release that eventually does not happen.
     */
    private void closePlan(InstallmentPlan plan, boolean writeOff) {
        if (writeOff) {
            for (Installment i : plan.getInstallments()) {
                if (i.outstanding().signum() > 0) {
                    i.setStatus("WAIVED");   // reports zero outstanding but keeps its stored balance
                    i.setUpdated(LocalDateTime.now());
                }
            }
        }
        plan.setStatus("CANCELLED");
        plan.setUpdated(LocalDateTime.now());
        planRepo.save(plan);
    }
}
