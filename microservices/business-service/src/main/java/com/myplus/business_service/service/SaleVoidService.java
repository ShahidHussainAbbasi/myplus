package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.entity.PaymentMethod;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.repository.SaleReturnRepo;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.commerce.contracts.dto.StockReturnLine;
import com.myplus.commerce.contracts.dto.StockReturnRequest;

/**
 * Voiding an invoice — the books-safe reversal, extracted from {@code SellController.voidSell} so it has exactly
 * ONE implementation.
 *
 * <h3>Why it moved (OMS O1)</h3>
 * A storefront cancellation must reverse the sale it created, and after O1 there IS one. The reversal a
 * cancellation needs is precisely this: restore inventory, refund what was paid, zero the header, stamp VOID,
 * recompute the customer's due, and post the aggregate GL reversal. Copying that into the internal endpoint
 * would have produced a second reversal path — the exact defect O1 exists to delete — so the body moved here and
 * both callers use it.
 *
 * <h3>What stayed with the callers</h3>
 * Resolution and access control: finding the invoice, {@code inMyTenant}, {@code myStore}, and the
 * {@code VOID_INVOICE} privilege on the human-facing endpoint. Each caller knows its own trust rules; the
 * internal endpoint's rule is different (internal-secret + org match), and folding them together here would mean
 * one of them was wrong.
 *
 * <h3>What is intrinsic to voiding and therefore lives HERE</h3>
 * Already-void, a return already recorded, and the period lock. Those are properties of the operation, not of
 * who is asking, so every caller must get them.
 *
 * <p>A void is deliberately NOT a delete: the header survives, stamped VOID and read-only, and a
 * {@code SALE_RETURN} GL event nets Sales + AR back to zero. Hard-deleting a sale was retired precisely because
 * it bypassed all of this and silently drifted the books.
 */
@Service
public class SaleVoidService {

    private static final Logger LOG = LoggerFactory.getLogger(SaleVoidService.class);

    @Autowired private ISellService sellService;
    @Autowired private ICustomerHistoryService customerHistoryService;
    @Autowired private ICustomerService customerService;
    @Autowired private PaymentService paymentService;
    @Autowired private StoreCreditService storeCreditService;
    @Autowired private GlOutboxService glOutboxService;
    @Autowired private AuditService auditService;
    @Autowired private PeriodLockGuard periodLockGuard;
    @Autowired private SaleReturnRepo saleReturnRepo;
    /** Optional so a slim context still voids: a tenant with no plans must not need the repository. */
    @Autowired(required = false)
    private com.myplus.business_service.repository.InstallmentPlanRepo installmentPlanRepo;
    @Autowired private com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

    /** Why a void was refused. The caller turns this into its own wire shape (GenericResponse / HTTP status). */
    public static class VoidRefused extends RuntimeException {
        public VoidRefused(String message) { super(message); }
    }

