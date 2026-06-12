package com.web.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import com.security.TokenStore;
import com.web.util.GenericResponse;

/**
 * "Reset demo" — clears the logged-in demo account's write counters at the gateway so the 50/module
 * trial can restart on demand (the cap otherwise auto-resets daily). Proxies to the gateway's
 * {@code /demo/reset} with the session Bearer token.
 */
@Controller
public class DemoResetController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    @RequestMapping(value = "/demo/reset", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse reset() {
        GenericResponse response = new GenericResponse();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            rest.exchange(gatewayUrl + "/demo/reset", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
            response.setMessage("Demo reset — your 50-entry trial is fresh again.");
        } catch (Exception e) {
            LOGGER.error("demo reset failed", e);
            response.setError("ResetFailed");
            response.setMessage("Could not reset the demo right now. Please try again.");
        }
        return response;
    }
}
