package com.myplus.commerce.contracts.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.myplus.commerce.contracts.dto.DrawerExpenseRequest;
import com.myplus.commerce.contracts.dto.ExpenseVoucherRef;

/**
 * EX-3 — what other services ask of expense-service.
 *
 * <p>{@code /internal/**}, NOT {@code /api/expense/**}: no gateway route matches {@code /internal}, so this is
 * reachable only service-to-service with the internal secret (the BLK-0 pattern finance's payment write uses).
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface ExpenseClient {

    /** Record a till pay-out as a posted expense. Idempotent on (organization, movementId). */
    @PostExchange("/internal/expense/drawer-vouchers")
    ExpenseVoucherRef recordDrawerPayOut(@RequestBody DrawerExpenseRequest request);

    /** FP-5b — the caller's supplier's expense bills still owed, with what may still be applied to each. */
    @org.springframework.web.service.annotation.GetExchange("/internal/expense/bills/open")
    java.util.List<com.myplus.commerce.contracts.dto.OpenBillView> openBills(
            @org.springframework.web.bind.annotation.RequestParam("supplierId") Long supplierId);

    /** FP-5b — apply part of a Pay Supplier payment to one bill; returns {applied}. Idempotent on (clientRef, bill). */
    @PostExchange("/internal/expense/bills/{id}/apply")
    java.util.Map<String, Object> applyToBill(@org.springframework.web.bind.annotation.PathVariable("id") Long billId,
            @RequestBody com.myplus.commerce.contracts.dto.BillApplyRequest request);

    /** E11 — what the caller's tenant's bills owe in the books: {"open": n, "count": n}. */
    @org.springframework.web.service.annotation.GetExchange("/internal/expense/payables/summary")
    java.util.Map<String, Object> payablesSummary();

    /** E11 — re-send every bill of the caller's tenant to finance's subledger: {"queued": n}. */
    @PostExchange("/internal/expense/payables/resend")
    java.util.Map<String, Object> resendPayables();
}
