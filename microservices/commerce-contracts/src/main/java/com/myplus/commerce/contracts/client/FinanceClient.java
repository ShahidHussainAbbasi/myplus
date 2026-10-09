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
    /** FP-3b — reverse one payment (mirror + opposite journal); idempotent per payment. */
    @PostExchange("/internal/finance/payments/{id}/reverse")
    PaymentRecordResult reversePayment(@org.springframework.web.bind.annotation.PathVariable("id") Long id,
                                       @RequestBody java.util.Map<String, String> body);

    @GetExchange("/api/finance/payments")
    List<PaymentView> listPayments(@RequestParam("partyType") String partyType, @RequestParam("partyId") Long partyId);

    /** F3b: post a SALE/PURCHASE event to the General Ledger (finance-service applies the posting rules). Best-effort. */
    @PostExchange("/api/finance/gl/post-event")
    void postEvent(@RequestBody com.myplus.commerce.contracts.dto.PostingEventRequest request);

    /**
     * EX-1 — the tenant's chart of accounts, seeding any default account it is missing first. Read by
     * expense-service when an owner maps a category, so a category pointing at a non-expense account (1200
     * Inventory) is refused when it is SAVED rather than on every posting. Admin/owner only in finance.
     */
    @PostExchange("/api/finance/gl/accounts/ensure-defaults")
    java.util.List<com.myplus.commerce.contracts.dto.GlAccountView> ensureDefaultAccounts();

    /**
     * FP-1/2 — report supplier payables (snapshots) into finance's subledger. Idempotent per (org, source, ref);
     * an older {@code sourceVersion} is ignored. {@code /internal/**}: no gateway route reaches it.
     */
    /** FP-4b — the subledger's per-supplier figures (netOwed, bySource, …) for the caller's tenant. */
    @GetExchange("/api/finance/payables/summary")
    java.util.Map<String, Object> payablesSummary();

    /** FP-4b — subledger open vs GL 2000 for the caller's tenant. */
    @GetExchange("/api/finance/payables/reconciliation")
    java.util.Map<String, Object> payablesReconciliation();

    /**
     * FP-6a — align GL 2000 to the supplier ledger for the caller's tenant: finance computes the difference itself and
     * posts ONE journal against 2990. Idempotent per ledger state (account 2000's newest line); runKey labels it. {@code /internal/**}: no gateway route reaches it.
     */
    @PostExchange("/internal/finance/payables/align-ledger")
    java.util.Map<String, Object> alignPayablesLedger(@RequestParam("runKey") String runKey);

    /** FP-4b — supplier aging {rows: [PartyAgingDTO-shaped], advances: [{partyId, partyName, advance}]}. */
    @GetExchange("/api/finance/payables/aging")
    java.util.Map<String, Object> payablesAging();

    /** FP-4b — a supplier's statement lines (date, docNo, type, debit, credit, balance); sources=PURCHASE = business's view. */
    @GetExchange("/api/finance/payables/statement")
    java.util.List<java.util.Map<String, Object>> payablesStatement(@RequestParam("partyType") String partyType,
            @RequestParam("partyId") Long partyId, @RequestParam(value = "sources", required = false) String sources);

    @PostExchange("/internal/finance/payables")
    void upsertPayables(@RequestBody java.util.List<com.myplus.commerce.contracts.dto.PayableSnapshot> snapshots);

    /** Period close: the org's lock date (or null when open) — read by business-service to gate its ops. */
    @GetExchange("/api/finance/gl/period-lock")
    com.myplus.commerce.contracts.dto.PeriodLockView getPeriodLock();
}
