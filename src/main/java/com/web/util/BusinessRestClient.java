package com.web.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.persistence.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Thin proxy client that forwards business-module requests to business-service.
 * Attaches X-User-Id header so business-service can identify the caller.
 */
@Component
public class BusinessRestClient {

    private static final Logger log = LoggerFactory.getLogger(BusinessRestClient.class);

    @Value("${business.service.url:http://localhost:8083}")
    private String baseUrl;

    @Autowired
    private RequestUtil requestUtil;

    private final RestTemplate restTemplate = new RestTemplate();

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String rolesHeader() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "";
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private User resolveUser() {
        // Try requestUtil first
        User user = requestUtil.getCurrentUser();
        if (user != null) return user;

        // Fallback: read directly from SecurityContextHolder
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return (User) auth.getPrincipal();
        }
        if (auth != null) {
            log.warn("BusinessRestClient: principal type is {} — X-User-Id will not be set",
                    auth.getPrincipal() == null ? "null" : auth.getPrincipal().getClass().getName());
        }
        return null;
    }

    private HttpHeaders headersWithUserId() {
        User user = resolveUser();
        HttpHeaders headers = new HttpHeaders();
        if (user != null) {
            headers.set("X-User-Id", String.valueOf(user.getId()));
            headers.set("X-User-Email", user.getEmail());
            headers.set("X-User-Roles", rolesHeader());
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders formHeadersWithUserId() {
        User user = resolveUser();
        HttpHeaders headers = new HttpHeaders();
        if (user != null) {
            headers.set("X-User-Id", String.valueOf(user.getId()));
            headers.set("X-User-Email", user.getEmail());
            headers.set("X-User-Roles", rolesHeader());
        }
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    /** GET — returns raw Map (GenericResponse body). */
    public Map<String, Object> get(String path) {
        HttpEntity<?> entity = new HttpEntity<>(headersWithUserId());
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
            baseUrl + path, HttpMethod.GET, entity,
            new ParameterizedTypeReference<Map<String, Object>>() {});
        return resp.getBody();
    }

    /** GET with query string appended. */
    public Map<String, Object> get(String path, String queryString) {
        String url = baseUrl + path + (queryString != null && !queryString.isEmpty() ? "?" + queryString : "");
        HttpEntity<?> entity = new HttpEntity<>(headersWithUserId());
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
            url, HttpMethod.GET, entity,
            new ParameterizedTypeReference<Map<String, Object>>() {});
        return resp.getBody();
    }

    /** GET — returns plain String (for HTML option-list endpoints). */
    public String getString(String path) {
        HttpEntity<?> entity = new HttpEntity<>(headersWithUserId());
        ResponseEntity<String> resp = restTemplate.exchange(
            baseUrl + path, HttpMethod.GET, entity, String.class);
        return resp.getBody();
    }

    /** GET — returns plain String with query string. */
    public String getString(String path, String queryString) {
        String url = baseUrl + path + (queryString != null && !queryString.isEmpty() ? "?" + queryString : "");
        HttpEntity<?> entity = new HttpEntity<>(headersWithUserId());
        ResponseEntity<String> resp = restTemplate.exchange(
            url, HttpMethod.GET, entity, String.class);
        return resp.getBody();
    }

    /** POST with form params (application/x-www-form-urlencoded). */
    public Map<String, Object> postForm(String path, Map<String, String> params) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        if (params != null) params.forEach(formData::add);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formData, formHeadersWithUserId());
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
            baseUrl + path, HttpMethod.POST, entity,
            new ParameterizedTypeReference<Map<String, Object>>() {});
        return resp.getBody();
    }

    /** POST with form params — returns boolean. */
    public Boolean postFormBoolean(String path, Map<String, String> params) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        if (params != null) params.forEach(formData::add);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formData, formHeadersWithUserId());
        ResponseEntity<Boolean> resp = restTemplate.exchange(
            baseUrl + path, HttpMethod.POST, entity, Boolean.class);
        return resp.getBody();
    }

    /** POST with JSON body. */
    public Map<String, Object> postJson(String path, Object body) {
        HttpEntity<Object> entity = new HttpEntity<>(body, headersWithUserId());
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
            baseUrl + path, HttpMethod.POST, entity,
            new ParameterizedTypeReference<Map<String, Object>>() {});
        return resp.getBody();
    }
}
