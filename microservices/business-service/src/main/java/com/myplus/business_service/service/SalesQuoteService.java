package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.QuoteStatus;
import com.myplus.business_service.entity.SalesQuote;
import com.myplus.business_service.entity.SalesQuoteLine;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.SalesQuoteRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.domain.InvoiceNumbers;
import com.myplus.common.security.AuthenticatedUser;

/**
 * B2B-P4b â€” the sales-quote lifecycle.
 *
 * <h3>One guarded write path</h3>
 * Every status change goes through {@link #transition}. Same shape as {@code PartyService.setAccountParent}
 * (4a): the invariants live on one method, so no caller can route around them. A quote's status decides whether
 * a customer can be billed from it, so "which transitions are legal" is not something to re-implement per
 * endpoint.
 *
 * <h3>The two approvals are different things</h3>
 * {@code PENDING_APPROVAL} is an INTERNAL permission gate â€” "may we offer this discount?" â€” and fires only when
 * the discount exceeds the org threshold. {@code ACCEPTED}/{@code REJECTED} record what the CUSTOMER decided.
 * Treating them as one status would mean either asking an owner to approve the customer's own answer, or
 * letting a big discount out of the building unapproved.
 *
 * <h3>Expiry is derived, never written</h3>
 * See {@link SalesQuote#getEffectiveStatus()}. No scheduled job touches a customer-facing document.
 */
@Service
public class SalesQuoteService {

    private static final Logger LOG = LoggerFactory.getLogger(SalesQuoteService.class);

    /** Org settings (common-settings catalog). Defaults keep the feature inert for a shop that ignores it. */
    static final String SETTING_VALIDITY_DAYS = "sales.quote.validityDays";
    static final String SETTING_DISCOUNT_THRESHOLD = "sales.quote.discountApprovalThreshold";
    static final int DEFAULT_VALIDITY_DAYS = 30;

    /**
     * The legal moves. Everything not listed here is refused â€” a whitelist, not a blacklist, because the failure
     * mode of a missed illegal transition is a document that bills a customer from a rejected offer.
     */
    private static final Map<QuoteStatus, Set<QuoteStatus>> ALLOWED = Map.of(
            QuoteStatus.DRAFT,            EnumSet.of(QuoteStatus.PENDING_APPROVAL, QuoteStatus.SENT, QuoteStatus.REJECTED),
            QuoteStatus.PENDING_APPROVAL, EnumSet.of(QuoteStatus.SENT, QuoteStatus.DRAFT, QuoteStatus.REJECTED),
            QuoteStatus.SENT,             EnumSet.of(QuoteStatus.ACCEPTED, QuoteStatus.REJECTED),
            QuoteStatus.ACCEPTED,         EnumSet.of(QuoteStatus.CONVERTED, QuoteStatus.REJECTED),
            QuoteStatus.REJECTED,         EnumSet.noneOf(QuoteStatus.class),
            QuoteStatus.EXPIRED,          EnumSet.noneOf(QuoteStatus.class),
            QuoteStatus.CONVERTED,        EnumSet.noneOf(QuoteStatus.class));

    @Autowired private SalesQuoteRepo quoteRepo;
    @Autowired private CustomerRepo customerRepo;
    @Autowired private RequestUtil requestUtil;
    @Autowired private SagaSellService sagaSellService;   // THE revenue path â€” quotes do not author invoices

    @Autowired(required = false)
    private com.myplus.common.settings.SettingsService settingsService;

