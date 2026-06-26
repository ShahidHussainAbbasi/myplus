package com.web.util;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Facade for inventory-service calls (slice 45). Reuses the shared {@link GatewayClient}; only adds the
 * {@code /api/inventory} gateway prefix (no StripPrefix — inventory controllers are at the full path).
 */
@Component
public class InventoryRestClient {

    private static final String PREFIX = "/api/inventory";

    @Value("${inventory.service.url:http://localhost:8082}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    public Map<String, Object> get(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** Raw GET returning a scalar (e.g. stock level) as text. */
    public String getString(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** POST a JSON body to inventory (org/user travel via the gateway-forwarded identity). */
    public Map<String, Object> postJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }

    /** POST a JSON body to an inventory endpoint that returns a raw scalar (e.g. /stock/import → count). */
    public String postJsonString(String path, Object body) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }
}
