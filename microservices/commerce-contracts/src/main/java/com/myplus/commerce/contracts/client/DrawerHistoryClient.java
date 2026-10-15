package com.myplus.commerce.contracts.client;

import java.util.List;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.myplus.commerce.contracts.dto.DrawerPayoutView;

/**
 * EX-9a — business-service's till pay-outs that never reached the books, for the caller's business (the forwarded
 * identity scopes it). Internal: no gateway route reaches {@code /internal/**}.
 */
@HttpExchange(accept = "application/json")
public interface DrawerHistoryClient {

    @GetExchange("/internal/business/drawer/unbooked-payouts")
    List<DrawerPayoutView> unbookedPayouts();

    /**
     * Stamp the expense number on an imported pay-out, so it leaves business's "without expense" list (as an EX-3
     * delivery does). Only a PAY_OUT of the caller's business; anything else is left untouched.
     */
    @PostExchange("/internal/business/drawer/payouts/{movementId}/expense-voucher")
    void stampExpenseVoucher(@org.springframework.web.bind.annotation.PathVariable("movementId") Long movementId,
                             @org.springframework.web.bind.annotation.RequestParam("voucherNo") String voucherNo);
}
