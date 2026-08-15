package com.web.controller.ecommerce;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.util.MarketplaceRestClient;

/** Monolith proxy for e-commerce orders (E1, slice 46) → marketplace-service via the gateway (/api/marketplace/orders). */
@Controller
public class OrderController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private MarketplaceRestClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The orders list (OMS O4) — now paginated and filtered.
     *
     * <p>Returns {@code data} as a {@code PageResponse}: {@code {content, pageNo, pageSize, totalElements,
     * totalPages, last}}. It used to be a bare array of every order the tenant had ever taken (OMS-7).
     *
     * <p>The filter parameters are relayed verbatim rather than parsed here. This proxy has no business deciding
     * what a valid status or page size is — {@code OrderQuery} clamps them server-side, and duplicating those
     * rules in the monolith would create exactly the second source of truth O4 exists to remove.
     */
    @RequestMapping(value = "/getOrders", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getOrders(final HttpServletRequest request) {
        try {
            // O7 D2: `mine` narrows to the caller's own booked orders. Relayed as the BOOLEAN it is — the
            // service resolves whose "mine" that is from the token, so a rep cannot read a colleague's orders
            // by editing an id in the URL.
            return client.get("/orders" + relayQuery(request,
                    "page", "size", "status", "paymentStatus", "source", "from", "to", "q", "late", "mine"));
        } catch (Exception e) {
            LOGGER.error("getOrders proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /**
     * One order in full (OMS O4) — lines, money, payment and the {@code order_events} timeline.
     *
     * <p>The detail endpoint existed in marketplace-service from slice 46 and had no monolith proxy, so the back
     * office could never open an order: it showed six columns and nothing else, while the SHOPPER's tracking
     * page could already see the timeline.
     */
    @RequestMapping(value = "/getOrder", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getOrder(final HttpServletRequest request) {
        try {
            String id = request.getParameter("id");
            return client.get("/orders/" + java.net.URLEncoder.encode(
                    id == null ? "" : id, java.nio.charset.StandardCharsets.UTF_8));
        } catch (HttpStatusCodeException e) {
            // A scoped miss is a 404 "Order not found" — another tenant's order is indistinguishable from a
            // missing one, and that wording is the marketplace's to give.
            return relayError(e, "Could not load the order.");
        } catch (Exception e) {
            LOGGER.error("getOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /**
     * OMS O5b — dispatch part or all of an order.
     *
     * <p>Replaces "Mark SHIPPED": the order becomes SHIPPED (or PARTIALLY_SHIPPED) because a parcel was
     * recorded, not because someone typed a status. The body carries the per-line quantities plus carrier and
     * tracking number.
     */
    @RequestMapping(value = "/shipOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> shipOrder(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            Map<String, Object> payload = new HashMap<>(body);
            payload.remove("id");
            return client.postJson("/orders/" + id + "/shipments", payload);
        } catch (HttpStatusCodeException e) {
            // "Cannot ship 6 of Widget — only 5 still to go" is the merchant's answer, not a server fault.
            return relayError(e, "Could not record the shipment.");
        } catch (Exception e) {
            LOGGER.error("shipOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /**
     * OMS O5c — what the shop still owes, and what it can now fill ({@code ?ready=true}).
     *
     * <p>A read, not a job: "can this be filled now?" is derived from stock that already exists, so a stored
     * flag would only go stale.
     */
    @RequestMapping(value = "/getBackorders", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getBackorders(final HttpServletRequest request) {
        try {
            // R5 (2026-08-10): the read is paged now, so page/size ride along. Relayed verbatim and clamped
            // server-side — the monolith has no business deciding what a valid page size is (same rule as
            // /getOrders above).
            return client.get("/orders/backorders" + relayQuery(request, "ready", "page", "size"));
        } catch (Exception e) {
            LOGGER.error("getBackorders proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Rebuild the named request parameters as an encoded query string; empty when none were supplied. */
    private static String relayQuery(HttpServletRequest request, String... names) {
        StringBuilder qs = new StringBuilder();
        for (String n : names) {
            String v = request.getParameter(n);
            if (v == null || v.isBlank()) continue;
            qs.append(qs.length() == 0 ? '?' : '&').append(n).append('=')
              .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        }
        return qs.toString();
    }

    /*
     * OMS O7 D1 — distribution pre-sales. Six thin proxies, one per review action.
     *
     * These exist NOW rather than with D2's booker screen on purpose: the 2026-08-10 review found three
     * capabilities shipped with no way to reach them (R7), and a service endpoint with no proxy is exactly that
     * shape. The SCREENS are D2/D4; these make the lifecycle drivable and gate-testable today.
     */

    /** Book an order at the outlet — creates a PENDING_APPROVAL order with no invoice and no stock movement. */
    @RequestMapping(value = "/bookOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> bookOrder(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/orders/booking", body); }
        catch (HttpStatusCodeException e) { return relayError(e, "Could not book the order."); }
        catch (Exception e) { LOGGER.error("bookOrder proxy error", e); return Collections.singletonMap("success", false); }
    }

    /**
     * Amend an order still under review.
     *
     * <p>Relays the downstream status rather than flattening it — a <b>409</b> here means another reviewer
     * changed the order first (D-2), and telling the user "could not save" instead of "someone else changed
     * this" would send them to reload and lose their edit for no stated reason.
     */
    @RequestMapping(value = "/amendOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> amendOrder(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body == null ? null : body.get("id");
            return client.putJson("/orders/" + enc(String.valueOf(id)), body);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not amend the order.");
        } catch (Exception e) {
            LOGGER.error("amendOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    @RequestMapping(value = "/confirmOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> confirmOrder(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body == null ? null : body.get("id");
            return client.postJson("/orders/" + enc(String.valueOf(id)) + "/confirm", java.util.Map.of());
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not confirm the order.");
        } catch (Exception e) {
            LOGGER.error("confirmOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    @RequestMapping(value = "/rejectOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> rejectOrder(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body == null ? null : body.get("id");
            Object reason = body == null ? null : body.get("reason");
            return client.postJson("/orders/" + enc(String.valueOf(id)) + "/reject",
                    java.util.Collections.singletonMap("reason", reason));
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not reject the order.");
        } catch (Exception e) {
            LOGGER.error("rejectOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    @RequestMapping(value = "/resubmitOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> resubmitOrder(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body == null ? null : body.get("id");
            return client.postJson("/orders/" + enc(String.valueOf(id)) + "/resubmit", java.util.Map.of());
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not resubmit the order.");
        } catch (Exception e) {
            LOGGER.error("resubmitOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /**
     * OMS O7 D4 — record what happened when a parcel reached the shop.
     *
     * <p>Relays the downstream status: this raises credit notes and takes money, so a refusal ("already
     * recorded", "nothing can be credited") must reach the admin verbatim rather than as a generic failure.
     */
    @RequestMapping(value = "/recordDelivery", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> recordDelivery(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body == null ? null : body.get("id");
            return client.postJson("/orders/" + enc(String.valueOf(id)) + "/delivery", body);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not record the delivery.");
        } catch (Exception e) {
            LOGGER.error("recordDelivery proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** What has been keyed against this order's parcels. */
    @RequestMapping(value = "/getDeliveries", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getDeliveries(final HttpServletRequest request) {
        try {
            String id = request.getParameter("id");
            return client.get("/orders/" + enc(id == null ? "" : id) + "/deliveries");
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not load the delivery history.");
        } catch (Exception e) {
            LOGGER.error("getDeliveries proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Who changed what on this order, and why. */
    @RequestMapping(value = "/getOrderAmendments", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getOrderAmendments(final HttpServletRequest request) {
        try {
            String id = request.getParameter("id");
            return client.get("/orders/" + enc(id == null ? "" : id) + "/amendments");
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not load the amendment history.");
        } catch (Exception e) {
            LOGGER.error("getOrderAmendments proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    @RequestMapping(value = "/recordOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> recordOrder(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/orders", body); }
        catch (Exception e) { LOGGER.error("recordOrder proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/updateOrderStatus", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateOrderStatus(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            return client.putJson("/orders/" + id + "/status", Collections.singletonMap("status", body.get("status")));
        } catch (HttpStatusCodeException e) {
            // OMS O2: a refused transition ("a CANCELLED order cannot become SHIPPED") or a denied reversal
            // ("that needs an admin") is a business answer, not a server fault — relay the marketplace's own
            // wording, exactly as refundOrder below already does. Swallowing it left the operator with a silent
            // failure and no idea what to do differently.
            return relayError(e, "Could not update the order status.");
        } catch (Exception e) {
            LOGGER.error("updateOrderStatus proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Back-office refund (E6, slice 70) — amount optional (omit = full remaining refund). */
    @RequestMapping(value = "/refundOrder", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> refundOrder(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            return client.postJson("/orders/" + id + "/refund", Collections.singletonMap("amount", body.get("amount")));
        } catch (HttpStatusCodeException e) {
            // Expected business rejection (e.g. COD order, over-refund) — relay the marketplace's message; not a server error.
            return relayError(e, "Could not refund the order.");
        } catch (Exception e) {
            LOGGER.error("refundOrder proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Back-office process a return (E10, slice 71) — stock back + refund → RETURNED. */
    @RequestMapping(value = "/processReturn", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> processReturn(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("id");
            return client.postJson("/orders/" + id + "/return", Collections.emptyMap());
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not process the return.");
        } catch (Exception e) {
            LOGGER.error("processReturn proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    /** Relay the marketplace's {success,message} body to the caller instead of swallowing it into a bare failure. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> relayError(HttpStatusCodeException e, String fallback) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        try {
            Map<String, Object> err = objectMapper.readValue(e.getResponseBodyAsString(), Map.class);
            out.put("message", err.get("message") != null ? err.get("message") : fallback);
        } catch (Exception ignore) {
            out.put("message", fallback);
        }
        return out;
    }
}
