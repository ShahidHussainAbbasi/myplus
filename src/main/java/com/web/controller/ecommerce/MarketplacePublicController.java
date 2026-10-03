package com.web.controller.ecommerce;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * MKT-1c — the ANONYMOUS marketplace reads. Only GETs live under {@code /marketplace/public/**}, which
 * {@code SecSecurityConfig} permits without a login; every write stays behind authentication.
 *
 * <p>Calls the gateway's allow-listed {@code /api/marketplace/public/**} with no token, the storefront recipe — but
 * with connect and read TIMEOUTS (standard D3e): {@code new RestTemplate()} has none, and an anonymous page that
 * pins a request thread on a slow service is the cheapest way to take the monolith down.
 */
@Controller
public class MarketplacePublicController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketplacePublicController.class);

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate restTemplate;

    public MarketplacePublicController() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(5_000);
        this.restTemplate = new RestTemplate(f);
    }

    /** A product's offers, ranked by the customer's sort and filtered by city and quantity (MKT-1c). */
    @GetMapping("/marketplace/public/products/{id}/offers")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public Object offers(@PathVariable Long id, @RequestParam(required = false) String city,
            @RequestParam(required = false) String sort, @RequestParam(required = false) BigDecimal qty) {
        StringBuilder url = new StringBuilder(gatewayUrl).append("/api/marketplace/public/mkt/products/").append(id)
                .append("/offers?x=1");
        if (city != null && !city.isBlank()) url.append("&city=").append(URLEncoder.encode(city.trim(), StandardCharsets.UTF_8));
        if (sort != null && !sort.isBlank()) url.append("&sort=").append(URLEncoder.encode(sort.trim(), StandardCharsets.UTF_8));
        if (qty != null) url.append("&qty=").append(qty.toPlainString());
        try {
            Map<String, Object> r = restTemplate.getForObject(java.net.URI.create(url.toString()), Map.class);
            return r == null ? Map.of("success", false, "message", "Could not load offers.") : r;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("marketplace public offers -> {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Map.of("success", false, "message", "Could not load offers. Please try again.");
        } catch (Exception e) {
            LOGGER.error("marketplace public offers proxy error", e);
            return Map.of("success", false, "message", "Offers are not available right now. Please try again.");
        }
    }
}
