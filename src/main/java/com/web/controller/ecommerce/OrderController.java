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

    @RequestMapping(value = "/getOrders", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getOrders(final HttpServletRequest request) {
        try { return client.get("/orders"); }
        catch (Exception e) { LOGGER.error("getOrders proxy error", e); return Collections.singletonMap("success", false); }
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
