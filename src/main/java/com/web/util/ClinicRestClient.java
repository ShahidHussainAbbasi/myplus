package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * HMS S1 — the monolith's door to clinical-service ({@code /api/clinic}), through {@link GatewayClient}, which owns
 * the token, the 401 refresh, the URI encoding and the downstream-error logging (STANDARDS D3d).
 *
 * <p>Writes go as JSON bodies, never form parameters: patient names are often Urdu, and the form proxy path is
 * where non-ASCII text was double-encoded.
 */
@Component
public class ClinicRestClient {

    private static final String PREFIX = "/api/clinic";

    @Value("${clinic.service.url:http://localhost:8098/api/clinic}")
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