    /** Refused for a reason the operator should see verbatim. */
    public static class QuoteRefused extends RuntimeException {
        public QuoteRefused(String message) { super(message); }
    }

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    // â”€â”€ create / price â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Raise a quote. Totals are computed from the lines HERE â€” the caller states what is being offered, never
     * what it comes to, exactly as {@code SaleRecordRequest} refuses a client total (OMS-5).
     *
     * <p>The quote lands in {@code DRAFT}; {@link #send} decides whether it needs internal approval first.
     */
    @Transactional
    public SalesQuote create(SalesQuote incoming) {
        if (incoming == null || incoming.getLines() == null || incoming.getLines().isEmpty())
            throw new QuoteRefused("A quote needs at least one line.");

        SalesQuote q = new SalesQuote();
        q.setCustomerId(incoming.getCustomerId());
        q.setCustomerPoNumber(blankToNull(incoming.getCustomerPoNumber()));
        q.setNotes(incoming.getNotes());
        q.setTradeDiscount(nz(incoming.getTradeDiscount()));
        q.setOrganizationId(orgId());
        q.setUserId(userId());
        q.setStoreId(incoming.getStoreId());
        q.setStatus(QuoteStatus.DRAFT);
        q.setDated(LocalDateTime.now());
        q.setUpdated(LocalDateTime.now());

        // Name the customer on the document so it still reads correctly if they are renamed later.
        if (q.getCustomerId() != null) {
            Customer c = customerRepo.findById(q.getCustomerId()).orElse(null);
            if (c != null && inMyTenant(c)) q.setCustomerName(c.getName());
        }

        q.setValidUntil(LocalDate.now().plusDays(validityDays()));

        for (SalesQuoteLine line : incoming.getLines()) {
            if (line == null || line.getProductId() == null) continue;
            SalesQuoteLine l = new SalesQuoteLine();
            l.setQuote(q);
            l.setProductId(line.getProductId());
            l.setProductName(line.getProductName());
            l.setQuantity(line.getQuantity() == null ? 1F : line.getQuantity());
            l.setUnitPrice(nz(line.getUnitPrice()));
            l.setPriceReason(line.getPriceReason());
            l.setDiscount(nz(line.getDiscount()));
            l.setLineTotal(lineTotal(l));
            q.getLines().add(l);
        }
        if (q.getLines().isEmpty()) throw new QuoteRefused("A quote needs at least one line with a product.");

        recomputeTotals(q);

        // Allocate the number at creation so the document can be referenced immediately. MAX+1 inside this
        // transaction, made safe by UNIQUE(organization_id, quote_seq).
        long seq = quoteRepo.maxQuoteSeqForOrg(orgId()) + 1;
        q.setQuoteSeq(seq);
        q.setQuoteNo(InvoiceNumbers.quote(seq));

        SalesQuote saved = quoteRepo.save(q);
        LOG.info("P4b: raised quote {} for customer {} ({} line(s), total {})",
                saved.getQuoteNo(), saved.getCustomerId(), saved.getLines().size(), saved.getGrandTotal());
        return saved;
    }

    // â”€â”€ the single guarded write path â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Move a quote to {@code target}. The ONLY place status changes.
     *
     * @throws QuoteRefused when the move is not legal from the quote's CURRENT (effective) status
     */
    @Transactional
    public SalesQuote transition(Long quoteId, QuoteStatus target, String reason) {
        SalesQuote q = load(quoteId);
        QuoteStatus current = q.getEffectiveStatus();

        // Derived expiry is checked FIRST: an open quote past its validity is EXPIRED to every reader, so it
        // cannot be accepted or converted even though the stored status still says SENT.
        if (current == QuoteStatus.EXPIRED && q.getStatus() != QuoteStatus.EXPIRED) {
            q.setStatus(QuoteStatus.EXPIRED);            // settle the row to match what everyone already sees
            quoteRepo.save(q);
            throw new QuoteRefused("This quote expired on " + q.getValidUntil() + " and can no longer be used.");
        }
        if (current == target) return q;                 // idempotent â€” asking for where it already is

        Set<QuoteStatus> allowed = ALLOWED.getOrDefault(current, EnumSet.noneOf(QuoteStatus.class));
        if (!allowed.contains(target))
            throw new QuoteRefused("A " + current + " quote cannot become " + target + ".");

        // Sending is where the INTERNAL approval gate applies: over the org's discount threshold, a quote must
        // be approved by an owner/admin before it can leave the building.
        if (target == QuoteStatus.SENT && current == QuoteStatus.DRAFT && needsApproval(q))
            throw new QuoteRefused("This discount is over the approval threshold â€” it needs owner approval "
                    + "before the quote can be sent.");

        if (target == QuoteStatus.SENT && current == QuoteStatus.PENDING_APPROVAL) {
            q.setApprovedBy(userId());
            q.setApprovedAt(LocalDateTime.now());
        }

        q.setStatus(target);
        q.setUpdated(LocalDateTime.now());
        if (reason != null && !reason.isBlank())
            q.setNotes((q.getNotes() == null ? "" : q.getNotes() + " | ") + reason);
        LOG.info("P4b: quote {} {} -> {}", q.getQuoteNo(), current, target);
        return quoteRepo.save(q);
    }

    /** Raise the quote for internal approval â€” the explicit route when a discount is over threshold. */
    @Transactional
    public SalesQuote submitForApproval(Long quoteId) {
        return transition(quoteId, QuoteStatus.PENDING_APPROVAL, null);
    }

    // â”€â”€ convert â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Turn an ACCEPTED quote into an invoice.
     *
     * <h3>The same single revenue path</h3>
     * This calls {@code SagaSellService.addSell} â€” the very method the till uses and the one OMS O1 routed the
     * storefront through. So a quote inherits idempotency, FEFO reservation, tax, COGS, the period lock, the
     * audit trail and the GL outbox for free, and a quoted sale is the same kind of record in the books as a
     * counter sale. Writing an invoice here instead would be the third revenue path, which is exactly the defect
     * O1 exists to remove.
     *
     * <h3>Credit is checked against the GROUP (4a)</h3>
     * {@code addSell} runs {@code assertCreditPolicy}, which since 4a measures the customer's whole shared pool
     * against the account head's limit. So converting a quote for a branch is capped by its company's limit â€”
     * without that, a group could be talked past its ceiling one quote at a time.
     *
     * <h3>Idempotent by construction</h3>
     * The idempotency key is the quote number, so a double-click converts ONCE. Belt and braces: {@code @Version}
     * on the quote stops two concurrent converts, and the CONVERTED terminal state stops a later one.
     *
     * @throws QuoteRefused when the quote is not ACCEPTED, has expired, or was already converted
     */
    @Transactional
    public SalesQuote convert(Long quoteId) {
        SalesQuote q = load(quoteId);
        QuoteStatus current = q.getEffectiveStatus();

        if (current == QuoteStatus.CONVERTED)
            throw new QuoteRefused("This quote was already converted (invoice " + q.getConvertedInvoiceNo() + ").");
        if (current == QuoteStatus.EXPIRED)
            throw new QuoteRefused("This quote expired on " + q.getValidUntil() + " and can no longer be converted.");
        if (current != QuoteStatus.ACCEPTED)
            throw new QuoteRefused("Only an accepted quote can be converted â€” this one is " + current + ".");

        CustomerHistoryDTO dto = toSaleRequest(q);
        String invoiceNo = sagaSellService.addSell(dto);   // reserve + invoice + tax + COGS + GL + audit

        q.setStatus(QuoteStatus.CONVERTED);
        q.setConvertedInvoiceNo(invoiceNo);
        q.setUpdated(LocalDateTime.now());
        SalesQuote saved = quoteRepo.save(q);
        LOG.info("P4b: quote {} converted to invoice {}", saved.getQuoteNo(), invoiceNo);
        return saved;
    }

    /**
     * Quote â†’ the DTO the till builds. Only identity, lines and the document-level concession cross over:
     * everything derived (line tax, COGS, due, invoice number, GL) is computed downstream, so there is one
     * implementation of each.
     */
    CustomerHistoryDTO toSaleRequest(SalesQuote q) {
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        // The quote number IS the idempotency key: one quote can only ever produce one invoice.
        dto.setIdempotencyKey("QTE-" + q.getId());

        if (q.getCustomerId() != null) {
            com.myplus.business_service.dto.CustomerDTO c = new com.myplus.business_service.dto.CustomerDTO();
            c.setCustomerId(q.getCustomerId());
            c.setName(q.getCustomerName());
            dto.setCustomer(c);
        }

        java.util.List<com.myplus.business_service.dto.SellDTO> sales = new java.util.ArrayList<>();
        for (SalesQuoteLine l : q.getLines()) {
            com.myplus.business_service.dto.SellDTO s = new com.myplus.business_service.dto.SellDTO();
            s.setProductId(l.getProductId());
            s.setQuantity(l.getQuantity());
            // The SNAPSHOTTED price, not a fresh calculation: the customer accepted these numbers.
            s.setSellRate(l.getUnitPrice());
            s.setDiscount(l.getDiscount());
            s.setPriceReason(l.getPriceReason());
            sales.add(s);
        }
        dto.setSales(sales);

        // D-4: the whole-document concession travels to the invoice, where it posts to a CONTRA-REVENUE account
        // rather than being netted off sales â€” so gross revenue still matches the invoice face value.
        dto.setTradeDiscount(nz(q.getTradeDiscount()));

        // The buyer's PO must reach the printed invoice, or their AP clerk cannot match it to their order.
        dto.setCustomerPoNumber(q.getCustomerPoNumber());

        // No tenders: a quoted trade sale is on account by definition, so it lands in AR exactly like an unpaid
        // counter sale. A payment is taken later through Receive Payment.
        dto.setPaidAmount(BigDecimal.ZERO);
        return dto;
    }

    // â”€â”€ reads â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Transactional(readOnly = true)
    public List<SalesQuote> list() {
        return quoteRepo.findScoped(orgId(), userId());
    }

    @Transactional(readOnly = true)
    public SalesQuote get(Long id) {
        return load(id);
    }

    // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Does this quote's discount need an owner's sign-off? Threshold is a PERCENT of the pre-discount value;
     * unset (or â‰¤ 0) means no internal gate at all, which is the default so nothing changes for a shop that
     * does not want one.
     */
    boolean needsApproval(SalesQuote q) {
        BigDecimal threshold = discountThreshold();
        if (threshold == null || threshold.signum() <= 0) return false;
        BigDecimal gross = nz(q.getSubTotal()).add(nz(q.getTradeDiscount()));
        if (gross.signum() <= 0) return false;
        BigDecimal pct = nz(q.getTradeDiscount()).multiply(BigDecimal.valueOf(100))
                .divide(gross, 2, RoundingMode.HALF_UP);
        return pct.compareTo(threshold) > 0;
    }

    /** getInt already swallows a malformed override and returns the fallback â€” a settings typo must not stop a
     *  shop quoting. Guard only the >0 case, since a 0-day validity would expire every quote instantly. */
    private int validityDays() {
        if (settingsService == null) return DEFAULT_VALIDITY_DAYS;
        int days = settingsService.getInt(SETTING_VALIDITY_DAYS, DEFAULT_VALIDITY_DAYS);
        return days > 0 ? days : DEFAULT_VALIDITY_DAYS;
    }

    /** Null = no internal gate, which is the default: unset means a shop that does not want approvals. */
    private BigDecimal discountThreshold() {
        if (settingsService == null) return null;
        // The shared reader, not a local parse. This method WAS the local parse — OMS O3 needed the same for
        // shipping fees, and a second copy of "read a decimal setting" is what §5c says to move into the
        // library rather than duplicate. Same fail-soft rule: a malformed value returns the fallback.
        return settingsService.getDecimal(SETTING_DISCOUNT_THRESHOLD, null);
    }

    void recomputeTotals(SalesQuote q) {
        BigDecimal sub = BigDecimal.ZERO;
        for (SalesQuoteLine l : q.getLines()) sub = sub.add(nz(l.getLineTotal()));
        sub = sub.subtract(nz(q.getTradeDiscount()));
        if (sub.signum() < 0) sub = BigDecimal.ZERO;
        q.setSubTotal(sub.setScale(2, RoundingMode.HALF_UP));
        // Tax is applied by the sale path at conversion (one tax engine, not two). The quote shows goods value;
        // duplicating the tax rules here is how two systems end up disagreeing about the same invoice.
        q.setTaxTotal(nz(q.getTaxTotal()));
        q.setGrandTotal(nz(q.getSubTotal()).add(nz(q.getTaxTotal())));
    }

    private static BigDecimal lineTotal(SalesQuoteLine l) {
        BigDecimal qty = BigDecimal.valueOf(l.getQuantity() == null ? 0f : l.getQuantity());
        return nz(l.getUnitPrice()).multiply(qty).subtract(nz(l.getDiscount()))
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    SalesQuote load(Long id) {
        SalesQuote q = (id == null) ? null : quoteRepo.findByIdScoped(id, orgId(), userId()).orElse(null);
        if (q == null) throw new QuoteRefused("Quote not found: " + id);   // anti-IDOR: foreign == missing
        return q;
    }

    private boolean inMyTenant(Customer c) {
        Long org = orgId();
        return (c.getOrganizationId() != null && c.getOrganizationId().equals(org))
                || (c.getOrganizationId() == null && c.getUserId() != null && c.getUserId().equals(userId()));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
}
