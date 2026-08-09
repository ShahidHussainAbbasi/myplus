package com.web.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * OMS O5e step 3 — a Store-vertical sale produces its order <b>server-side</b>, so the browser reports
 * neither the sale nor the order.
 *
 * <h3>Why the monolith and not business-service</h3>
 * §2.5 of {@code oms-O5e-pos-order-parity.md} weighed three options and took <b>C</b>. business-service has no
 * marketplace client, and giving it one would make the pair mutually dependent — marketplace already calls
 * business-service for the money ({@code TradeClient} &rarr; {@code /internal/sales}, which is what O1
 * established). The monolith is already on both sides of that line: it proxies {@code /addSell} and it proxies
 * {@code /orders}. Orchestrating here closes the actual defect — <i>close the tab, lose the order</i> — without
 * inverting the service dependency, and with no new client and no new transport.
 *
 * <p>The trade, stated plainly: the order is created by the orchestrator rather than atomically with the sale,
 * so a crash of <em>this</em> JVM between the two still loses an order. That is strictly better than today,
 * where a <em>browser</em> crash loses it, and it is what an outbox (Track C) fixes once a broker exists.
 *
 * <h3>Best-effort, never fatal</h3>
 * The money is already written and correct by the time this runs. A failure to record the order must never
 * fail the sale, so every path here ends in a WARN — never a throw. Step 1's idempotency on {@code invoiceNo}
 * means a later retry (including the browser's, which is still live until step 4) converges on one order.
 */
@Component
public class PosOrderRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PosOrderRecorder.class);

    /**
     * The Store vertical, in the one vocabulary {@code User.userType} and {@code Organization.type} share.
     * The browser's equivalent gate is {@code window.MODULE === 'MARKETPLACE'} (main.js:1013).
     */
    static final String STORE_VERTICAL = "MARKETPLACE";

    @Autowired
    private BusinessRestClient business;

    @Autowired
    private MarketplaceRestClient marketplace;

    @Autowired
    private RequestUtil requestUtil;

    /**
     * Called by {@code SellController.addSell} with business-service's own response, immediately after the
     * sale returns.
     *
     * <p>Takes the whole response rather than an invoice number so that "did the sale actually succeed, and
     * what is its invoice number" is decided in ONE place — see {@link #invoiceNoOf(Map)} — instead of being
     * re-derived by every caller. The controller stays the thin proxy it is everywhere else.
     */
    public void afterSale(final Map<String, Object> addSellResponse) {
        try {
            String invoiceNo = invoiceNoOf(addSellResponse);
            if (invoiceNo == null) {
                return;                                     // the sale did not complete — there is no order to record
            }
            if (!isStoreVertical()) {
                return;                                     // a trade/pharmacy sale is an invoice, not an order
            }

            // The authoritative read. addSell's response carries ONLY the invoice number
            // (GenericResponse("SUCCESS", msg, invoiceNo)), so the server total and the persisted lines have to
            // be read back — and reading the invoice back is the only way to be sure the order describes what
            // was WRITTEN rather than what was asked for. That is gap B (the client-computed total) and gap A
            // (no line items) closed from one source.
            Map<String, Object> receipt = business.get("/getReceipt",
                    "invoiceNo=" + java.net.URLEncoder.encode(invoiceNo, java.nio.charset.StandardCharsets.UTF_8));

            Map<String, Object> order = orderFrom(receipt, invoiceNo);
            if (order == null) {
                LOGGER.warn("Sale {} completed but its invoice could not be read back; no order recorded. "
                        + "The sale is correct and unaffected.", invoiceNo);
                return;
            }

            Map<String, Object> resp = marketplace.postJson("/orders", order);
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                LOGGER.warn("Sale {} completed but marketplace refused the order: {}. The sale is correct and "
                        + "unaffected; a retry converges on one order (idempotency key = invoiceNo).",
                        invoiceNo, resp);
            }
        } catch (Exception e) {
            // Deliberately swallowed. The invoice, the GL posting and the stock movement are already committed;
            // failing the cashier's sale because a fulfilment row could not be written would turn a recoverable
            // omission into a lost sale.
            LOGGER.warn("Could not record the order for a completed Store sale. The sale is correct and "
                    + "unaffected.", e);
        }
    }

    /**
     * Is the tenant this sale was written into a Store?
     *
     * <p><b>Resolved against {@link ModuleRouter#moduleOf}, not {@code User.userType} alone.</b> The browser's
     * {@code window.MODULE} comes from {@code CommerceDashboardController.resolveModule()}, which reads only
     * {@code userType} — so for a user who owns both a shop and a store, it names the person's type rather than
     * the tenant they are working in. {@code ModuleRouter} is the platform's single documented rule (active org
     * type, falling back to user type) and it follows the org the invoice was actually written into, which is
     * the org the order must be created in. The two agree for every single-module user; where they disagree,
     * {@code ModuleRouter} is the one that is right.
     *
     * <p>Widening the gate cannot double-record: step 1's idempotency on {@code invoiceNo} makes the browser's
     * still-live post a no-op.
     */
    private boolean isStoreVertical() {
        return STORE_VERTICAL.equals(ModuleRouter.moduleOf(requestUtil.getCurrentUser()));
    }

    // ── Pure mapping (no Spring, no I/O) — this is what PosOrderRecordTest pins ───────────────────────────

    /**
     * The invoice number of a SUCCESSFUL sale, or {@code null} for anything else.
     *
     * <p>business-service answers a rejected sale with a 200 and a {@code FAILED} / {@code ERROR} /
     * {@code CONFIRM} envelope (period closed, insufficient stock, credit limit unacknowledged, a clinical
     * rule). Every one of those wrote nothing, so treating the envelope as a completed sale would create an
     * order for an invoice that does not exist.
     */
    static String invoiceNoOf(final Map<String, Object> addSellResponse) {
        if (addSellResponse == null || !"SUCCESS".equals(addSellResponse.get("status"))) {
            return null;
        }
        Object object = addSellResponse.get("object");
        String invoiceNo = object == null ? null : String.valueOf(object).trim();
        return (invoiceNo == null || invoiceNo.isEmpty()) ? null : invoiceNo;
    }

    /**
     * Build the order body from the invoice business-service persisted — the {@code /getReceipt} envelope.
     *
     * <p>Returns {@code null} when the invoice could not be read, which the caller logs and drops. Every value
     * here comes off the stored invoice: {@code grandTotal} is the figure the sale posted to the ledger, and
     * the lines are the rows stock actually moved against. Nothing is recomputed, because a second derivation
     * of a total is how a UI comes to show a number the books disagree with.
     */
    static Map<String, Object> orderFrom(final Map<String, Object> receipt, final String invoiceNo) {
        if (receipt == null || !"SUCCESS".equals(receipt.get("status"))) {
            return null;
        }
        Object object = receipt.get("object");
        if (!(object instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> invoice = (Map<String, Object>) object;

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("invoiceNo", invoiceNo);
        order.put("customerName", customerNameOf(invoice));
        order.put("total", decimal(invoice.get("grandTotal")));
        if (invoice.get("paymentMode") != null) {
            order.put("paymentMode", String.valueOf(invoice.get("paymentMode")));
        }
        order.put("items", linesOf(invoice, invoiceNo));
        return order;
    }

    private static String customerNameOf(final Map<String, Object> invoice) {
        Object customer = invoice.get("customer");
        if (customer instanceof Map) {
            Object name = ((Map<?, ?>) customer).get("name");
            if (name != null && !String.valueOf(name).isBlank()) {
                return String.valueOf(name);
            }
        }
        return null;
    }

    /**
     * The sale's lines, in {@code OrderDTO.Line} shape.
     *
     * <p>A line with no {@code productId} is dropped: {@code OrderService.toItems} skips those anyway, and an
     * order item that names no product is one cancel cannot restore stock for — the very thing this slice
     * exists to make possible.
     */
    private static List<Map<String, Object>> linesOf(final Map<String, Object> invoice, final String invoiceNo) {
        List<Map<String, Object>> lines = new ArrayList<>();
        Object sales = invoice.get("sales");
        if (!(sales instanceof List)) {
            return lines;
        }
        for (Object row : (List<?>) sales) {
            if (!(row instanceof Map)) {
                continue;
            }
            Map<?, ?> sale = (Map<?, ?>) row;
            Long productId = longOf(sale.get("productId"));
            if (productId == null) {
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productId", productId);
            line.put("productName", sale.get("itemName") == null ? null : String.valueOf(sale.get("itemName")));
            line.put("quantity", quantityOf(sale.get("quantity"), invoiceNo));
            line.put("price", unitPriceOf(sale));
            lines.add(line);
        }
        return lines;
    }

    /**
     * A sale line's quantity as an order line's whole number.
     *
     * <p><b>Known limitation, carried deliberately.</b> {@code Sell.quantity} is a {@code Float} — a POS can
     * sell 1.5&nbsp;kg — while {@code OrderItem.quantity} is an {@code Integer}, because the storefront that
     * shaped it only ever sold whole units. Widening the order line is a marketplace schema change and is out
     * of O5e's scope, so a fractional quantity is rounded and WARNed: a cancel would then restore the rounded
     * figure, which is worth knowing about in the log rather than discovering in a stock count.
     */
    private static int quantityOf(final Object raw, final String invoiceNo) {
        BigDecimal q = decimal(raw);
        if (q == null) {
            return 0;
        }
        int rounded = q.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (q.compareTo(BigDecimal.valueOf(rounded)) != 0) {
            LOGGER.warn("Invoice {} has a fractional line quantity ({}); the order line records {} because "
                    + "OrderItem.quantity is a whole number. A cancel would restore the rounded quantity.",
                    invoiceNo, q, rounded);
        }
        return rounded;
    }

    /**
     * What the line SOLD at, per unit. {@code sellRate} is the cashier's rate as persisted (it may override the
     * catalog price); {@code totalAmount / quantity} is the fallback for a row written before {@code sellRate}
     * was carried through the relay.
     */
    private static BigDecimal unitPriceOf(final Map<?, ?> sale) {
        BigDecimal sellRate = decimal(sale.get("sellRate"));
        if (sellRate != null) {
            return sellRate;
        }
        BigDecimal total = decimal(sale.get("totalAmount"));
        BigDecimal qty = decimal(sale.get("quantity"));
        if (total == null || qty == null || qty.signum() == 0) {
            return null;
        }
        return total.divide(qty, 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Any JSON number as a {@code BigDecimal}. Jackson hands these back as Integer, Double or BigDecimal
     * depending on the value, so going through the string form is what keeps one code path for all three.
     */
    private static BigDecimal decimal(final Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Long longOf(final Object raw) {
        BigDecimal d = decimal(raw);
        return d == null ? null : d.longValue();
    }
}
