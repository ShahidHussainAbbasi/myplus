package com.web.controller.ecommerce;

import java.util.Collections;
import java.util.Map;

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

/** Monolith proxy for e-commerce coupons (E13, slice 72) → marketplace-service via the gateway (/api/marketplace/coupons). */
@Controller
public class CouponController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MarketplaceRestClient client;

    @RequestMapping(value = "/getCoupons", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getCoupons() {
        try { return client.get("/coupons"); }
        catch (Exception e) { LOGGER.error("getCoupons proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/addCoupon", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addCoupon(@RequestBody final Map<String, Object> body) {
        try {
            return client.postJson("/coupons", body);
        } catch (HttpStatusCodeException e) {
            // Expected business rejection (duplicate code, bad value) — relay the marketplace's message.
            Map<String, Object> out = new java.util.HashMap<>();
            out.put("success", false);
            try {
                Map<String, Object> err = objectMapper.readValue(e.getResponseBodyAsString(), Map.class);
                out.put("message", err.get("message") != null ? err.get("message") : "Could not create the coupon.");
            } catch (Exception ignore) {
                out.put("message", "Could not create the coupon.");
            }
            return out;
        } catch (Exception e) {
            LOGGER.error("addCoupon proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }
}
