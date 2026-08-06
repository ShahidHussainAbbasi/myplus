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
        return dto;
    }
}
