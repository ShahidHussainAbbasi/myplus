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
}
