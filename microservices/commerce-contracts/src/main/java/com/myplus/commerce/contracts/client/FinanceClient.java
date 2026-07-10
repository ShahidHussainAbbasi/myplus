package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.commerce.contracts.dto.PaymentView;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * Declarative client for the shared finance-service payment ledger. Any module records receipts/disbursements
 * here so the future General Ledger posts from one source. The implementing proxy is built from a load-balanced
 * RestClient (base URL {@code lb://finance-service/api/finance}) in the consuming service (see TradeClientsConfig).
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface FinanceClient {

    /** Record a payment (+ allocations) in the ledger. Returns the ledger id + receipt number. */
    @PostExchange("/payments")
    PaymentRecordResult recordPayment(@RequestBody PaymentRecordRequest request);

    /** A party's ledger payments (newest first) — for F2 statements of account. Tenant-scoped in finance-service. */
    @GetExchange("/payments")
    List<PaymentView> listPayments(@RequestParam("partyType") String partyType, @RequestParam("partyId") Long partyId);

    /** F3b: post a SALE/PURCHASE event to the General Ledger (finance-service applies the posting rules). Best-effort. */
    @PostExchange("/gl/post-event")
    void postEvent(@RequestBody com.myplus.commerce.contracts.dto.PostingEventRequest request);
}
