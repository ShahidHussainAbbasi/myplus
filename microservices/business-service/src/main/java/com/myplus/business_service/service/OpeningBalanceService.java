package com.myplus.business_service.service;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.common.web.exception.ValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OB-1 — what customers and suppliers owed BEFORE this shop started using MaxTheService.
 *
 * <h3>An opening balance is a DOCUMENT, not a number</h3>
 * {@code Customer.dueAmount} is derived: {@link CustomerService#recomputeDue} sums the invoice headers and
 * overwrites the column on every sale and every receipt. A figure written straight into it survives until
 * that customer's next transaction and then vanishes — silently, with no error, at the moment the shop is
 * most likely to trust it.
 *
 * <p>So this service writes a {@link CustomerHistory} (or {@link Purchase}) marked {@link #DOC_OPENING},
 * dated the tenant's cutover, carrying the amount and no lines. An end-to-end review of every reader of
 * those tables found that the balance, the statement, the aging, the FIFO allocator and the credit limit
 * then pick it up <b>with no code at all</b> — and exactly one reader had to be told to exclude it (the
 * dashboard's "sales by customer" chart, which is trade rather than migration).
 *
 * <h3>What this slice deliberately REFUSES</h3>
 * <ul>
 *   <li>posting with no cutover date — the whole migration is anchored to it, so it may not be defaulted</li>
 *   <li>changing the cutover date once anything has posted — that would re-date ledger entries</li>
 *   <li><b>reversing a PARTIALLY PAID opening balance</b> — a full reversal would unpick a receipt that has
 *       already been allocated and posted. That needs a net adjustment (OB-4), and refusing is the feature:
 *       OB-1 does the part it can do correctly rather than approximating the part it cannot.</li>
 * </ul>
 */
@Service
public class OpeningBalanceService {

    private static final Logger LOG = LoggerFactory.getLogger(OpeningBalanceService.class);

    /** Everything the till records. The default, and what every pre-OB-1 row was backfilled to. */
    public static final String DOC_SALE = "SALE";
    /** What was owed at cutover. Never trade, never revenue, never in the tax register. */
    public static final String DOC_OPENING = "OPENING";

    /** The date this organisation became the system of record. No default — see {@link #assertCutover}. */
    public static final String CUTOVER_KEY = "business.cutoverDate";
    /** Set true by the FIRST posting, so the date cannot move under documents already in the ledger. */
    public static final String LOCKED_KEY = "business.cutoverLocked";

    @Autowired private CustomerHistoryRepo customerHistoryRepo;
    @Autowired private CustomerRepo customerRepo;
    @Autowired private PurchaseRepo purchaseRepo;
    @Autowired private VenderRepo venderRepo;
    @Autowired private CustomerService customerService;
    @Autowired private IVenderService venderService;
    @Autowired private DocumentNumberService documentNumberService;
    /** Control 4 — a double-click or a retried timeout must not double a shop's opening receivables. */
    @Autowired private IdempotencyService idempotencyService;
    /** OB-1 — the books. Optional so a slim test context still builds a document. */
    @Autowired(required = false) private GlOutboxService glOutboxService;
    @Autowired(required = false) private com.myplus.common.settings.SettingsService settingsService;

    // ── the cutover date ────────────────────────────────────────────────────────────────────────

    /**
     * The tenant's cutover date, or a refusal naming the setting.
     *
     * <h3>No default, deliberately</h3>
     * A defaulted cutover date is a WRONG cutover date on every tenant that did not notice the field, and
     * every opening document is dated by it. "Today" is the most tempting and the most wrong: a shop that
     * starts entering balances a fortnight after go-live would date its whole migration into the wrong
     * period, and the error is invisible afterwards.
     */
    public LocalDate cutoverDate() {
        // getText(key) takes ONE argument and already returns null for a blank value — verified against
        // SettingsService rather than assumed, which is standard 0.
        String raw = settingsService == null ? null : settingsService.getText(CUTOVER_KEY);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception badDate) {
            // A malformed date is the same as none: refuse and name the setting, rather than guess a day.
            LOG.warn("cutoverDate is not a date: {}", raw);
            return null;
        }
    }

    public boolean cutoverLocked() {
        return settingsService != null && settingsService.getBool(LOCKED_KEY);
    }

    private LocalDate assertCutover() {
        LocalDate d = cutoverDate();
        if (d == null) {
            throw new ValidationException(
                    "Set the cutover date first — the date this business became the system of record. "
                            + "Settings → Configuration → Opening balances. Every opening balance is "
                            + "dated by it, so it cannot be guessed.");
        }
        return d;
    }

    /** Called by the first successful posting. Idempotent — writing true twice costs nothing. */
    private void lockCutover() {
        if (settingsService == null || cutoverLocked()) return;
        try {
            settingsService.set(LOCKED_KEY, "true");
        } catch (Exception e) {
            // A failed lock must not fail the posting: the document is already correct, and an unlocked
            // cutover is a smaller problem than a migration that half-committed.
            LOG.warn("could not lock the cutover date after posting", e);
        }
    }

    // ── posting ────────────────────────────────────────────────────────────────────────────────

    /**
     * Record what a CUSTOMER owed at cutover.
     *
     * @return {@code {invoiceNo, amount, cutoverDate}}
     */
    @Transactional
    public Map<String, Object> postCustomerOpening(Long orgId, Long userId, Long customerId,
                                                   BigDecimal amount, String reference,
                                                   String idempotencyKey) {
        Map<String, Object> replay = replayIfSeen(orgId, OP_CUSTOMER, idempotencyKey);
        if (replay != null) return replay;
        LocalDate cutover = assertCutover();
        BigDecimal owed = positive(amount);

        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ValidationException("No such customer."));
        assertSameOrg(orgId, customer.getOrganizationId(), "customer");

        CustomerHistory ch = new CustomerHistory();
        ch.setOrganizationId(orgId);
        ch.setUserId(userId);
        ch.setCustomer(customer);
        ch.setDocType(DOC_OPENING);
        // The CUTOVER date, never today. This is what makes an opening balance ageable and what tells it
        // apart from trade for ever afterwards.
        ch.setDated(cutover.atStartOfDay());
        ch.setUpdated(LocalDateTime.now());
        ch.setGrandTotal(owed);
        ch.setPaidAmount(BigDecimal.ZERO);
        // The existing convention: dueAmount = paid − bill, so it is NEGATIVE while the customer owes.
        // recomputeDue() negates the sum, which is why this row lands in the balance with no change to it.
        ch.setDueAmount(owed.negate());
        /*
         * ⚠ invoiceSeq is left NULL and the number comes from the OPENING series.
         *
         * maxInvoiceSeqForOrg() is COALESCE(MAX(invoiceSeq)), which ignores nulls — so an opening balance
         * cannot consume an INV- number, and a shop's invoice series stays unbroken across its migration.
         * A gap in an invoice series is the kind of thing an auditor asks about.
         */
        long seq = documentNumberService.next(orgId, DocumentNumberService.OPENING);
        ch.setInvoiceNo(String.format("OB-%06d", seq));
        ch.setCustomerPoNumber(trimToNull(reference));

        customerHistoryRepo.save(ch);
        customerService.recomputeDue(customer);   // the balance, the exposure and the aging all follow
        postToLedger("OPENING_AR", ch.getInvoiceNo(), owed, cutover);
        lockCutover();

        remember(orgId, OP_CUSTOMER, idempotencyKey, ch.getInvoiceNo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceNo", ch.getInvoiceNo());
        out.put("amount", owed);
        out.put("cutoverDate", cutover.toString());
        return out;
    }

    /** Record what this shop owed a SUPPLIER at cutover. The mirror of the above. */
    @Transactional
    public Map<String, Object> postVendorOpening(Long orgId, Long userId, Long venderId,
                                                 BigDecimal amount, String reference,
                                                 String idempotencyKey) {
        Map<String, Object> replay = replayIfSeen(orgId, OP_SUPPLIER, idempotencyKey);
        if (replay != null) return replay;
        LocalDate cutover = assertCutover();
        BigDecimal owed = positive(amount);

        Vender vendor = venderRepo.findById(venderId)
                .orElseThrow(() -> new ValidationException("No such supplier."));
        assertSameOrg(orgId, vendor.getOrganizationId(), "supplier");

        Purchase p = new Purchase();
        p.setOrganizationId(orgId);
        p.setUserId(userId);
        p.setVenderId(venderId);
        p.setDocType(DOC_OPENING);
        p.setDated(cutover.atStartOfDay());
        p.setTotalAmount(owed);
        p.setNetAmount(owed);
        p.setPaidAmount(BigDecimal.ZERO);
        p.setDueAmount(owed.negate());
        long seq = documentNumberService.next(orgId, DocumentNumberService.OPENING);
        p.setPurchaseInvoiceNo(String.format("OB-%06d", seq));

        purchaseRepo.save(p);
        venderService.recomputePayable(venderId);
        postToLedger("OPENING_AP", p.getPurchaseInvoiceNo(), owed, cutover);
        lockCutover();

        remember(orgId, OP_SUPPLIER, idempotencyKey, p.getPurchaseInvoiceNo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoiceNo", p.getPurchaseInvoiceNo());
        out.put("amount", owed);
        out.put("cutoverDate", cutover.toString());
        return out;
    }

    // ── correcting one ─────────────────────────────────────────────────────────────────────────

    /**
     * Reverse an opening balance that was entered wrongly.
     *
     * <h3>⚠ A PARTIALLY PAID document is REFUSED, and that refusal is the feature</h3>
     * Once a receipt has been allocated against an opening balance, a full reversal would unpick an
     * allocation that is already in the ledger — leaving a receipt pointing at a document that no longer
     * exists, and a customer balance nobody can explain. The owner's ruling is that this case needs a
     * controlled net adjustment (OB-4).
     *
     * <p>Approximating it here would produce a wrong ledger rather than a clear refusal, and a wrong
     * ledger is the one thing a migration must not leave behind.
     */
    @Transactional
    public Map<String, Object> reverseCustomerOpening(Long orgId, String invoiceNo, String reason) {
        if (trimToNull(reason) == null) {
            throw new ValidationException("A reason is required — a reversal is a ledger entry, and the "
                    + "next person to read it needs to know why it exists.");
        }
        CustomerHistory ch = customerHistoryRepo.findByOrganizationIdAndInvoiceNo(orgId, invoiceNo)
                .orElseThrow(() -> new ValidationException("No such opening balance: " + invoiceNo));

        if (!DOC_OPENING.equalsIgnoreCase(ch.getDocType())) {
            throw new ValidationException(invoiceNo + " is a sale, not an opening balance. Use the sale "
                    + "return or void path for a sale.");
        }
        BigDecimal paid = nz(ch.getPaidAmount());
        if (paid.signum() > 0) {
            throw new ValidationException("This opening balance has already been PAID in part ("
                    + paid.toPlainString() + " received), so it cannot be reversed — the receipt is "
                    + "allocated against it and posted. A correction on a part-settled opening balance "
                    + "needs an adjustment that keeps the payment history, which is not in this release.");
        }

        Customer customer = ch.getCustomer();
        customerHistoryRepo.delete(ch);
        if (customer != null) customerService.recomputeDue(customer);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reversed", invoiceNo);
        out.put("reason", reason.trim());
        return out;
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────

    /** This organisation's opening balances, for the migration screen. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long orgId) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate d = cutoverDate();
        out.put("cutoverDate", d == null ? null : d.toString());
        out.put("locked", cutoverLocked());
        out.put("customerTotal", nz(customerHistoryRepo.sumOpeningForOrg(orgId)));
        out.put("supplierTotal", nz(purchaseRepo.sumOpeningForOrg(orgId)));
        return out;
    }

    /**
     * Put the opening balance in the BOOKS: Dr AR / Cr Equity, or Dr Equity / Cr AP.
     *
     * <h3>⚠ Dated the CUTOVER, never today</h3>
     * This is the reason V60 exists. The outbox used to drop the event date and the relay stamped
     * {@code LocalDate.now()}, so an opening balance would have posted into the CURRENT period — landing a
     * migration in this month's accounts as though it were this month's trade, which is precisely the
     * failure this slice replaces. Passing the date is only half of it; the outbox had to learn to carry it.
     *
     * <h3>Enqueued, not called</h3>
     * {@code GlOutboxService.enqueue} writes to the outbox inside THIS transaction and delivers after commit.
     * A rolled-back opening balance therefore posts no journal, and a finance-service that is down delays
     * the posting rather than failing the migration — the same contract every sale already has.
     *
     * <p>The failure is logged and swallowed for the same reason the sale path swallows it: the document is
     * already correct and durable, and the relay retries. A migration that half-committed because the books
     * were briefly unreachable would be far worse than one whose journal arrives a minute late.
     */
    private void postToLedger(String eventType, String ref, BigDecimal amount, LocalDate cutover) {
        if (glOutboxService == null) return;
        try {
            glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                    .eventType(eventType)
                    .date(cutover)            // ⚠ the cutover, not today — see V60
                    .ref(ref)
                    .grandTotal(amount)
                    // No tax, no COGS, no inventory and nothing paid: there are no lines, and whatever was
                    // sold to create this debt was sold — and taxed — in a system that is not this one.
                    .paidAmount(BigDecimal.ZERO)
                    .build());
        } catch (Exception e) {
            LOG.warn("opening balance {} recorded, but the GL event could not be queued", ref, e);
        }
    }

    // ── idempotency (control 4) ────────────────────────────────────────────────────────────────

    private static final String OP_CUSTOMER = "openingBalanceCustomer";
    private static final String OP_SUPPLIER = "openingBalanceSupplier";

    /**
     * Has this exact posting already been made? Then return the FIRST document rather than a second one.
     *
     * <h3>Why this matters more here than almost anywhere else</h3>
     * A migration is a bulk act on a slow connection: the operator posts, the request times out, they press
     * it again. Without this the shop's opening receivables double — and nothing looks wrong, because two
     * valid documents for the same customer is a state the system otherwise permits (Q1 explicitly allows
     * several per party). The duplicate would be found by reconciling against the old system, months later,
     * which is precisely the check the shop is doing this migration to be able to trust.
     *
     * <p>Blank key = guard disabled, matching {@code receivePayment} and {@code addPurchase}. A caller that
     * sends no key is asking for no protection, and refusing them would break every existing integration.
     */
    private Map<String, Object> replayIfSeen(Long orgId, String op, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        return idempotencyService.find(orgId, op, idempotencyKey)
                .map(ref -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("invoiceNo", ref);
                    // Named so a caller can tell a replay from a first posting — a UI that said "recorded"
                    // twice would have the operator believing two balances exist.
                    out.put("replayed", true);
                    LocalDate d = cutoverDate();
                    out.put("cutoverDate", d == null ? null : d.toString());
                    return out;
                })
                .orElse(null);
    }

    /** Record the key AFTER the document exists, so a failed posting leaves nothing to replay. */
    private void remember(Long orgId, String op, String idempotencyKey, String ref) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        try {
            idempotencyService.record(orgId, op, idempotencyKey, ref);
        } catch (Exception e) {
            // The document is already durable. Losing the key costs a possible duplicate on a retry, which
            // is worse than nothing but far better than failing a posting that actually succeeded.
            LOG.warn("opening balance {} recorded, but its idempotency key was not", ref, e);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private static BigDecimal positive(BigDecimal v) {
        if (v == null || v.signum() <= 0) {
            throw new ValidationException("Enter the amount owed — a positive figure.");
        }
        return v;
    }

    /**
     * ⚠ The party must belong to the CALLER's organisation.
     *
     * The id arrives off the wire, and an id off the wire is not an id followed from a row the caller could
     * already see. Posting an opening balance against another tenant's customer would put a receivable on
     * somebody else's books.
     */
    private static void assertSameOrg(Long orgId, Long partyOrg, String what) {
        if (orgId == null || (partyOrg != null && !orgId.equals(partyOrg))) {
            throw new ValidationException("No such " + what + ".");
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
