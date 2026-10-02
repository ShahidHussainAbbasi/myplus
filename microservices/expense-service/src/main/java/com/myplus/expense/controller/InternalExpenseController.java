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

    @PostMapping("/internal/expense/drawer-vouchers")
    public ExpenseVoucherRef drawerVoucher(@RequestBody DrawerExpenseRequest request) {
        return vouchers.recordFromDrawer(request);
    }
}
