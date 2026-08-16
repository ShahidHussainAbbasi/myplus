package com.myplus.business_service.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.service.ICustomerService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.PaymentReceiptRequest;
import com.myplus.commerce.contracts.dto.PaymentReceiptResult;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * OMS O7 D5 — the ONE way another service hands over money it collected on business-service's behalf.
 *
 * <h3>Why this contains no money logic</h3>
 * {@code CustomerService.receivePayment} already does the whole job: FIFO-allocate across the customer's open
 * invoices through the single allocator AR and AP share, recompute the running balance, record the entry in the
 * shared finance ledger, post {@code Dr cash / Cr AR}, honour the period lock, audit, and dedupe on an
 * idempotency key. This endpoint maps a wire request onto that call and reports what it did. Any arithmetic
 * added here would be a second settlement path, which is precisely the defect the trade contract exists to
 * remove.
 *
 * <h3>Why it is NOT part of {@link InternalSalesController}</h3>
 * A receipt is not a sale. That class also carries the §12.5 controller-into-controller debt, taken on because
 * {@code saleReturn} lives in a controller and could not be reached otherwise. Nothing here needs that —
 * {@code receivePayment} is a service — so folding this in would grow the debt-bearing class to save a file.
 *
 * <h3>Trust boundary and anti-IDOR</h3>
 * Reachable only inside the private network (the gateway does not route {@code /internal/**}) and the caller
 * arrives with a forwarded identity. The {@code customerId} in the body comes <b>off the wire</b>, so it is
 * resolved with the SCOPED read against the authenticated org: another tenant's customer is refused as
 * <b>absent</b>, worded identically to a genuinely missing one, so a compromised in-network caller can neither
 * clear another tenant's receivable nor probe which customer ids exist. {@code receivePayment} itself uses an
 * unscoped {@code findById}, which is safe for its browser callers (they follow an id the session already
 * proved) and is exactly the gap {@code /creditStanding} fell into in D2.
 */
@RestController
@RequestMapping("/internal/receipts")
public class InternalReceiptsController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalReceiptsController.class);

    @Autowired
    private ICustomerService customerService;

    @Autowired
    private RequestUtil requestUtil;

    @PostMapping
    public ResponseEntity<?> receive(@RequestBody PaymentReceiptRequest request) {
        if (request == null) throw new ValidationException("A receipt request is required");
        if (request.getCustomerId() == null)
            throw new ValidationException("customerId is required — a receipt has to clear somebody's account");
        BigDecimal amount = request.getAmount();
        if (amount == null || amount.signum() <= 0)
            throw new ValidationException("A positive amount is required");
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())
            throw new ValidationException("idempotencyKey is required — it is what makes a retry safe");

        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");

        // Anti-IDOR: resolve within the CALLER's tenant. "Not yours" answers exactly as "not there".
        Customer customer = customerService
                .findByIdScoped(request.getCustomerId(), org, user.getUserId())
                .orElse(null);
        if (customer == null)
            return ResponseEntity.status(404)
                    .body(Map.of("message", "Customer not found: " + request.getCustomerId()));

        LocalDate paidOn = request.getPaidOn() != null ? request.getPaidOn() : LocalDate.now();
        String method = (request.getMethod() == null || request.getMethod().isBlank())
                ? "CASH" : request.getMethod();

        // Refusals from here down are the CALLER's answer, not a server fault — a closed accounting period is
        // the one an admin most needs to read verbatim, because the fix is to change the date, not to retry.
        Map<String, Object> res;
        try {
            res = customerService.receivePayment(request.getCustomerId(), amount, method, paidOn,
                    request.getReference(), request.getIdempotencyKey());
        } catch (RuntimeException ex) {
            LOG.warn("O7 D5: receipt refused for customer {} org {} ({})",
                    request.getCustomerId(), org, ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("message", ex.getMessage() == null ? "The receipt could not be recorded."
                            : ex.getMessage()));
        }

        PaymentReceiptResult out = PaymentReceiptResult.builder()
                .receiptNo(str(res.get("receiptNo")))
                .allocated(dec(res.get("allocated")))
                .onAccount(dec(res.get("onAccountCredit")))
                .newDue(dec(res.get("newDue")))
                .replay(Boolean.TRUE.equals(res.get("replay")))
                .build();

        LOG.info("O7 D5: receipt {} for customer {} org {} — {} allocated, new due {}{}",
                out.getReceiptNo(), request.getCustomerId(), org, out.getAllocated(), out.getNewDue(),
                out.isReplay() ? " (replay)" : "");
        return ResponseEntity.ok(out);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static BigDecimal dec(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        try { return new BigDecimal(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
