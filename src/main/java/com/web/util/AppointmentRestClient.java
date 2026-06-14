package com.web.util;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Facade for appointment-service calls. Authenticated (org-user) calls go through {@link GatewayClient}
 * (Bearer/headers, /api/appointment prefix). The anonymous public booking goes straight to the gateway's
 * open route with a plain RestTemplate (no auth) — mirroring how DemoController posts the public lead.
 */
@Component
public class AppointmentRestClient {

    private static final String PREFIX = "/api/appointment";

    @Value("${appointment.service.url:http://localhost:8091}")
    private String directBaseUrl;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    @Autowired
    private GatewayClient gateway;

    private final RestTemplate publicRest = new RestTemplate();

    /** Authenticated GET returning the raw JSON body. */
    public ResponseEntity<String> get(String path) {
        return gateway.forStringEntity(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** Authenticated GET returning the parsed ApiResponse map ({success,message,data,...}). */
    public Map<String, Object> getMap(String path) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }

    /** Authenticated JSON POST returning the parsed ApiResponse map. */
    public Map<String, Object> postJson(String path, Object body) {
        return gateway.forMap(PREFIX, directBaseUrl, path, HttpMethod.POST, body, MediaType.APPLICATION_JSON);
    }

    /** Anonymous public POST (e.g. patient booking) straight to the gateway open route; parsed ApiResponse. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> postPublic(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = publicRest.exchange(gatewayUrl + PREFIX + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        return resp.getBody();
    }
}
