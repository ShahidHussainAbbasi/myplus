package com.web.util;

import java.io.IOException;
import java.util.Enumeration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Facade for education-module calls. Transport/auth/refresh live in {@link GatewayClient}; this
 * class contributes the {@code /api/education} gateway prefix, the legacy direct base URL, and the
 * existing method shapes.
 *
 * <p>The {@code userId} parameters are retained for source compatibility with the controllers but
 * are no longer used to propagate identity — that now travels in the JWT (server mode) or the
 * X-User-* headers GatewayClient adds from the security context (legacy mode).
 */
@Component
public class EducationRestClient {

    private static final String PREFIX = "/api/education";

    @Value("${education.service.url:http://localhost:8084}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    public ResponseEntity<String> get(String path, Long userId) {
        return gateway.forStringEntity(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    public ResponseEntity<String> post(String path, HttpServletRequest request, Long userId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            for (String value : request.getParameterValues(name)) {
                params.add(name, value);
            }
        }
        return gateway.forStringEntity(PREFIX, directBaseUrl, path, HttpMethod.POST, params,
                MediaType.APPLICATION_FORM_URLENCODED);
    }

    /** Forward a raw JSON body (e.g. bulk attendance) to the gateway as application/json. */
    public ResponseEntity<String> postJson(String path, String jsonBody) {
        return gateway.forStringEntity(PREFIX, directBaseUrl, path, HttpMethod.POST, jsonBody,
                MediaType.APPLICATION_JSON);
    }

    public ResponseEntity<String> postMultipart(String path, MultipartFile file, HttpServletRequest request, Long userId)
            throws IOException {
        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        return gateway.forStringEntity(PREFIX, directBaseUrl, path, HttpMethod.POST, body,
                MediaType.MULTIPART_FORM_DATA);
    }
}
