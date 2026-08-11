package com.myplus.marketplace.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;

import lombok.RequiredArgsConstructor;

/**
 * OMS O7 D1 — raise the invoice when the goods actually leave (`ON_DISPATCH`).
 *
 * <h3>Why the invoice waits for dispatch</h3>
 * A distributor's order is booked by a field rep and then <b>reviewed, amended, confirmed or rejected</b> by
 * the warehouse. If booking raised an invoice, an amendment would be an edit to an issued fiscal document, a
 * rejection would be a void, and the sales-tax register would fill with invoices for orders nobody approved.
 * Waiting until dispatch also means the invoice is raised from <b>what physically went out</b>, so a pack
 * shortfall or a batch substitution is invoiced correctly the first time instead of being corrected by a credit
 * note afterwards.
 *
 * <h3>Why this is its own class</h3>
 * {@code ShipmentService} needs it and {@code OrderService} already depends on {@code ShipmentService}, so
 * putting it on {@code OrderService} would make the two services mutually dependent — a cycle Spring refuses
 * without {@code @Lazy}, and a design smell either way. One collaborator, one job, injected by whoever needs it.
 *
 * <h3>What it does NOT do</h3>
 * No money logic of its own. It calls the same {@code TradeClient → /internal/sales} contract O1 established, so
 * business-service's single revenue path still does the pricing, tax, COGS, GL, AR and idempotency. The
 * direction of the dependency is unchanged: marketplace → business-service, never the reverse.
 */
