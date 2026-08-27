package com.web.controller.business;

import com.web.util.ProxyErrors;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.security.TokenStore;

/**
 * Multi-location Store management proxy. The dashboard "Stores" screen + the Manage Users store picker
 * call these; they forward to business-service (store registry) and auth-service (store access grants)
 * with the logged-in owner's Bearer token. On create we ALSO self-grant the owner to the new store, so a
 * single-store business is zero-touch (the store becomes the owner's active store on their next token issue).
 */
@Controller
public class StoreController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    @RequestMapping(value = "/getStores", method = RequestMethod.GET)
    @ResponseBody
    public Object getStores() {
        try {
            return rest.exchange(gatewayUrl + "/api/business/getStores", HttpMethod.GET,
                    new HttpEntity<>(bearer()), Object.class).getBody();
        } catch (Exception e) {
            LOGGER.error("getStores proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/addStore", method = RequestMethod.POST)
    @ResponseBody
    public Object addStore(@RequestBody Map<String, Object> body) {
        try {
            Object result = post("/api/business/addStore", body);
            // Zero-touch: grant the creating owner access to the new store so it becomes their active store.
            Long newId = storeIdOf(result);
            if (newId != null) {
                try { post("/api/auth/org/locations/grant", Map.of("storeIds", List.of(newId))); }
                catch (Exception grantErr) { LOGGER.warn("owner self-grant for store {} failed: {}", newId, grantErr.getMessage()); }
            }
            return result;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("addStore {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ProxyErrors.statusError(e);
        } catch (Exception e) {
            LOGGER.error("addStore proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/updateStore", method = RequestMethod.POST)
    @ResponseBody
    public Object updateStore(@RequestBody Map<String, Object> body) {
        try { return post("/api/business/updateStore", body); }
        catch (Exception e) { LOGGER.error("updateStore proxy error", e); return ProxyErrors.statusError(e); }
    }

    /** P5b — the stores the logged-in user may work at (their grants; an owner gets all). Feeds the switcher. */
    @RequestMapping(value = "/getMyStores", method = RequestMethod.GET)
    @ResponseBody
    public Object getMyStores() {
        try {
            return rest.exchange(gatewayUrl + "/api/business/getMyStores", HttpMethod.GET,
                    new HttpEntity<>(bearer()), Object.class).getBody();
        } catch (Exception e) {
            LOGGER.error("getMyStores proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /**
     * P5b — switch the ACTIVE store. auth-service re-issues the JWT with the chosen activeLocationId, so the
     * session token MUST be swapped here (same as switching organization) — otherwise the old token keeps
     * stamping writes with the old store.
     *
     * <p>Vertical-agnostic despite living here: auth resolves the location module from the caller's own userType,
     * so education's branch switcher posts a school id to this same endpoint rather than cloning it (P4).
     */
    @RequestMapping(value = "/switchStore", method = RequestMethod.POST)
    @ResponseBody
    public Object switchStore(@RequestBody Map<String, Object> body) {
        try {
            Object result = post("/api/auth/org/locations/switch", body);
            Object data = (result instanceof Map<?, ?> m) ? m.get("data") : null;
            if (data instanceof Map<?, ?> d && d.get("accessToken") != null) {
                tokenStore.setAccessToken(String.valueOf(d.get("accessToken")));
                if (d.get("refreshToken") != null) tokenStore.setRefreshToken(String.valueOf(d.get("refreshToken")));
                return Collections.singletonMap("status", "SUCCESS");
            }
            LOGGER.warn("switchStore: no token in response {}", result);
            return Collections.singletonMap("status", "FAILED");
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("switchStore {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ProxyErrors.statusError(e);
        } catch (Exception e) {
            LOGGER.error("switchStore proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** Assign store access to a user (owner/admin). Body: {userId?, storeIds:[...], roleAtLocation?}. */
    @RequestMapping(value = "/assignStores", method = RequestMethod.POST)
    @ResponseBody
    public Object assignStores(@RequestBody Map<String, Object> body) {
        try { return post("/api/auth/org/locations/grant", body); }
        catch (Exception e) { LOGGER.error("assignStores proxy error", e); return ProxyErrors.statusError(e); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private HttpHeaders bearer() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tokenStore.getAccessToken());
        return h;
    }

    private Object post(String path, Map<String, Object> body) {
        HttpHeaders h = bearer();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(gatewayUrl + path, HttpMethod.POST, new HttpEntity<>(body, h), Object.class).getBody();
    }

    /** Pull the new store id out of business-service's GenericResponse {status,message,object:{id,...}}. */
    @SuppressWarnings("unchecked")
    private Long storeIdOf(Object result) {
        if (!(result instanceof Map<?, ?> m)) return null;
        Object obj = m.get("object");
        if (obj instanceof Map<?, ?> o && o.get("id") != null) {
            try { return Long.valueOf(String.valueOf(o.get("id"))); } catch (NumberFormatException ignored) { }
        }
        return null;
    }
}
