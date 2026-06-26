package com.web.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Public e-commerce storefront (slice 47). No auth (the {@code /store} page + {@code /storefront/**} proxies are
 * permitted in {@code SecSecurityConfig}). Reuses the {@link DemoController} pattern: proxies to the gateway's OPEN
 * storefront routes (anonymous — gateway allow-lists {@code /api/catalog/public/}, {@code /api/inventory/public/}
 * + {@code /api/marketplace/public/}).
 */
@Controller
public class StorefrontController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorefrontController.class);

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The public storefront page. {@code ?org=} selects the store; defaults to the demo store. */
    @GetMapping("/store")
    public String store() {
        return "store";
    }

    /**
     * Browse a store's active products (anonymous), enriched with each product's available stock so the storefront
     * can disable / badge out-of-stock items and cap the cart quantity — the shopper never gets to checkout an item
     * that the inventory reservation would reject.
     */
    @GetMapping("/storefront/products")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public Object products(@RequestParam(value = "org", required = false, defaultValue = "0") Long org) {
        try {
            Map<String, Object> resp = restTemplate.getForObject(
                    gatewayUrl + "/api/catalog/public/products?org=" + org, Map.class);
            if (resp == null) return Map.of("success", false, "message", "Could not load products.");

            // Best-effort availability merge — if inventory is unreachable, products still render (treated as
            // available) rather than blanking the whole store.
            Map<Long, Number> avail = availabilityByProduct(org);
            Object data = resp.get("data");
            if (data instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> p) {
                        Map<String, Object> pm = (Map<String, Object>) p;
                        Object id = pm.get("id");
                        Number a = (id instanceof Number n) ? avail.get(n.longValue()) : null;
                        // No inventory row -> unknown; expose null so the UI can decide (defaults to buyable).
                        pm.put("available", a == null ? null : a.doubleValue());
                    }
                }
            }
            return resp;
        } catch (Exception e) {
            LOGGER.error("storefront products proxy error", e);
            return Map.of("success", false, "message", "Could not load products.");
        }
    }

    /** Per-product available quantity for the store, keyed by productId (empty map on any inventory error). */
    @SuppressWarnings("unchecked")
    private Map<Long, Number> availabilityByProduct(Long org) {
        try {
            Map<String, Object> resp = restTemplate.getForObject(
                    gatewayUrl + "/api/inventory/public/availability?org=" + org, Map.class);
            Object data = (resp == null) ? null : resp.get("data");
            if (data instanceof Map<?, ?> m) {
                Map<Long, Number> out = new java.util.HashMap<>();
                m.forEach((k, v) -> {
                    if (v instanceof Number n) out.put(Long.valueOf(String.valueOf(k)), n);
                });
                return out;
            }
        } catch (Exception e) {
            LOGGER.warn("storefront availability lookup failed (rendering without stock caps): {}", e.getMessage());
        }
        return Map.of();
    }

    /**
     * Place a guest order (anonymous). COD → PENDING; CARD → sandbox charge (slice 48). On a business error (e.g.
     * out of stock) the marketplace returns a 4xx with a {@code {success:false,message:...}} body — pass that body
     * straight through so the shopper sees the real reason ("out of stock") instead of a generic retry message.
     */
    @PostMapping("/storefront/checkout")
    @ResponseBody
    public Object checkout(@RequestBody Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.postForObject(gatewayUrl + "/api/marketplace/public/order",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (HttpStatusCodeException e) {
            // The marketplace already shaped a meaningful {success,message} body — relay it to the shopper.
            try {
                Map<String, Object> err = objectMapper.readValue(e.getResponseBodyAsString(), Map.class);
                if (err.get("message") != null) {
                    return Map.of("success", false, "message", err.get("message"));
                }
            } catch (Exception ignore) {
                LOGGER.warn("could not parse storefront checkout error body", ignore);
            }
            LOGGER.error("storefront checkout proxy error ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Map.of("success", false, "message", "Could not place the order. Please try again.");
        } catch (Exception e) {
            LOGGER.error("storefront checkout proxy error", e);
            return Map.of("success", false, "message", "Could not place the order. Please try again.");
        }
    }
}