@Service
@RequiredArgsConstructor
public class DispatchInvoiceService {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchInvoiceService.class);

    private final com.myplus.commerce.contracts.client.TradeClient tradeClient;

    /**
     * Invoice an order that is being dispatched, if it has not been invoiced already.
     *
     * @param dispatchedNow orderItemId → quantity going out in THIS parcel
     * @return the invoice number, or {@code null} when nothing needed invoicing
     *
     * <p><b>Only for FIELD orders.</b> A storefront or POS order was invoiced when it was placed (O1/O5e) and
     * must not be invoiced twice, so this returns immediately for any other channel. That is the guard against
     * the genuinely dangerous failure here — billing a customer a second time for goods they already paid for.
     * See the body for why it keys on {@code source} and emphatically not on {@code booksStatus}.
     *
     * <p><b>The invoice covers what is in THIS parcel</b>, not the whole order. A part-dispatched order is
     * part-invoiced, which is the same rule O5c applied to backorders: you invoice what you deliver. A second
     * parcel raises a second invoice, and each one matches a physical movement.
     *
     * <p><b>Failure is fatal to the dispatch, deliberately.</b> Everywhere else in this codebase a bookkeeping
     * call is best-effort, because the money was already correct and only a record was at risk. Here it is the
     * opposite: if the invoice cannot be raised, the goods must not leave. Letting the parcel go would send
     * stock out of the building with no sale, no AR and no tax record behind it — which is precisely OMS-1, the
     * defect this whole programme began with.
     */
    public String invoiceForDispatch(Order order, Map<Long, Integer> dispatchedNow) {
        // Which orders invoice HERE rather than at placement? A FIELD order — booked by a rep, released by the
        // warehouse — and nothing else. POS and storefront orders were invoiced when they were placed (O1/O5e)
        // and must never be invoiced a second time.
        //
        // ── This guard read `booksStatus == AWAITING_DISPATCH` and that was a LIVE DEFECT ──────────────────
        // `ShipmentService` sets `booksStatus = POSTED` after the first successful dispatch, so from the SECOND
        // parcel onwards the guard was false and this returned null: no invoice was raised at all, the order
        // kept parcel one's number, and the goods left the building with nothing behind them. That is OMS-1,
        // the defect this entire programme began with, on the partial-delivery path that is a distributor's
        // normal week. Caught by `order-approval.cy.js` — and note it caught a defect DIFFERENT from the one
        // the case was written for.
        //
        // The lesson is about what a guard may key on: this question is "does this order invoice at dispatch?",
        // which is fixed for the order's whole life, so it must be answered by something that does not move.
        // `booksStatus` answers "has it reached the books yet?" and changes as the order progresses — a state,
        // used where a property was needed. `source` is stamped once at booking and never changes.
        //
        // Double-invoicing the SAME parcel is prevented by the idempotency key below, which is what a key is
        // for. The two guards are complementary: this one decides whether to invoice at all, the key decides
        // whether this exact parcel has already been invoiced.
        if (order == null || !"FIELD".equals(order.getSource())) {
            return null;                    // invoiced at placement (O1/O5e), or not an order at all
        }
        List<SaleRecordRequest.Line> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            Integer qty = dispatchedNow.get(item.getId());
            if (qty == null || qty <= 0 || item.getProductId() == null) continue;
            lines.add(SaleRecordRequest.Line.builder()
                    .productId(item.getProductId())
                    .quantity(qty.floatValue())
                    // The price the order was CONFIRMED at, including any amendment the reviewer made (D-3).
                    // Sending it rather than letting the catalog re-price is the point: the shopkeeper agreed a
                    // price with the booker, and the invoice must honour it, not today's list price.
                    .unitPrice(item.getPrice())
                    .description(item.getProductName())
                    .build());
        }
        if (lines.isEmpty())
            throw new ValidationException("Nothing dispatchable on this order — no invoice can be raised.");

        // Idempotent per ORDER + parcel: a retried dispatch must replay the same invoice, never mint a second.
        //
        // The key includes the order's ALREADY-SHIPPED state, and that is the whole subtlety. Keying on the
        // parcel contents alone looks right and is wrong: shipping 2 units today and 2 more tomorrow from the
        // same line produces an IDENTICAL {line × qty}, so the second dispatch would replay the first invoice
        // and the goods would leave with no invoice behind them — OMS-1, the defect this programme began with,
        // reintroduced in the one place that raises invoices. Partial delivery is routine in this business, so
        // that is a likely path, not a corner.
        //
        // `quantityShipped` is advanced only by a COMMITTED dispatch, which makes it exactly the counter needed:
        //   * two sequential identical parcels see different before-states  ⇒ different keys ⇒ two invoices;
        //   * a RETRY after this transaction rolled back sees the same before-state ⇒ same key ⇒ one invoice.
        // The second case is the one that matters: the sale commits remotely, so a local rollback would
        // otherwise leave a committed invoice that a retry duplicates.
        String key = "SO-" + order.getId() + "-S" + alreadyShipped(order) + "-D" + dispatchKey(dispatchedNow);

        SaleRecordResult sale = asOrg(order.getOrganizationId(), () -> tradeClient.recordSale(
                SaleRecordRequest.builder()
                        .idempotencyKey(key)
                        .organizationId(order.getOrganizationId())
                        .channel("FIELD")
                        .customer(SaleRecordRequest.Customer.builder()
                                .name(order.getCustomerName())
                                .contact(order.getCustomerContact())
                                .address(order.getShippingAddress())
                                .build())
                        .lines(lines)
                        .build()));

        if (sale == null || sale.getInvoiceNo() == null)
            throw new ValidationException("The dispatch could not be invoiced. Nothing has been sent.");

        LOG.info("Order {} dispatched and invoiced as {}", order.getOrderNo(), sale.getInvoiceNo());
        return sale.getInvoiceNo();
    }

    /**
     * How much of this order has already gone out, across every line.
     *
     * <p>Read BEFORE the caller advances the quantities — {@code ShipmentService} invoices first and applies the
     * line movements afterwards, so this sees the state as of the last committed dispatch. That ordering is what
     * makes the idempotency key above distinguish a genuine second parcel from a retry of the first.
     */
    private static int alreadyShipped(Order order) {
        int shipped = 0;
        for (OrderItem it : order.getItems())
            shipped += it.getQuantityShipped() == null ? 0 : it.getQuantityShipped();
        return shipped;
    }

    /** A stable key for this exact set of dispatched quantities, so a retry of the SAME parcel deduplicates. */
    private static String dispatchKey(Map<Long, Integer> dispatchedNow) {
        return dispatchedNow.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "x" + e.getValue())
                .reduce((a, b) -> a + "_" + b).orElse("none");
    }

    /** Stamp the tenant on the outbound call — the warehouse user's identity does not travel to inventory. */
    private <T> T asOrg(Long org, java.util.function.Supplier<T> call) {
        java.util.concurrent.atomic.AtomicReference<T> out = new java.util.concurrent.atomic.AtomicReference<>();
        GatewayIdentityForwarding.runAs(0L, org, () -> out.set(call.get()));
        return out.get();
    }
}
