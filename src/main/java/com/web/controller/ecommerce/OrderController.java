package com.web.controller.ecommerce;

import java.util.Collections;
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

import com.web.util.MarketplaceRestClient;

/** Monolith proxy for e-commerce orders (E1, slice 46) → marketplace-service via the gateway (/api/marketplace/orders). */
@Controller
public class OrderController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private MarketplaceRestClient client;

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
}
