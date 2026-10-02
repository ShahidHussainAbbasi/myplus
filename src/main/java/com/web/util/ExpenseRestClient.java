package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * EX-1 — the monolith's door to expense-service ({@code /api/expense}), through {@link GatewayClient}, which owns
 * the token, the 401 refresh, the URI encoding and the downstream-error logging (STANDARDS D3d).
 *
 * <p>Writes go as JSON bodies, never form parameters: the form proxy path is where repeated parameters were
 * collapsed (purchase proxy) and non-ASCII text double-encoded (Urdu notes) — neither can happen to a body.
 */
@Component
public class ExpenseRestClient {

    private static final String PREFIX = "/api/expense";

    @Value("${expense.service.url:http://localhost:8097/api/expense}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    public ResponseEntity<String> get(String pathAndQuery) {
        return gateway.forStringEntity(PREFIX, directBaseUrl, pathAndQuery, HttpMethod.GET, null, null);
    }

    public ResponseEntity<String> send(HttpMethod method, String pathAndQuery, Object body) {
        return gateway.forStringEntity(PREFIX, directBaseUrl, pathAndQuery, method,
                body == null ? java.util.Map.of() : body, MediaType.APPLICATION_JSON);
    }
}
