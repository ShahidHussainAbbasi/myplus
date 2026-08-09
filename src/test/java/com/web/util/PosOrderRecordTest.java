package com.web.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * OMS O5e step 3 — the sale, not the browser, decides what the order says.
 *
 * <p>Pure logic, no Spring, so it runs on every {@code mvn test}. The two mapping decisions pinned here are
 * exactly the two gaps OMS-5 named: the total must be the one the SALE posted to the books (gap B), and the
 * lines must be present at all, because {@code cancel}/{@code return} are guarded by {@code !items.isEmpty()}
 * and a line-less order can never restore stock (gap A).
 *
 * <p>Idempotency is deliberately NOT tested here — it lives in {@code OrderService.record}, shipped and gated
 * as step 1, and re-asserting it against a mapper would only pin the wrong layer.
 */
class PosOrderRecordTest {

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> envelope(String status, Object object) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("object", object);
        return m;
    }

    private static Map<String, Object> line(Object productId, String itemName, Object qty, Object sellRate,
            Object totalAmount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("itemName", itemName);
        m.put("quantity", qty);
        m.put("sellRate", sellRate);
        m.put("totalAmount", totalAmount);
        return m;
    }

    /** A {@code /getReceipt} envelope: the invoice as business-service actually persisted it. */
    private static Map<String, Object> receipt(Object grandTotal, List<Map<String, Object>> sales) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Ayesha");
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("invoiceNo", "INV-100");
        invoice.put("grandTotal", grandTotal);
        invoice.put("paymentMode", "CASH");
        invoice.put("customer", customer);
        invoice.put("sales", sales);
        return envelope("SUCCESS", invoice);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> order) {
        return (List<Map<String, Object>>) order.get("items");
    }

    // ── the sale is the source of truth ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("gap B — the total comes from the SALE, never the request")
    class ServerTotal {

        @Test
        @DisplayName("the order total is the invoice's grandTotal")
        void totalIsTheInvoiceGrandTotal() {
            // The browser used to sum its own cart (ecommerce.js summed global.data). SF-12 is the standing
            // proof that cart arithmetic can be wrong in ways the books are not: a scanned-only cart totalled
            // ZERO. grandTotal is the figure the sale posted to the ledger, so it is the only defensible one.
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(7L, "Rice 5kg", 2, 850, 1700));
            Map<String, Object> order = PosOrderRecorder.orderFrom(receipt(new BigDecimal("1955.50"), sales), "INV-100");

            assertNotNull(order);
            assertEquals(new BigDecimal("1955.50"), order.get("total"));
        }

        @Test
        @DisplayName("the total is NOT the sum of the lines — tax and discount live only on the header")
        void totalIsNotRecomputedFromLines() {
            // 2 x 850 = 1700 of goods, but the invoice was written for 1955.50 (tax). Re-deriving the total
            // from the lines here would be the client's arithmetic moved to the server, not the server's total.
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(7L, "Rice 5kg", 2, 850, 1700));
            Map<String, Object> order = PosOrderRecorder.orderFrom(receipt(new BigDecimal("1955.50"), sales), "INV-100");

            assertEquals(new BigDecimal("1955.50"), order.get("total"));
        }

        @ParameterizedTest(name = "grandTotal arriving as {0}")
        @ValueSource(strings = {"1955.50", "1955", "0.00"})
        @DisplayName("an integer, a decimal or a zero total all map exactly")
        void numbersSurviveTheJsonRoundTrip(String raw) {
            Map<String, Object> order = PosOrderRecorder.orderFrom(receipt(new BigDecimal(raw),
                    new ArrayList<>()), "INV-100");
            assertEquals(new BigDecimal(raw), order.get("total"));
        }
    }

    @Nested
    @DisplayName("gap A — the lines are what make cancel able to restore stock")
    class Lines {

        @Test
        @DisplayName("every sale line becomes an order line, with its product, quantity and sold rate")
        void linesArePersisted() {
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(7L, "Rice 5kg", 2, 850, 1700));
            sales.add(line(9L, "Tea 250g", 1, 320, 320));
            List<Map<String, Object>> items = itemsOf(PosOrderRecorder.orderFrom(receipt(2020, sales), "INV-100"));

            assertEquals(2, items.size());
            assertEquals(7L, items.get(0).get("productId"));
            assertEquals("Rice 5kg", items.get(0).get("productName"));
            assertEquals(2, items.get(0).get("quantity"));
            assertEquals(new BigDecimal("850"), items.get(0).get("price"));
            assertEquals(9L, items.get(1).get("productId"));
        }

        @Test
        @DisplayName("the price is the rate the line SOLD at, not the catalog price")
        void priceIsTheSoldRate() {
            // sellRate exists precisely because a cashier can override the catalog price, and the omission of
            // this field from the relay DTO once stored sell_rate=1000 against total_amount=850.
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(7L, "Rice 5kg", 1, 850, 850));
            assertEquals(new BigDecimal("850"),
                    itemsOf(PosOrderRecorder.orderFrom(receipt(850, sales), "INV-100")).get(0).get("price"));
        }

        @Test
        @DisplayName("a row written before sellRate was carried falls back to total / quantity")
        void priceFallsBackToTheLineTotal() {
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(7L, "Rice 5kg", 4, null, 3400));
            assertEquals(new BigDecimal("850.00"),
                    itemsOf(PosOrderRecorder.orderFrom(receipt(3400, sales), "INV-100")).get(0).get("price"));
        }

        @Test
        @DisplayName("a line with no productId is dropped rather than sent as an unrestorable item")
        void linesWithoutAProductAreDropped() {
            // OrderService.toItems skips these anyway; sending them would inflate the order with rows cancel
            // cannot put back — the exact failure mode this slice exists to remove.
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(null, "Ad-hoc charge", 1, 50, 50));
            sales.add(line(7L, "Rice 5kg", 1, 850, 850));
            List<Map<String, Object>> items = itemsOf(PosOrderRecorder.orderFrom(receipt(900, sales), "INV-100"));

            assertEquals(1, items.size());
            assertEquals(7L, items.get(0).get("productId"));
        }

        @Test
        @DisplayName("a fractional quantity is rounded, because an order line is a whole number")
        void fractionalQuantityIsRounded() {
            // Known limitation, logged at WARN: Sell.quantity is a Float (1.5 kg is a real POS sale) and
            // OrderItem.quantity is an Integer. Widening the order line is a marketplace schema change.
            List<Map<String, Object>> sales = new ArrayList<>();
            sales.add(line(7L, "Mince", 1.5, 600, 900));
            assertEquals(2, itemsOf(PosOrderRecorder.orderFrom(receipt(900, sales), "INV-100")).get(0).get("quantity"));
        }
    }

    // ── only a sale that happened becomes an order ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("a rejected sale produces no order")
    class OnlyCompletedSales {

        @ParameterizedTest(name = "addSell answered {0}")
        @ValueSource(strings = {"FAILED", "ERROR", "CONFIRM", "NOT_FOUND"})
        @DisplayName("a non-SUCCESS envelope yields no invoice number")
        void rejectedSalesAreNotOrders(String status) {
            // business-service answers all of these with HTTP 200: period closed, insufficient stock, an
            // unacknowledged credit limit, a clinical rule. Every one of them wrote NOTHING, so an order built
            // from one would reference an invoice that does not exist.
            assertNull(PosOrderRecorder.invoiceNoOf(envelope(status, "INV-100")));
        }

        @Test
        @DisplayName("a SUCCESS with no invoice number yields nothing")
        void successWithoutAnInvoiceNumber() {
            assertNull(PosOrderRecorder.invoiceNoOf(envelope("SUCCESS", null)));
            assertNull(PosOrderRecorder.invoiceNoOf(envelope("SUCCESS", "   ")));
        }

        @Test
        @DisplayName("a completed sale yields its invoice number")
        void completedSale() {
            assertEquals("INV-100", PosOrderRecorder.invoiceNoOf(envelope("SUCCESS", "INV-100")));
        }

        @Test
        @DisplayName("an unreadable invoice yields no order rather than a half-built one")
        void unreadableInvoice() {
            // The sale is already committed and correct. Better no order — which idempotency lets a retry
            // create later — than an order carrying a total nobody can vouch for.
            assertNull(PosOrderRecorder.orderFrom(null, "INV-100"));
            assertNull(PosOrderRecorder.orderFrom(envelope("NOT_FOUND", null), "INV-100"));
            assertNull(PosOrderRecorder.orderFrom(envelope("SUCCESS", "not-an-invoice"), "INV-100"));
        }
    }

    @Test
    @DisplayName("the order carries the invoice number as its idempotency key")
    void invoiceNumberIsTheKey() {
        // Step 1 keys on invoiceNo. If the body did not carry it, the browser's still-live post and this one
        // would be two writers with no key — two orders for one sale, which is what §2.3's ordering prevents.
        Map<String, Object> order = PosOrderRecorder.orderFrom(receipt(850, new ArrayList<>()), "INV-100");
        assertEquals("INV-100", order.get("invoiceNo"));
        assertEquals("Ayesha", order.get("customerName"));
        assertTrue(order.containsKey("items"));
    }
}
