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
 * Facade for welfare-module calls. Transport/auth/refresh live in {@link GatewayClient}; this class
 * supplies the {@code /api/welfare} gateway prefix and the legacy direct base URL.
 */
@Component
public class WelfareRestClient {

    private static final String PREFIX = "/api/welfare";

    @Value("${welfare.service.url:http://localhost:8085}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    private MultiValueMap<String, String> form(Map<String, String> params) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        if (params != null) {
            params.forEach(formData::add);
        }
        return formData;
    }

    public Map<String, Object> get(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    public String getString(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    public Map<String, Object> postForm(String path, Map<String, String> params) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, form(params),
                MediaType.APPLICATION_FORM_URLENCODED);
    }

    public Boolean postFormBoolean(String path, Map<String, String> params) {
        return gateway.forBoolean(PREFIX, directBaseUrl, path, HttpMethod.POST, form(params),
                MediaType.APPLICATION_FORM_URLENCODED);
    }
}
