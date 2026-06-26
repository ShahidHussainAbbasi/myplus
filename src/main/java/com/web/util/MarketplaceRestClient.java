package com.web.util;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Facade for marketplace-service (e-commerce, slice 46). Reuses {@link GatewayClient}; adds the
 * {@code /api/marketplace} gateway prefix. Mirrors {@link PharmaRestClient}.
 */
@Component
public class MarketplaceRestClient {

    private static final String PREFIX = "/api/marketplace";

    @Value("${marketplace.service.url:http://localhost:8088}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    public Map<String, Object> get(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    public Map<String, Object> postJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }

    public Map<String, Object> putJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.PUT, body, MediaType.APPLICATION_JSON);
    }
}
