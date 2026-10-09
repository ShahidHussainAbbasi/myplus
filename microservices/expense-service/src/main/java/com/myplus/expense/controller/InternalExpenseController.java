package com.myplus.expense.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.commerce.contracts.dto.DrawerExpenseRequest;
import com.myplus.commerce.contracts.dto.ExpenseVoucherRef;
import com.myplus.expense.service.ExpenseVoucherService;

import lombok.RequiredArgsConstructor;

/**
 * EX-3 — service-to-service writes into expense-service.
 *
 * <p>Mapped under {@code /internal/expense}, which NO gateway route matches (the gateway routes {@code
 * /api/expense/**} only), so a browser cannot reach it; a caller needs the internal secret that
 * {@code GatewayIdentityForwarding} stamps. The BLK-0 pattern finance's payment write established.
 *
 * <p>No capability check here, deliberately: business-service checked it at the moment the cash moved, and a
 * background redelivery carries no capability claim — re-checking would dead-letter every retry.
 */
@RestController
@RequiredArgsConstructor
public class InternalExpenseController {

    private final ExpenseVoucherService vouchers;
    private final com.myplus.expense.service.ExpenseBillService bills;
    private final com.myplus.expense.repository.ExpenseVoucherRepo voucherRepo;
    private final com.myplus.expense.service.ExpenseOutboxService outbox;

    /** FP-5b — the supplier's bills Pay Supplier may settle (business-service, with the caller's identity). */
    @org.springframework.web.bind.annotation.GetMapping("/internal/expense/bills/open")
    public java.util.List<com.myplus.commerce.contracts.dto.OpenBillView> openBills(
            @org.springframework.web.bind.annotation.RequestParam("supplierId") Long supplierId) {
        return bills.openBills(supplierId);
    }

    /** FP-5b — apply part of a Pay Supplier payment to one bill (business-service's outbox; idempotent). */
    @PostMapping("/internal/expense/bills/{id}/apply")
    public java.util.Map<String, Object> apply(@org.springframework.web.bind.annotation.PathVariable("id") Long id,
            @RequestBody com.myplus.commerce.contracts.dto.BillApplyRequest request) {
        return bills.applyExternal(id, request);
    }

    /**
     * E11 — for the daily payables check (business-service, acting as a user of the tenant): what this tenant's bills
     * owe in the books. Compared there with finance's EXPENSE_BILL documents.
     */
    @org.springframework.web.bind.annotation.GetMapping("/internal/expense/payables/summary")
    public java.util.Map<String, Object> payablesSummary() {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        var t = voucherRepo.billsInBooks(org);
        return java.util.Map.of("open", t == null || t.getTotal() == null ? java.math.BigDecimal.ZERO : t.getTotal(),
                "count", t == null ? 0L : t.getCount());
    }

    /** E11 — the check's repair: every bill of the tenant re-sent to the subledger (finance's intake is idempotent). */
    @PostMapping("/internal/expense/payables/resend")
    public java.util.Map<String, Object> resendPayables() {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        return java.util.Map.of("queued", outbox.resendPayables(org));
    }

    @PostMapping("/internal/expense/drawer-vouchers")
    public ExpenseVoucherRef drawerVoucher(@RequestBody DrawerExpenseRequest request) {
        return vouchers.recordFromDrawer(request);
    }
}
