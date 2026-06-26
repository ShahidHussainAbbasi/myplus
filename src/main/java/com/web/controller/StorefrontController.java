package com.web.controller;

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
import org.springframework.web.client.RestTemplate;

/**
 * Public e-commerce storefront (slice 47). No auth (the {@code /store} page + {@code /storefront/**} proxies are
 * permitted in {@code SecSecurityConfig}). Reuses the {@link DemoController} pattern: proxies to the gateway's OPEN
 * storefront routes (anonymous — gateway allow-lists {@code /api/catalog/public/} + {@code /api/marketplace/public/}).
 */
@Controller
public class StorefrontController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorefrontController.class);

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /** The public storefront page. {@code ?org=} selects the store; defaults to the demo store. */
    @GetMapping("/store")
    public String store() {
        return "store";
    }

    /** Browse a store's active products (anonymous). */
    @GetMapping("/storefront/products")
    @ResponseBody
    public Object products(@RequestParam(value = "org", required = false, defaultValue = "0") Long org) {
        try {
            return restTemplate.getForObject(gatewayUrl + "/api/catalog/public/products?org=" + org, Map.class);
        } catch (Exception e) {
            LOGGER.error("storefront products proxy error", e);
            return Map.of("success", false, "message", "Could not load products.");
        }
    }

    /** Place a guest order (anonymous). COD → PENDING; CARD → sandbox charge (slice 48). */
    @PostMapping("/storefront/checkout")
    @ResponseBody
    public Object checkout(@RequestBody Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return restTemplate.postForObject(gatewayUrl + "/api/marketplace/public/order",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            LOGGER.error("storefront checkout proxy error", e);
            return Map.of("success", false, "message", "Could not place the order. Please try again.");
        }
    }
}
