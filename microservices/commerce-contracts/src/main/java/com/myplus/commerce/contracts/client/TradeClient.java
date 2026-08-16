package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.commerce.contracts.dto.SaleReturnLine;
import com.myplus.commerce.contracts.dto.SaleReturnRequest;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
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

    /**
     * OMS O7 D4 — return PART of an invoice: the goods a shop refused at the door.
     *
     * <h3>Why {@link #reverseSale} could not do this</h3>
     * That is a FULL void. A door rejection of 2 of 10 is not a void — the other 8 were delivered, and the
     * shopkeeper is holding the invoice for all 10. Voiding it would cancel a document they have in their hand
     * and renumber their purchase record. <b>B2B-P3f settled this rule for the whole platform:</b> a return is a
     * CREDIT NOTE against the issued invoice, never a retro-edit of it, precisely because the customer has a
     * copy.
     *
     * <h3>Why it takes productId, not a line id</h3>
     * The caller (marketplace) knows what left its warehouse: products and quantities. {@code sell_id} is
     * business-service's own line identity and is not, and should not be, exported. So the contract speaks the
     * language both sides share and business-service does the translation — the same reason
     * {@link #recordSale} takes products rather than {@code Sell} rows.
     *
     * <h3>What happens on the other side</h3>
     * The SAME {@code saleReturn} path a counter return takes: {@code CRN-} credit note, stock back to
     * inventory, {@code SALE_RETURN} to the GL outbox, AR recomputed, audit written. <b>No new money logic
     * exists for this feature</b>, which is the whole point of routing it through the contract rather than
     * teaching marketplace to do accounting.
     *
     * @return the credit note numbers raised, one per line actually returned
     */
    @PostExchange("/internal/sales/return-lines")
    java.util.List<String> returnLines(@RequestBody SaleReturnRequest request);

    /**
     * OMS O7 D5 — money a channel collected on business-service's behalf, handed over to be allocated and posted.
     *
     * <h3>Why a channel cannot do this itself</h3>
     * Clearing a receivable means deciding which invoices a payment covers, moving the customer's running
     * balance, writing the entry in the shared payment ledger and posting {@code Dr cash / Cr AR}. All of that
     * is business-service's, and {@code CustomerService.receivePayment} already does it — FIFO across the
     * customer's open invoices through the ONE allocator AR and AP share, idempotent, period-lock checked. A
     * channel that allocated its own payments would be a second settlement rule; two rules disagreeing about
     * the same money is exactly what this contract exists to prevent.
     *
     * <h3>Why it is not on {@code /internal/sales}</h3>
     * A receipt is not a sale. It has its own controller, which needs none of the
     * {@code InternalSalesController} machinery — {@code receivePayment} is a service and can simply be called,
     * where {@code saleReturn} could only be reached through a controller (§12.5 debt).
     *
     * <h3>Idempotent, and the key must come from the CALLER's committed state</h3>
     * A repeat with the same {@code idempotencyKey} replays the original receipt rather than allocating twice.
     * That matters most when the caller's own transaction rolls back AFTER this call committed: the retry has
     * to present the same key, so it must be derived from a stable fact (the collection being remitted), never
     * from the retrying batch, which gets a new identity each attempt.
     *
     * <h3>Anti-IDOR</h3>
     * {@code customerId} arrives off the wire, so the receiver resolves it within the CALLER's tenant. Another
     * tenant's customer reads as absent — identically to a genuinely missing one, so the endpoint cannot be
     * used to probe which ids exist.
     */
    @PostExchange("/internal/receipts")
    com.myplus.commerce.contracts.dto.PaymentReceiptResult receivePayment(
            @RequestBody com.myplus.commerce.contracts.dto.PaymentReceiptRequest request);

    /**
     * The calling tenant's sales-tax policy, so a channel can price the way the BOOKS will.
     *
     * <h3>Why this op exists</h3>
     * Whether tax applies is a per-tenant switch owned by business-service (`tax_setting`). Marketplace's
     * checkout used to compute tax on its own — `net × product.taxRate / 100`, with no switch, no org
     * default and no INCLUSIVE handling — so a shop with tax turned OFF was shown a tax line and quoted a
     * total its own invoice then contradicted (quoted 22, invoiced 20). Both halves were locally correct;
     * only the disagreement between them was wrong, which is exactly the kind of defect a shared contract
     * exists to prevent.
     *
     * <h3>Policy here, arithmetic in the shared library</h3>
     * This returns only the POLICY. The maths is {@code com.myplus.commerce.domain.TaxMath}, which
     * business-service and every channel call, so the rule has one implementation rather than one per
     * caller. Returning a computed figure instead would have put a second engine on the wire.
     *
     * <h3>Tenant</h3>
     * Resolved from the CALLER's forwarded identity, never from a parameter — a channel cannot ask for
     * another tenant's policy because it has no way to name one.
     *
     * <p>Safe to cache briefly: this is configuration that changes at month-end, not per request, and the
     * storefront quote is a hot path.
     */
    @GetExchange("/internal/tax-policy")
    com.myplus.commerce.contracts.dto.TaxPolicyView taxPolicy();
}
