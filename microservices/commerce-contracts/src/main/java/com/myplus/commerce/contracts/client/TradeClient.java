package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * OMS O1 — the seam every channel uses to put a completed order into the books.
 *
 * <p><b>One revenue path.</b> business-service is the sole author of trade sales and finance-service the sole
 * author of journals. A channel does not write invoices, tax lines, AR or GL events of its own; it hands over
 * what was bought and paid, and business-service's existing sale path does the rest — reserve (FEFO), write the
 * invoice, confirm, snapshot COGS, emit the GL event via the outbox, record the payment, audit, and honour the
 * period lock. Adding a second revenue path is how two systems end up disagreeing about the same money.
 *
 * <p><b>Idempotent by contract.</b> {@code recordSale} is idempotent on
 * {@link SaleRecordRequest#getIdempotencyKey()} — a retry, a double-submit, or a recovery relay re-drive returns
 * the SAME invoice rather than minting a second one. Callers may therefore retry freely.
 *
 * <p><b>Failure is meaningful.</b> An out-of-stock line makes the whole call fail with nothing reserved and
 * nothing invoiced, so a channel that has taken a card authorization must release it. That is the point of
 * calling this BEFORE capturing money.
 *
 * <p>Internal-secret gated: this endpoint records revenue on behalf of an org, so it is reachable only from
 * inside the private network, never from the edge.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface TradeClient {

    /** Record a sale for a completed order; returns the invoice number. Idempotent on {@code idempotencyKey}. */
    @PostExchange("/internal/sales")
    SaleRecordResult recordSale(@RequestBody SaleRecordRequest request);

    /**
     * Reverse the sale behind an order that is being cancelled — the mirror of {@link #recordSale}.
     *
     * <p><b>A pre-fulfilment cancellation is a VOID, not a credit note.</b> A credit note is for goods that were
     * delivered and came back; a cancelled order shipped nothing. The void restores stock, refunds what was
     * paid, zeroes the invoice in place (the record survives, stamped VOID) and posts the aggregate GL reversal,
     * so Sales and AR net back to exactly zero.
     *
     * <p>Without this, O1 would close one hole and open another: after O1 a storefront order HAS an invoice, so
     * cancelling while only returning stock would leave the revenue booked and overstate P&amp;L and the tax
     * register.
     *
     * <p>Idempotent in the way that matters: voiding an already-void invoice is refused rather than double-
     * reversing, so a retried cancellation cannot reverse the books twice.
     */
    @PostExchange("/internal/sales/reverse")
    void reverseSale(@RequestParam("invoiceNo") String invoiceNo, @RequestParam("reason") String reason);
}
