package com.myplus.commerce.contracts.client;

import java.util.List;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.myplus.commerce.contracts.dto.FarmExpenseView;

/** EX-9b — agriculture-service's farm expense rows not yet in the books, for the caller's farm. Internal route. */
@HttpExchange(accept = "application/json")
public interface FarmHistoryClient {

    @GetExchange("/internal/agriculture/expenses/not-in-books")
    List<FarmExpenseView> notInBooks();

    /** Stamp an imported row with its expense number (only this farm's row, only once). */
    @PostExchange("/internal/agriculture/expenses/{id}/expense-voucher")
    void stampExpenseVoucher(@org.springframework.web.bind.annotation.PathVariable("id") Long id,
                             @org.springframework.web.bind.annotation.RequestParam("voucherNo") String voucherNo);
}
