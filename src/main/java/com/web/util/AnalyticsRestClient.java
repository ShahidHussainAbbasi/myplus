package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * AN-1: facade for analytics-service reads. analytics maps its controllers under {@code /api/analytics} (the gateway
 * route keeps the prefix), so server mode ({@code gatewayUrl + /api/analytics + path}) and direct mode
 * ({@code baseUrl + path}) both resolve to {@code …/api/analytics/…}. Raw JSON strings, as FinanceRestClient.
 */
@Component
public class AnalyticsRestClient {

    private static final String PREFIX = "/api/analytics";

    @Value("${analytics.service.url:http://localhost:8090/api/analytics}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    /** GET with query string, raw JSON string. */
    public String get(String path, String queryString) {
        String p = (queryString != null && !queryString.isEmpty()) ? path + "?" + queryString : path;
        return gateway.forString(PREFIX, directBaseUrl, p, HttpMethod.GET, null, null);
    }
}
