package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Facade for catalog-service calls (slice 33, U4.3 pre-stage). Mirrors {@link BusinessRestClient}: transport,
 * auth and token refresh live in {@link GatewayClient}; this only contributes the {@code /api/catalog} gateway
 * prefix and the legacy direct base URL. Used by the catalog-backed item picker.
 */
@Component
public class CatalogRestClient {

    private static final String PREFIX = "/api/catalog";

    @Value("${catalog.service.url:http://localhost:8092}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    private String withQuery(String path, String queryString) {
        return (queryString != null && !queryString.isEmpty()) ? path + "?" + queryString : path;
    }

    /** GET — returns raw Map (ApiResponse body). */
    public Map<String, Object> get(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** GET with query string appended. */
    public Map<String, Object> get(String path, String queryString) {
        return gateway.forMap(PREFIX, directBaseUrl, withQuery(path, queryString), HttpMethod.GET, null, null);
    }

    /** POST JSON (slice 42, M1): create a catalog Product (the single product master). */
    public Map<String, Object> postJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }

    /** PUT JSON (slice 42, M1): update a catalog Product. */
    public Map<String, Object> putJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.PUT, body, MediaType.APPLICATION_JSON);
    }

    /** GET returning the raw JSON string — for endpoints that return a JSON ARRAY (e.g. tax-codes list). */
    public String getString(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** DELETE (multi-rate tax: remove a tax code). Empty body. */
    /**
     * POST with a JSON body, returning the raw response text (slice I2).
     *
     * <p>For endpoints that answer with a FILE rather than an envelope — the import report download posts the
     * operator's CSV and gets CSV back, which {@link #postJson} would try to parse as a map and fail on.
     */
    public String postJsonString(String path, Object body) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }

    public String delete(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.DELETE, null, null);
    }
}
