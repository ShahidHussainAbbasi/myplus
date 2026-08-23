package com.myplus.business_service.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.dto.TenderDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.service.ICustomerHistoryService;
import com.myplus.business_service.service.SagaSellService;
import com.myplus.business_service.service.SaleVoidService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * OMS O1 — the ONE way a non-POS channel puts a completed order into the books.
 *
 * <h3>Why this is a thin controller and nothing else</h3>
 * business-service is the sole author of trade sales. {@link SagaSellService#addSell} already does the whole job
 * — reserve (FEFO), write the PENDING invoice, confirm, apply tax, snapshot COGS, record tenders, emit the GL
 * event through the outbox, audit, and honour the period lock — and it is idempotent on the request's
 * idempotency key. So this endpoint contains <b>no money logic at all</b>: it maps the wire request onto the
 * DTO that path already takes, and returns the invoice number. Any arithmetic added here would be a second
 * revenue path, which is precisely the defect O1 exists to remove.
 *
 * <h3>Totals are not accepted</h3>
 * {@link SaleRecordRequest} carries no total. The channel says what was bought; the server decides what it costs.
 * Before O1 the storefront sent its own {@code total} and it was trusted (OMS-5) — the fix is that the figure has
 * nowhere to travel.
 *
 * <h3>Trust boundary</h3>
 * Reachable only inside the private network (the gateway does not route {@code /internal/**}), and the caller
 * must arrive with a forwarded identity — marketplace wraps the call in {@code runAs(STOREFRONT_USER, org)},
 * which stamps X-Org-Id/X-User-Id/X-Internal-Secret. The org in the BODY is then checked against the
 * AUTHENTICATED org: a caller may only record revenue into the tenant it is authenticated as. Without that check
 * a compromised in-network caller could book another tenant's revenue.
 */
@RestController
@RequestMapping("/internal/sales")
public class InternalSalesController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalSalesController.class);

    @Autowired
    private SagaSellService sagaSellService;

    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private SaleVoidService saleVoidService;

    @Autowired
    private ICustomerHistoryService customerHistoryService;

    @Autowired
    private com.myplus.business_service.service.ISellService sellService;

    /**
     * O7 D4 — the counter's OWN return path, called as-is.
     *
     * <p>Injecting a controller into a controller is unusual and is the lesser of two evils, chosen
     * deliberately. {@code SellController.saleReturn} is ~190 lines of money logic — credit-note numbering,
     * pro-rata line adjustment, stock restore, header re-settlement, store credit, GL reversal, AR recompute,
     * audit — and there are exactly two honest ways to reach it from here: call it, or extract it into a
     * service first. <b>Duplicating it is not one of them</b>, and transcribing money arithmetic by hand into a
     * second copy is precisely how the books drift.
     *
     * <p>The extraction is the right end state and is recorded as debt (§12.5 of the O7 slice). It is not done
     * inside this slice because moving 190 lines of ledger code is a change that deserves its own gate rather
     * than riding along with a delivery feature.
     */
    @Autowired
    private SellController sellController;

    @PostMapping
    public ResponseEntity<?> recordSale(@RequestBody SaleRecordRequest request) {
        if (request == null) throw new ValidationException("A sale request is required");
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())
            throw new ValidationException("idempotencyKey is required — it is what makes a retry safe");
        if (request.getLines() == null || request.getLines().isEmpty())
            throw new ValidationException("A sale needs at least one line");

        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long authenticatedOrg = (user == null) ? null : user.getOrganizationId();
        if (authenticatedOrg == null)
            throw new ValidationException("No tenant identity on the request");
        // Anti-IDOR: the body may not name a different tenant than the one it authenticated as.
        if (request.getOrganizationId() != null && !request.getOrganizationId().equals(authenticatedOrg))
            throw new ValidationException("Organization mismatch: a sale may only be recorded for its own tenant");

        String invoiceNo = sagaSellService.addSell(toCustomerHistory(request));

        // The SERVER's total, read back from the invoice just written. Returned so the channel charges and
        // displays what was actually billed rather than what it computed locally — the request carries no total
        // at all, which is how OMS-5 (client-supplied total) stops being representable.
        java.math.BigDecimal grandTotal = customerHistoryService.findByOrgAndInvoiceNo(authenticatedOrg, invoiceNo)
                .map(CustomerHistory::getGrandTotal).orElse(null);

        LOG.info("O1: recorded {} sale for org {} as invoice {} total {} (key {})",
                request.getChannel(), authenticatedOrg, invoiceNo, grandTotal, request.getIdempotencyKey());

        return ResponseEntity.ok(SaleRecordResult.builder()
                .invoiceNo(invoiceNo)
                .grandTotal(grandTotal)
                // RECORDED vs REPLAYED is not distinguishable from addSell's return today (a replay returns the
                // same invoice number, which is the guarantee that matters). Reported as RECORDED rather than
                // guessed — the caller's own idempotency handling does not depend on telling the two apart.
                .status("RECORDED")
                .build());
    }

    /**
     * OMS O7 D1b — what {@link #recordSale} WOULD say about this basket. <b>Writes nothing.</b>
     *
     * <h3>The gap</h3>
     * Margin and credit are enforced at DISPATCH, by the sale path, exactly as for every other sale. So a
     * reviewer amending an order learned that their amendment loses money, or puts the outlet over its limit,
     * when the van was already loading. This answers the same question while they are still deciding.
     *
     * <h3>No new rule, and no new privilege</h3>
     * Delegates to {@code SagaSellService.checkPolicy}, which runs the very methods {@code addSell} runs. This
     * controller adds nothing but the tenancy guard — the same rule as {@link #recordSale}, because without it
     * a caller could read another tenant's pricing and credit position by asking about their customer.
     *
     * <h3>Idempotency is not required here</h3>
     * Unlike {@link #recordSale}, which demands a key because a retry must not bill twice, a check writes
     * nothing and is therefore safe to repeat by construction. Demanding a key would be ceremony that implies
     * a danger that does not exist.
     */
    @PostMapping("/check-policy")
    public ResponseEntity<?> checkPolicy(@RequestBody SaleRecordRequest request) {
        if (request == null) throw new ValidationException("A sale request is required");
        if (request.getLines() == null || request.getLines().isEmpty())
            throw new ValidationException("A policy check needs at least one line");

        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long authenticatedOrg = (user == null) ? null : user.getOrganizationId();
        if (authenticatedOrg == null)
            throw new ValidationException("No tenant identity on the request");
        if (request.getOrganizationId() != null && !request.getOrganizationId().equals(authenticatedOrg))
            throw new ValidationException("Organization mismatch: a policy check may only be run for its own tenant");

        return ResponseEntity.ok(sagaSellService.checkPolicy(toCustomerHistory(request)));
    }

    /**
     * Reverse the sale behind a cancelled order — the mirror of {@link #recordSale}.
     *
     * <p>Delegates to the SAME {@code SaleVoidService} the human-facing {@code /voidSell} uses, so a cancelled
     * storefront order and a counter void reverse the books identically. The privilege gate differs by design:
     * {@code /voidSell} requires {@code VOID_INVOICE} because a person is asking; this path is trusted by the
     * internal secret and re-checks the tenant, because the caller is a service acting for its own org.
     *
     * <p>Already-void is refused rather than double-reversed, so a retried cancellation cannot reverse twice.
     */
    @PostMapping("/reverse")
    public ResponseEntity<?> reverseSale(@RequestParam String invoiceNo,
                                         @RequestParam(required = false) String reason) {
        if (invoiceNo == null || invoiceNo.isBlank())
            throw new ValidationException("invoiceNo is required");

        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");

        // Resolve within the caller's tenant — anti-IDOR: an invoice from another org reads as "not found".
        CustomerHistory ch = customerHistoryService.findByOrgAndInvoiceNo(org, invoiceNo).orElse(null);
        if (ch == null)
            return ResponseEntity.status(404).body(java.util.Map.of("message", "Invoice not found: " + invoiceNo));

        try {
            saleVoidService.voidInvoice(ch, reason == null ? "Order cancelled" : reason, false,
                    org, user.getUserId());
        } catch (SaleVoidService.VoidRefused refused) {
            // Already void / a return exists: the operator's answer, not a server fault.
            return ResponseEntity.badRequest().body(java.util.Map.of("message", refused.getMessage()));
        }
        LOG.info("O1: reversed invoice {} for org {} ({})", invoiceNo, org, reason);
        return ResponseEntity.ok(java.util.Map.of("invoiceNo", invoiceNo, "status", "VOID"));
    }

    /**
     * Wire request → the DTO the POS sale path already takes.
     *
     * <p>Only identity, lines and tenders cross over. Everything derived — line totals, tax, COGS, due amount,
     * invoice number, GL — is computed downstream exactly as it is for a till sale, so an online sale and a
     * counter sale are the same kind of record in the books.
     */
    private CustomerHistoryDTO toCustomerHistory(SaleRecordRequest r) {
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setIdempotencyKey(r.getIdempotencyKey());

        SaleRecordRequest.Customer c = r.getCustomer();
        if (c != null) {
            CustomerDTO cust = new CustomerDTO();
            cust.setCustomerId(c.getCustomerId());
            cust.setName(c.getName());
            cust.setContact(c.getContact());
            cust.setAddress(c.getAddress());
            cust.setPartyId(c.getPartyId());
            dto.setCustomer(cust);
        }

        List<SellDTO> sales = new ArrayList<>();
        for (SaleRecordRequest.Line line : r.getLines()) {
            if (line == null || line.getProductId() == null) continue;
            SellDTO s = new SellDTO();
            s.setProductId(line.getProductId());
            s.setQuantity(line.getQuantity() == null ? 1F : line.getQuantity());
            // The channel's quoted unit price. addSell still prices from catalog when this is absent, and applies
            // tax/discount rules on top either way.
            s.setSellRate(line.getUnitPrice());
            // The per-line concession.
            //
            // It goes on the nested StockDTO, NOT on SellDTO.discount, because that is where the sale path
            // reads it: resolveDiscount() looks at stock.bsellDiscount / bsellDiscountType and ignores the
            // top-level field entirely. Setting the obvious-looking one would have shipped a discount that
            // travelled the whole way and then did nothing.
            //
            // Type "0" = an absolute amount. resolveDiscount treats "%" and "1" as percentages and everything
            // else as an amount, so sending 350.00 while the type still said percent would read as 350%.
            //
            // No new money logic here: buildLines already resolves the discount and taxes the DISCOUNTED base.
            if (line.getDiscount() != null && line.getDiscount().signum() > 0) {
                com.myplus.business_service.dto.StockDTO st = s.getStock() != null
                        ? s.getStock() : new com.myplus.business_service.dto.StockDTO();
                st.setBsellDiscount(line.getDiscount());
                st.setBsellDiscountType("0");
                s.setStock(st);
            }
            s.setDescription(line.getDescription());
            sales.add(s);
        }
        if (sales.isEmpty()) throw new ValidationException("A sale needs at least one line with a productId");
        dto.setSales(sales);

        List<TenderDTO> tenders = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        if (r.getTenders() != null) {
            for (SaleRecordRequest.Tender t : r.getTenders()) {
                if (t == null || t.getAmount() == null) continue;
                TenderDTO td = new TenderDTO();
                td.setMethod(t.getMethod());
                td.setAmount(t.getAmount());
                td.setReference(t.getReference());
                tenders.add(td);
                paid = paid.add(t.getAmount());
            }
        }
        dto.setTenders(tenders);
        // No tenders is not an error — it is a COD order, i.e. a receivable. addSell derives the due from
        // (grand total − paid), so an unpaid online order lands in AR exactly like an unpaid counter sale.
        dto.setPaidAmount(paid);

        // The two whole-document figures a channel knows and the server cannot derive from the lines.
        //
        // These do NOT reopen the "no totals on the wire" rule above. The server still prices every line and
        // still computes every total; it is simply told about a concession it has no way to recompute (the
        // promotion lives in the channel's coupon rules) and a delivery charge it has no way to know (the fee
        // comes from the channel's shipping policy). Before this, both were charged to the shopper, stored on
        // the order, and never reached the books: the coupon vanished and delivery income stayed off the P&L.
        dto.setTradeDiscount(r.getDiscountTotal());
        dto.setShippingFee(r.getShippingFee());
        return dto;
    }

    /**
     * OMS O7 D4 — return PART of an invoice: the goods a shop refused at the door.
     *
     * <p>Resolves each {@code productId} to the {@code Sell} line it was sold on, then runs the SAME
     * {@code saleReturn} the counter runs — one credit note per line, stock back, GL, AR, audit. No money logic
     * is added here; this endpoint is a translator between the contract's language (products) and this
     * service's own (line ids), which is exactly why the operation lives behind a contract instead of
     * marketplace attempting accounting.
     *
     * <p><b>Anti-IDOR:</b> the invoice is resolved within the CALLER's tenant, so an invoice from another org
     * reads as missing — the same rule {@code reverseSale} above follows.
     *
     * <p><b>Not idempotent, and honestly so.</b> Returning the same goods twice would raise two credit notes,
     * because a shop genuinely can refuse the same product on two different deliveries. The caller
     * (marketplace) is what knows a delivery outcome has already been keyed, and that is where the guard
     * belongs — stating it here rather than pretending a safety this endpoint cannot provide.
     */
    // All-or-nothing: a partially-applied door rejection (line 1 credited, line 2 refused) would leave the
    // books describing a delivery that never happened in that shape. The rollback is what makes the throw
    // below safe.
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/return-lines")
    public ResponseEntity<?> returnLines(
            @RequestBody com.myplus.commerce.contracts.dto.SaleReturnRequest body,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (body == null || body.getInvoiceNo() == null || body.getInvoiceNo().isBlank())
            throw new ValidationException("invoiceNo is required");
        java.util.List<com.myplus.commerce.contracts.dto.SaleReturnLine> lines = body.getLines();
        if (lines == null || lines.isEmpty())
            throw new ValidationException("At least one line is required");
        final String invoiceNo = body.getInvoiceNo();
        final String reason = body.getReason();

        // `saleReturn` reads `reason` (and `quarantine`, and `refundAs`) off the RAW REQUEST via getParameter.
        // The reason now arrives in the JSON body, so it would be invisible to it — and the credit note would
        // silently carry no reason at all, which is the field a shop's refusal is explained by months later.
        //
        // Wrapping is deliberate rather than incidental: it makes that hidden coupling explicit at the one
        // place it matters, instead of leaving a caller to discover the reason vanished. It disappears when
        // saleReturn is extracted into a service that takes its inputs as arguments (§12.5 debt).
        jakarta.servlet.http.HttpServletRequest request =
                new jakarta.servlet.http.HttpServletRequestWrapper(httpRequest) {
                    @Override public String getParameter(String name) {
                        if ("reason".equals(name)) return reason;
                        return super.getParameter(name);
                    }
                };

        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");

        CustomerHistory ch = customerHistoryService.findByOrgAndInvoiceNo(org, invoiceNo).orElse(null);
        if (ch == null)
            return ResponseEntity.status(404).body(java.util.Map.of("message", "Invoice not found: " + invoiceNo));

        java.util.List<com.myplus.business_service.entity.Sell> sold =
                sellService.findByInvoiceScoped(ch.getCustomer_history_id(), org, user.getUserId());
        java.util.List<String> creditNotes = new java.util.ArrayList<>();

        for (com.myplus.commerce.contracts.dto.SaleReturnLine line : lines) {
            if (line == null || line.getProductId() == null || line.getQuantity() == null
                    || line.getQuantity() <= 0f) continue;

            com.myplus.business_service.entity.Sell match = sold.stream()
                    .filter(x -> line.getProductId().equals(x.getProductId()))
                    .filter(x -> x.getQuantity() != null && x.getQuantity() > 0f)
                    .findFirst().orElse(null);
            if (match == null) {
                // Refuse rather than skip: a product that is not on the invoice means the caller and the books
                // disagree about what was sold, and silently returning nothing would hide that.
                throw new ValidationException("Product " + line.getProductId()
                        + " is not on invoice " + invoiceNo + " — nothing was returned.");
            }
            if (line.getQuantity() > match.getQuantity()) {
                throw new ValidationException("Cannot return " + line.getQuantity() + " of product "
                        + line.getProductId() + " — only " + match.getQuantity() + " was sold on " + invoiceNo + ".");
            }

            SellDTO dto = new SellDTO();
            dto.setSellId(match.getSellId());
            dto.setQuantity(line.getQuantity());
            // `reason` reaches saleReturn through the REQUEST (it reads getParameter("reason")), which is why
            // the contract sends it as a query parameter rather than in the body.
            GenericResponse res = sellController.saleReturn(dto, request);
            if (res == null || !"SUCCESS".equals(res.getStatus())) {
                // Propagate: a partially-applied door rejection is worse than a refused one, and the
                // @Transactional boundary on this method rolls the earlier lines back with it.
                throw new ValidationException(res == null ? "The return could not be recorded."
                        : String.valueOf(res.getMessage()));
            }
            if (res.getObject() != null) creditNotes.add(String.valueOf(res.getObject()));
        }

        LOG.info("O7 D4: returned {} line(s) off invoice {} for org {} ({}) — credit notes {}",
                creditNotes.size(), invoiceNo, org, reason, creditNotes);
        return ResponseEntity.ok(creditNotes);
    }
}
