package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * F3: facade for finance-service calls (GL reports + journal posting). Like {@link BusinessRestClient} but for the
 * finance module. NOTE finance-service maps its controllers UNDER {@code /api/finance} (business maps at root), so
 * the direct base URL includes {@code /api/finance} — then server-mode ({@code gatewayUrl + /api/finance + path})
 * and direct-mode ({@code baseUrl + path}) both resolve to {@code …/api/finance/gl/…}. Returns raw JSON strings
 * because GL reads return JSON arrays as well as objects.
 */
@Component
public class FinanceRestClient {

    private static final String PREFIX = "/api/finance";

    @Value("${finance.service.url:http://localhost:8094/api/finance}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    /** GET returning the raw JSON string. */
    public String get(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** GET with query string, raw JSON string. */
    public String get(String path, String queryString) {
        String p = (queryString != null && !queryString.isEmpty()) ? path + "?" + queryString : path;
        return gateway.forString(PREFIX, directBaseUrl, p, HttpMethod.GET, null, null);
    }

    /** POST a JSON body, returning the raw JSON string. */
    public String postJson(String path, Object body) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }
}
