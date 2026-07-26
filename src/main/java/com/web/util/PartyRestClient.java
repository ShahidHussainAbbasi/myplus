package com.web.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Facade for party-service calls (the shared contact master). Like {@link FinanceRestClient} but for party — its
 * controllers map under {@code /api/party}, so the direct base URL includes that prefix and both server-mode
 * (gateway + /api/party + path) and direct-mode (baseUrl + path) resolve to {@code …/api/party/parties/…}. Returns
 * raw JSON strings (the contact view carries a nested object + a roles array). Used only for the owner Contact-360
 * read today; party writes go module→party-service directly, not through the monolith.
 */
@Component
public class PartyRestClient {

    private static final String PREFIX = "/api/party";

    @Value("${party.service.url:http://localhost:8096/api/party}")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    /** GET returning the raw JSON string. */
    public String get(String path) {
        return gateway.forString(PREFIX, directBaseUrl, path, HttpMethod.GET, null, null);
    }
}
