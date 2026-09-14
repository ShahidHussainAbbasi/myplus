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
 * RestClient in the consuming service (see TradeClientsConfig).
 *
 * <p>⚠ <b>The base URL is the BARE SERVICE ({@code lb://finance-service}) and every path below is
 * ABSOLUTE.</b> It used to be {@code lb://finance-service/api/finance} with relative paths; that could
 * not survive BLK-0, which moved the payment WRITE to {@code /internal/**} while the reads stayed on
 * {@code /api/finance/**}. <b>Both consuming configs must agree</b> — business-service's
 * TradeClientsConfig and education-service's FinanceClientConfig.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface FinanceClient {

    /**
     * Record a payment (+ allocations) in the ledger. Returns the ledger id + receipt number.
     *
     * <p>⚠ <b>{@code /internal/**}, NOT {@code /api/finance/**} — and that is the whole point (BLK-0).</b>
     * The gateway routes {@code /api/finance/**}, so while this write lived there any holder of a valid JWT
     * could post straight into the ledger: bypassing AR/AP allocation, bypassing the idempotency that stops
     * the screens double-charging, and leaving no record of who did it. <b>No gateway route matches
     * {@code /internal/**}</b>, which is what closes it — the same mechanism InternalSalesController uses.
     *
     * <p>This is why every path here is ABSOLUTE and the base URL is the bare service: the write and the
     * reads now live under different prefixes and a single shared prefix can no longer express both.
     */
    @PostExchange("/internal/finance/payments")
    PaymentRecordResult recordPayment(@RequestBody PaymentRecordRequest request);

    /** A party's ledger payments (newest first) — for F2 statements of account. Tenant-scoped in finance-service. */
    @GetExchange("/api/finance/payments")
    List<PaymentView> listPayments(@RequestParam("partyType") String partyType, @RequestParam("partyId") Long partyId);

    /** F3b: post a SALE/PURCHASE event to the General Ledger (finance-service applies the posting rules). Best-effort. */
    @PostExchange("/api/finance/gl/post-event")
    void postEvent(@RequestBody com.myplus.commerce.contracts.dto.PostingEventRequest request);

    /** Period close: the org's lock date (or null when open) — read by business-service to gate its ops. */
    @GetExchange("/api/finance/gl/period-lock")
    com.myplus.commerce.contracts.dto.PeriodLockView getPeriodLock();
}
