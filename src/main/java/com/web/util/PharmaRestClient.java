package com.web.util;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Facade for pharma-service calls (slice 41). Transport/auth/refresh live in {@link GatewayClient}; this only adds
 * the {@code /api/pharma} gateway prefix + the legacy direct base URL. Mirrors {@link BusinessRestClient}.
 */
@Component
public class PharmaRestClient {

    private static final String PREFIX = "/api/pharma";

    @Value("${pharma.service.url:http://localhost:8087}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    private String withQuery(String path, String queryString) {
        return (queryString != null && !queryString.isEmpty()) ? path + "?" + queryString : path;
    }

    private MultiValueMap<String, String> form(Map<String, String> params) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        if (params != null) params.forEach(formData::add);
        return formData;
    }

    public Map<String, Object> get(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    public Map<String, Object> get(String path, String queryString) {
        return gateway.forMap(PREFIX, directBaseUrl, withQuery(path, queryString), HttpMethod.GET, null, null);
    }

    public Map<String, Object> postForm(String path, Map<String, String> params) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, form(params),
                MediaType.APPLICATION_FORM_URLENCODED);
    }

    public Map<String, Object> postJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }

    public Map<String, Object> putJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.PUT, body, MediaType.APPLICATION_JSON);
    }
}
