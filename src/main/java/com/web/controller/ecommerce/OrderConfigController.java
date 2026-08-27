package com.web.controller.ecommerce;

import com.web.util.ProxyErrors;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.MarketplaceRestClient;

/**
 * OMS O3 — Owner "Order settings" proxy → marketplace-service's shared common-settings endpoint
 * ({@code /settings} behind {@code /api/marketplace}).
 *
 * <p>Without this the O3 catalog would be honoured by {@code ShippingPolicy} but unreachable: a shop could
 * not change its own delivery fee, free-delivery threshold, or switch cash-on-delivery off. Deliberately the
 * SAME shape as {@link com.web.controller.business.BusinessConfigController} — the shared controller keys on
 * the HTTP verb, so the monolith exposes one path per verb and the one renderer in
 * {@code /js/common/settings-form.js} serves this screen too.
 *
 * <p>Authorisation is NOT decided here: marketplace's {@code @PreAuthorize("ROLE_OWNER or ADMIN_PRIVILEGE")}
 * on the save is the real gate, reached with the caller's own JWT.
 */
@Controller
public class OrderConfigController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private MarketplaceRestClient client;

    /** The order/checkout settings catalog with each entry's effective value for this org. */
    @RequestMapping(value = "/getOrderConfig", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getOrderConfig(final HttpServletRequest request) {
        try {
            return client.get("/settings");
        } catch (Exception e) {
            LOGGER.error("getOrderConfig proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Upsert one override (key + value). Relays the service's refusal rather than reporting a bare failure. */
    @RequestMapping(value = "/saveOrderConfig", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveOrderConfig(final HttpServletRequest request) {
        try {
            String key = request.getParameter("key");
            String value = request.getParameter("value");
            // The shared endpoint reads key/value as @RequestParam, so they travel on the URL.
            String qs = "key=" + enc(key) + (value != null ? "&value=" + enc(value) : "");
            return client.postJson("/settings?" + qs, java.util.Map.of());
        } catch (Exception e) {
            LOGGER.error("saveOrderConfig proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
