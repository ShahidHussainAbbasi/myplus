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

    @PostMapping("/internal/expense/drawer-vouchers")
    public ExpenseVoucherRef drawerVoucher(@RequestBody DrawerExpenseRequest request) {
        return vouchers.recordFromDrawer(request);
    }
}