    /**
     * Reverse an invoice in place.
     *
     * @param ch          the invoice, ALREADY resolved and access-checked by the caller
     * @param reason      free text recorded on the header and in the audit trail
     * @param quarantine  P11: pharmacy no-restock — returned stock goes to quarantine instead of sellable
     * @return the invoice's original grand total (what was reversed)
     * @throws VoidRefused when the invoice is already void or a return was already recorded against it
     */
    /**
     * {@code noRollbackFor} — these two are ANSWERS, not failures, and both are thrown by the guard clauses
     * below <b>before this method writes anything</b>, so there is nothing to undo.
     *
     * <p>Without it the refusal was unreadable. {@code voidSell} is itself {@code @Transactional}, so this
     * method PARTICIPATES in the caller's transaction; a RuntimeException leaving this proxy marks that
     * shared transaction rollback-only. The controller then catches the exception, returns its tidy
     * {@code FAILED "…the period is closed"} — and Spring throws {@code UnexpectedRollbackException} when it
     * tries to commit, which reaches the browser as
     * {@code {"status":"ERROR","message":"Transaction silently rolled back because it has been marked as
     * rollback-only"}}. The considered refusal message was replaced by plumbing, and the controller's catch
     * block was effectively dead code.
     *
     * <p>It affected all three refusals here — already-void, return-already-recorded, and period-closed —
     * i.e. every ordinary reason a shopkeeper cannot void an invoice. Note the sibling endpoints
     * ({@code addSell}, {@code updateSell}, {@code saleReturn}) do NOT have the bug purely because they call
     * {@code periodLockGuard.assertOpen} inline in the controller, where no inner proxy exists to mark
     * anything — the same code one call deeper behaves differently, which is why this was invisible.
     */
    @Transactional(noRollbackFor = { VoidRefused.class, PeriodClosedException.class })
    public BigDecimal voidInvoice(CustomerHistory ch, String reason, boolean quarantine, Long orgId, Long userId) {
        if (ch == null) throw new VoidRefused("Invoice not found.");
        if ("VOID".equals(ch.getStatus()))
            throw new VoidRefused("This invoice is already voided.");
        if (saleReturnRepo.countByInvoiceScoped(ch.getInvoiceNo(), orgId, userId) > 0)
            throw new VoidRefused("A return was already recorded on this invoice; void is not allowed. Reconcile manually.");
        // Period close: a void zeroes the ORIGINAL invoice in place, so its period must still be open.
        /*
         * A SALE SOLD ON TERMS — void only while it is still a mistake.
         *
         * Void and reverse are different instruments. Void says "this never happened"; a credit note, a
         * return or a repossession says "it happened and is now being unwound". The line between them is
         * whether anything DEPENDS on the document yet, which is the same rule the return check above
         * applies — and an instalment plan that has taken money is exactly such a dependant.
         *
         * Found live: a plan financing 85,000, still ACTIVE, against an invoice already VOID. The shop went
         * on chasing a sale that no longer existed — on the collections worklist, on the aging report and
         * on the customer's statement, with nothing anywhere saying why.
         *
         * So:
         *   nothing collected  the plan is cancelled with the sale (below, after the reversal) — a genuine
         *                      mis-key erases cleanly
         *   money collected    REFUSED here, naming the amount, because voiding would strand cash on a
         *                      document that no longer exists. Repossession is the instrument for that, and
         *                      it has an explicit forfeit rule; this message says so rather than leaving the
         *                      operator to guess which button was meant.
         */
        if (installmentPlanRepo != null && ch.getInvoiceNo() != null) {
            for (InstallmentPlan plan : installmentPlanRepo.findLiveByInvoiceNo(
                    ch.getOrganizationId(), ch.getInvoiceNo())) {
                BigDecimal collected = plan.getTotalPaid() == null ? BigDecimal.ZERO : plan.getTotalPaid();
                if (collected.signum() > 0) {
                    throw new VoidRefused("This sale is on installment plan " + plan.getPlanNo()
                            + " and " + collected.toPlainString() + " has already been collected against it. "
                            + "Void is not allowed — repossess the item instead, or raise a credit note.");
                }
            }
        }

        periodLockGuard.assertOpen(ch.getDated() != null ? ch.getDated().toLocalDate() : LocalDate.now());

        Long chId = ch.getCustomer_history_id();
        List<Sell> lines = sellService.findByInvoiceScoped(chId, orgId, userId);
        String reservationId = ch.getReservationId();

        // Reverse every line: restore inventory + accumulate COGS. The Sales/Tax/AR reversal uses the header's
        // POSTED totals (captured below), NOT per-line totalAmount (pre-discount) — see the GL enqueue note.
        BigDecimal retCost = BigDecimal.ZERO;
        for (Sell s : lines) {
            float qty = s.getQuantity() != null ? s.getQuantity() : 0f;
            if (s.getProductId() != null && reservationId != null) {
                StockReturnRequest rr = new StockReturnRequest(List.of(new StockReturnLine(s.getProductId(), qty)));
                rr.setQuarantine(quarantine);
                inventoryClient.returnStock(reservationId, rr);
            } else if (s.getProductId() != null) {
                inventoryClient.importStock(List.of(
                        StockImportLine.builder().productId(s.getProductId()).quantity(qty).build()));
            }
            retCost = retCost.add(nzbd(s.getCostPrice()).multiply(BigDecimal.valueOf(qty)));
            sellService.deleteById(s.getSellId());
        }

        // Header: return whatever was paid, zero the totals, stamp VOID. SF-5 Model B: the portion paid WITH store
        // credit is returned AS store credit (re-issued, not cash) so we don't hand out cash the sale never took;
        // the rest is a cash REFUND.
        BigDecimal refund = nzbd(ch.getPaidAmount());
        BigDecimal scPaid = paymentService.forInvoice(chId).stream()
                .filter(p -> p.getMethod() == PaymentMethod.STORE_CREDIT)
                .map(p -> nzbd(p.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditReissue = refund.min(scPaid);                 // credit portion → back as credit
        BigDecimal cashRefund = refund.subtract(creditReissue).max(BigDecimal.ZERO);
        if (cashRefund.signum() > 0)
            paymentService.refund(chId, cashRefund, orgId, userId);
        if (creditReissue.signum() > 0 && ch.getCustomer() != null && ch.getCustomer().getCustomerId() != null)
            storeCreditService.issue(ch.getCustomer().getCustomerId(), creditReissue, "RETURN", ch.getInvoiceNo());

        // GL reversal MUST mirror exactly what the SALE posted — the header totals (post-discount). Do NOT rebuild
        // from Sell.totalAmount (that is the PRE-discount qty×rate, so a discounted invoice would over-reverse Sales
        // AND AR by the discount amount, drifting the books). Capture the posted totals BEFORE zeroing the header.
        BigDecimal origSub = nzbd(ch.getSubTotal()), origTax = nzbd(ch.getTaxTotal()),
                origGrand = nzbd(ch.getGrandTotal());
        // The two whole-document legs the sale also posted. Captured with the rest and sent on the reversal,
        // or 4200 would keep a concession on a cancelled invoice, 4300 would keep a refunded delivery fee, and
        // — since delivery rides inside grandTotal — the reversing journal would not balance.
        BigDecimal origDiscount = nzbd(ch.getTradeDiscount()), origShipping = nzbd(ch.getShippingFee());
        // B2B-P3f: a void zeroes the header, so WITHOUT this the statement would read an issued value of 500
        // with nothing offsetting it and overstate every voided invoice by its full amount.
        if (ch.getIssuedTotal() == null)
            ch.setIssuedTotal(origGrand);
        ch.setSubTotal(BigDecimal.ZERO);
        ch.setTaxTotal(BigDecimal.ZERO);
        ch.setGrandTotal(BigDecimal.ZERO);
        // Zero these with the rest: a voided invoice is zeroed IN PLACE, and leaving a concession or a delivery
        // charge on a header whose totals are all zero would make the document contradict itself — and would
        // re-apply both if the invoice were ever re-priced through applyInvoice, which now reads them back.
        ch.setTradeDiscount(null);
        ch.setShippingFee(null);
        ch.setPaidAmount(BigDecimal.ZERO);
        ch.setDueAmount(BigDecimal.ZERO);
        ch.setStatus("VOID");
        ch.setVoidedBy(userId);
        ch.setVoidedAt(LocalDateTime.now());
        ch.setVoidReason(reason);
        ch.setUpdated(LocalDateTime.now());
        customerHistoryService.save(ch);

        Customer customer = ch.getCustomer();
        if (customer != null)
            customerService.recomputeDue(customer);

        // GL: one aggregate SALE_RETURN reversing the whole invoice with the SAME (post-discount) totals the sale
        // posted, so Sales + AR net back to zero exactly. COGS is per-line (cost is never discounted). Best-effort.
        try {
            if (origGrand.signum() > 0)
                glOutboxService.enqueue(PostingEventRequest.builder()
                        .eventType("SALE_RETURN").date(LocalDate.now()).ref(ch.getInvoiceNo())
                        .grandTotal(origGrand).subTotal(origSub).taxTotal(origTax).cost(retCost).paidAmount(refund)
                        .discountTotal(origDiscount).shippingFee(origShipping)   // reverse BOTH document legs
                        .method("CASH").storeCredit(creditReissue).build());   // re-issued credit portion → Cr 2200
        } catch (Exception glEx) {
            LOG.warn("voidInvoice GL reversal enqueue failed (void applied)", glEx);
        }

        /*
         * The sale is reversed, so the plan built on it must go too.
         *
         * Only reached when NOTHING was collected — the guard at the top of this method refuses the void
         * outright once money has been taken, so by here the plan is a mis-key with no payments behind it.
         *
         * Cancelling rather than deleting: the row is how anyone later explains why a plan number exists and
         * finances nothing. And CANCELLED is the status the rest of the feature already understands — the
         * collections worklist, the aging supplier and the statement all read only ACTIVE and DEFAULTED, so
         * they go quiet by themselves. Nothing else has to be told.
         */
        if (installmentPlanRepo != null && ch.getInvoiceNo() != null) {
            try {
                for (InstallmentPlan plan : installmentPlanRepo.findLiveByInvoiceNo(
                        ch.getOrganizationId(), ch.getInvoiceNo())) {
                    for (com.myplus.business_service.entity.Installment i : plan.getInstallments()) {
                        if (i.outstanding().signum() > 0) {
                            i.setStatus("WAIVED");   // reports zero outstanding, keeps its stored balance
                            i.setUpdated(LocalDateTime.now());
                        }
                    }
                    plan.setStatus("CANCELLED");
                    plan.setUpdated(LocalDateTime.now());
                    installmentPlanRepo.save(plan);
                    LOG.info("Void cancelled installment plan {} on invoice {}", plan.getPlanNo(), ch.getInvoiceNo());
                }
            } catch (Exception planEx) {
                // Best-effort, and deliberately: the invoice is already reversed. Failing here would leave a
                // voided sale that reports as un-voided, which is worse than a plan needing a second look.
                LOG.warn("voidInvoice could not cancel the plan on {} (void applied)", ch.getInvoiceNo(), planEx);
            }
        }

        auditService.record("VOID_SALE", "INVOICE", ch.getInvoiceNo(), origGrand, reason);   // #6
        return origGrand;
    }

    private static BigDecimal nzbd(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
