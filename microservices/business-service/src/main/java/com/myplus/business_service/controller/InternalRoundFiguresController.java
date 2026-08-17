package com.myplus.business_service.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.service.CreditStandingService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.RoundFigureView;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * OMS O8 — publishes the money for a delivery round's recovery sheet.
 *
 * <h3>What this exists to prevent</h3>
 * The route sheet a salesman carries is a COLLECTION document: he asks each shop for the amount printed on it.
 * Every figure on it is therefore a receivable, and the only system entitled to state a receivable is the one
 * holding it. marketplace legitimately knows which orders went out on the round; if it also totalled the
 * balances, the shop would be handed a second opinion about its own debt and the salesman would be collecting
 * against a number the ledger does not recognise. So the channel says which invoices are on the round, and
 * this says what they are worth.
 *
 * <h3>Batch, deliberately</h3>
 * A round is 20–30 stops and this is read at the end of every day. One request per stop would be thirty round
 * trips to print one sheet. The whole reason this endpoint takes a list is to keep it to one.
 *
 * <h3>Why it does not reuse /creditStanding</h3>
 * {@link CreditStandingService#standingFor} answers {@code null} for a customer with no credit limit, on
 * purpose — an uncapped shop is not "at 0% of 0", and flagging them as breached trains bookers to ignore the
 * warning. A collection sheet needs the opposite: what every shop owes, limit or not. So this reads the
 * outstanding balance directly, via the same {@code groupExposure} the credit check uses, which is what makes a
 * branch report its GROUP's balance rather than its own.
 *
 * <h3>Trust boundary and tenancy</h3>
 * Reachable only inside the private network ({@code /internal/**} is not routed by the gateway), and the tenant
 * comes from the caller's forwarded identity, never from a parameter. Invoice numbers are per-org, so a foreign
 * number resolves to nothing and is simply absent from the answer — identically to an invoice that does not
 * exist, so the endpoint cannot be used to discover which numbers are real.
 */
@RestController
@RequestMapping("/internal/round-figures")
public class InternalRoundFiguresController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalRoundFiguresController.class);

    /**
     * A round is a day's work, not a report over history. The cap is a guard against an accidental unbounded
     * read, not a business rule — a genuine round is well inside it.
     */
    private static final int MAX_INVOICES = 500;

    @Autowired
    private CustomerHistoryRepo customerHistoryRepo;

    @Autowired
    private CreditStandingService creditStandingService;

    @Autowired
    private RequestUtil requestUtil;

    @GetMapping
    public ResponseEntity<List<RoundFigureView>> roundFigures(@RequestParam("invoiceNos") List<String> invoiceNos) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");
        if (invoiceNos == null || invoiceNos.isEmpty()) return ResponseEntity.ok(List.of());
        if (invoiceNos.size() > MAX_INVOICES)
            throw new ValidationException("A round may not exceed " + MAX_INVOICES + " invoices");

        List<RoundFigureView> out = new ArrayList<>();
        for (String invoiceNo : invoiceNos) {
            if (invoiceNo == null || invoiceNo.isBlank()) continue;
            // SCOPED by org: this is the anti-IDOR read. A number from another tenant finds nothing and is
            // omitted, which is exactly what an unknown number does.
            CustomerHistory ch = customerHistoryRepo
                    .findByOrganizationIdAndInvoiceNo(org, invoiceNo.trim()).orElse(null);
            if (ch == null) continue;

            Customer customer = ch.getCustomer();

            // The invoice's own unpaid amount. `dueAmount` on the header is (paid − grandTotal), i.e. NEGATIVE
            // while owing, so it is negated here and floored: an overpaid invoice owes nothing, it does not owe
            // a negative amount, and a sheet showing "-250 due" would have a salesman handing money back.
            BigDecimal invoiceOutstanding = nz(ch.getDueAmount()).negate();
            if (invoiceOutstanding.signum() < 0) invoiceOutstanding = BigDecimal.ZERO;

            // What the ACCOUNT owes in total. groupExposure resolves a branch onto its trade group, so a branch
            // reports the balance that actually binds rather than its own — the same figure the credit check at
            // booking uses, which is what stops the two disagreeing.
            BigDecimal customerOutstanding = BigDecimal.ZERO;
            if (customer != null) {
                try {
                    BigDecimal owed = creditStandingService.groupExposure(
                            creditStandingService.creditAccountOf(customer), customer);
                    if (owed != null) customerOutstanding = owed;
                } catch (RuntimeException ex) {
                    // A balance we cannot resolve must not lose the whole sheet — the row still carries the
                    // invoice, and a zero here is visibly wrong to a cashier rather than silently plausible.
                    LOG.warn("Round sheet: could not resolve the balance for invoice {} (customer {})",
                            invoiceNo, customer.getCustomerId(), ex);
                }
            }

            out.add(RoundFigureView.builder()
                    .invoiceNo(ch.getInvoiceNo())
                    .customerId(customer != null ? customer.getCustomerId() : null)
                    .accountName(customer != null ? customer.getName() : null)
                    .invoiceTotal(nz(ch.getGrandTotal()))
                    .invoiceOutstanding(invoiceOutstanding)
                    .customerOutstanding(customerOutstanding)
                    .build());
        }

        LOG.debug("Round figures for org {}: asked {}, resolved {}", org, invoiceNos.size(), out.size());
        return ResponseEntity.ok(out);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
