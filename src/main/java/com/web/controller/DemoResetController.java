package com.web.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import com.persistence.model.User;
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

    // userType -> the module service's demo purge endpoint (only modules with one are listed; the
    // endpoints are themselves guarded to DEMO_PRIVILEGE server-side, so this is purely a router).
    private static final Map<String, String> PURGE_PATHS = Map.of(
            "APPOINTMENT", "/api/appointment/demo/purge");

    @RequestMapping(value = "/demo/reset", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse reset() {
        GenericResponse response = new GenericResponse();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            // 1) clear the write counters at the gateway (restart the 50/module trial)
            rest.exchange(gatewayUrl + "/demo/reset", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
            // 2) delete the demo account's data for its module — demo principals only (and the endpoint
            //    is itself guarded to DEMO_PRIVILEGE, so a real tenant can never be purged by mistake).
            boolean purged = false;
            String purgePath = purgePathForDemoUser();
            if (purgePath != null) {
                try {
                    rest.exchange(gatewayUrl + purgePath, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);
                    purged = true;
                } catch (Exception pe) {
                    LOGGER.warn("demo purge skipped: {}", pe.getMessage());
                }
            }
            response.setMessage(purged
                    ? "Demo reset — your data was cleared and the 50-entry trial is fresh again."
                    : "Demo reset — your 50-entry trial is fresh again.");
        } catch (Exception e) {
            LOGGER.error("demo reset failed", e);
            response.setError("ResetFailed");
            response.setMessage("Could not reset the demo right now. Please try again.");
        }
        return response;
    }

    /** The purge endpoint for the logged-in user's module — only when the principal is a demo account. */
    private String purgePathForDemoUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof User u && u.isDemo()) {
            return PURGE_PATHS.get(u.getUserType());
        }
        return null;
    }
}
