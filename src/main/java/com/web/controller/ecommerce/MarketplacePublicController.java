package com.web.controller.ecommerce;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MKT-1c/1d/1e — the ANONYMOUS marketplace: the {@code /marketplace} page, its reads, and the one anonymous write
 * (checkout). {@code SecSecurityConfig} permits GET {@code /marketplace}, GET {@code /marketplace/public/**} and POST
 * {@code /marketplace/public/checkout} without a login; CSRF stays enforced on the POST.
 *
 * <p>Calls the gateway's allow-listed {@code /api/marketplace/public/**} with no token. {@link RestClient} (Spring
 * 6.1), the successor to {@code RestTemplate}, with connect and read TIMEOUTS (standard D3e): an anonymous page that
 * pins a request thread on a slow service is the cheapest way to take the monolith down. Query strings are built by
 * {@link UriComponentsBuilder} (each value encoded), never by concatenation.
 */
@Controller
public class MarketplacePublicController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketplacePublicController.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };

    private final String gatewayUrl;
    private final RestClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketplacePublicController(@Value("${gateway.url:http://localhost:8765}") String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(3));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(timeouts).build();
    }

    /** The public marketplace page (MKT-1d). State lives in the URL: ?q=&city=&product=&sort=. */
    @GetMapping("/marketplace")
    public String page() {
        return "marketplace";
    }

    /** Search cards: "Available from N sellers · From Rs. …" (MKT-1d). */
    @GetMapping("/marketplace/public/products")
    @ResponseBody
    public Object search(@RequestParam(required = false) String q, @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return relay(UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/products")
                .queryParamIfPresent("q", blank(q)).queryParamIfPresent("city", blank(city))
                .queryParamIfPresent("page", java.util.Optional.ofNullable(page))
                .queryParamIfPresent("size", java.util.Optional.ofNullable(size)),
                "Could not search the marketplace.");
    }

    /** A product page header with the default sort and the sorts on offer (MKT-1d). */
    @GetMapping("/marketplace/public/products/{id}")
    @ResponseBody
    public Object product(@PathVariable Long id) {
        return relay(UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/products/{id}")
                .uriVariables(Map.of("id", id)), "Could not load the product.");
    }

    /** A product's offers, ranked by the customer's sort and filtered by city and quantity (MKT-1c). */
    @GetMapping("/marketplace/public/products/{id}/offers")
    @ResponseBody
    public Object offers(@PathVariable Long id, @RequestParam(required = false) String city,
            @RequestParam(required = false) String sort, @RequestParam(required = false) BigDecimal qty) {
        return relay(UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/products/{id}/offers")
                .uriVariables(Map.of("id", id))
                .queryParamIfPresent("city", blank(city)).queryParamIfPresent("sort", blank(sort))
                .queryParamIfPresent("qty", java.util.Optional.ofNullable(qty).map(BigDecimal::toPlainString)),
                "Could not load offers.");
    }

    /**
     * MKT-1e checkout — the one anonymous WRITE. Permitted by method and path in {@code SecSecurityConfig}; CSRF is
     * NOT exempted (the page sends the token). The body is passed through: the service binds only the fields it
     * knows and prices everything itself.
     */
    @PostMapping("/marketplace/public/checkout")
    @ResponseBody
    public Object checkout(@RequestBody Map<String, Object> body) {
        URI uri = UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/checkout").build().toUri();
        try {
            Map<String, Object> r = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON);
            return r == null ? Map.of("success", false, "message", "Could not place the order.") : r;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("marketplace checkout -> {}", e.getStatusCode());
            return Map.of("success", false, "statusCode", e.getStatusCode().value(),
                    "message", messageOf(e, "Could not place the order. Please try again."));
        } catch (Exception e) {
            // a timeout may hide an order that WAS placed: the shopper retries with the same key and gets that order
            LOGGER.error("marketplace checkout failed", e);
            return Map.of("success", false, "message", "We could not confirm your order. Please try again; you will not be charged twice.");
        }
    }

    /** MKT-1e tracking: the order number AND the phone it was placed with. */
    @GetMapping("/marketplace/public/orders/{orderNo}")
    @ResponseBody
    public Object track(@PathVariable String orderNo, @RequestParam(required = false) String phone) {
        return relay(UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/orders/{no}")
                .uriVariables(Map.of("no", orderNo)).queryParamIfPresent("phone", blank(phone)), "Could not load the order.");
    }

    /**
     * One relay for every read: the service's own sentence reaches the shopper (a 404 "No such product." stays
     * that sentence); an outage reads as a polite retry, never a stack trace.
     */
    private Object relay(UriComponentsBuilder url, String fallback) {
        URI uri = url.encode().build().toUri();
        try {
            Map<String, Object> r = http.get().uri(uri).retrieve().body(JSON);
            return r == null ? Map.of("success", false, "message", fallback) : r;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("marketplace public read {} -> {}", uri.getPath(), e.getStatusCode());
            return Map.of("success", false, "statusCode", e.getStatusCode().value(), "message", messageOf(e, fallback));
        } catch (Exception e) {
            LOGGER.error("marketplace public read {} failed", uri.getPath(), e);
            return Map.of("success", false, "message", "The marketplace is not available right now. Please try again.");
        }
    }

    @SuppressWarnings("unchecked")
    private String messageOf(HttpStatusCodeException e, String fallback) {
        try {
            Object m = objectMapper.readValue(e.getResponseBodyAsString(), Map.class).get("message");
            return m == null ? fallback : String.valueOf(m);
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private static java.util.Optional<String> blank(String s) {
        return s == null || s.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(s.trim());
    }
}
