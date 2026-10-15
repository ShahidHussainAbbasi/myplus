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

    /** EX-5 — a binary answer (a receipt), untouched. */
    public ResponseEntity<byte[]> getBytes(String pathAndQuery) {
        return gateway.forBytesEntity(PREFIX, directBaseUrl, pathAndQuery);
    }

    /** EX-5 — a receipt upload, forwarded as multipart (the file's bytes and its name). */
    public ResponseEntity<String> postFile(String pathAndQuery, byte[] bytes, String filename) {
        org.springframework.core.io.ByteArrayResource file = new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename == null || filename.isBlank() ? "receipt" : filename; }
        };
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", file);
        return gateway.forStringEntity(PREFIX, directBaseUrl, pathAndQuery, HttpMethod.POST, body, MediaType.MULTIPART_FORM_DATA);
    }
}
