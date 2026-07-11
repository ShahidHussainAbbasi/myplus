package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Audit #6: facade for the standalone audit-service (append-only trail reads). Like {@link FinanceRestClient} — its
 * controllers map UNDER {@code /api/audit}, so the direct base URL includes {@code /api/audit} and server-mode
 * ({@code gatewayUrl + /api/audit + path}) + direct-mode both resolve correctly. Returns raw JSON (arrays).
 */
@Component
public class AuditRestClient {

    private static final String PREFIX = "/api/audit";

    @Value("${audit.service.url:http://localhost:8095/api/audit}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    /** GET with an optional query string, raw JSON string. */
    public String get(String path, String queryString) {
        String p = (queryString != null && !queryString.isEmpty()) ? path + "?" + queryString : path;
        return gateway.forString(PREFIX, directBaseUrl, p, HttpMethod.GET, null, null);
    }
}
