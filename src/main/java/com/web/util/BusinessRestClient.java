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
 * Facade for business-module calls. All transport, authentication (Bearer/JWT or legacy X-User-*
 * headers) and token refresh live in {@link GatewayClient}; this class only contributes the
 * {@code /api/business} gateway prefix, the legacy direct base URL, and the method shapes the
 * controllers already use.
 */
@Component
public class BusinessRestClient {

    private static final String PREFIX = "/api/business";

    @Value("${business.service.url:http://localhost:8083}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    private String withQuery(String path, String queryString) {
        return (queryString != null && !queryString.isEmpty()) ? path + "?" + queryString : path;
    }

    private MultiValueMap<String, String> form(Map<String, String> params) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        if (params != null) {
            params.forEach(formData::add);
        }
        return formData;
    }

    /** GET — returns raw Map (GenericResponse body). */
    public Map<String, Object> get(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** GET with query string appended. */
    public Map<String, Object> get(String path, String queryString) {
        return gateway.forMap(PREFIX, directBaseUrl, withQuery(path, queryString), HttpMethod.GET, null, null);
    }

    /** GET — returns plain String (for HTML option-list endpoints). */
    public String getString(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** GET — returns plain String with query string. */
    public String getString(String path, String queryString) {
        return gateway.forString(PREFIX, directBaseUrl, withQuery(path, queryString), HttpMethod.GET, null, null);
    }

    /** POST with form params (application/x-www-form-urlencoded). */
    public Map<String, Object> postForm(String path, Map<String, String> params) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, form(params),
                MediaType.APPLICATION_FORM_URLENCODED);
    }

    /** POST with form params — returns boolean. */
    public Boolean postFormBoolean(String path, Map<String, String> params) {
        return gateway.forBoolean(PREFIX, directBaseUrl, path, HttpMethod.POST, form(params),
                MediaType.APPLICATION_FORM_URLENCODED);
    }

    /** POST with JSON body. */
    public Map<String, Object> postJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }
}
