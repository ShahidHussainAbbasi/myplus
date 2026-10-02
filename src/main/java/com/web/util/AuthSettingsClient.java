package com.web.util;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * The settings auth-service owns: the tenant's capabilities ({@code org.cap.*}) and its shape ({@code org.shape}).
 *
 * <p>EX-2a — extracted from {@code BusinessConfigController} so the business Configuration screen and the
 * Modules card on every other dashboard write capabilities through ONE path. Two copies would drift, and the
 * first thing to drift would be the token re-mint below — the step that makes a switch take effect.
 *
 * <h3>Why a save re-mints the session token</h3>
 * Services read capabilities from the JWT claim. Without a re-mint the owner switches a module on and every
 * service keeps refusing it until the token's next scheduled refresh.
 */
@Component
public class AuthSettingsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthSettingsClient.class);
    private static final String AUTH_PREFIX = "/api/auth";

    @Autowired
    private GatewayClient gateway;

    @Value("${auth.server.url:http://localhost:8765}")
    private String authDirectUrl;

    /** True for a key auth-service owns rather than the dashboard's own service. */
    public static boolean ownedByAuth(String key) {
        return key != null && (key.startsWith("org.cap.") || "org.shape".equals(key));
    }

    public Map<String, Object> get(String path) {
        return gateway.forMap(AUTH_PREFIX, authDirectUrl, path, HttpMethod.GET, null, null);
    }

    public Map<String, Object> post(String path) {
        return gateway.forMap(AUTH_PREFIX, authDirectUrl, path, HttpMethod.POST, Map.of(), MediaType.APPLICATION_JSON);
    }

    /** The tenant's auth-owned catalog (capabilities + shape), each entry with its value and catalog default. */
    public Map<String, Object> catalog() {
        return get("/settings");
    }

    /** Save one auth-owned setting, then re-mint the session token so the change applies now. */
    public Map<String, Object> save(String key, String value) {
        Map<String, Object> saved = post("/settings?key=" + enc(key) + (value != null ? "&value=" + enc(value) : ""));
        remint("Setting saved");
        return saved;
    }

    /** Remove the override (back to the catalog default), then re-mint. */
    public Map<String, Object> reset(String key) {
        Map<String, Object> done = post("/settings/reset?key=" + enc(key));
        remint("Setting reset");
        return done;
    }

    public void remint(String what) {
        try {
            gateway.refreshNow();
        } catch (Exception refreshFailed) {
            LOGGER.warn("{} but the session token could not be re-minted; it applies on the next refresh.", what, refreshFailed);
        }
    }

    /** Percent-encoded, because GatewayClient treats the query as already encoded (no double encoding). */
    public static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
