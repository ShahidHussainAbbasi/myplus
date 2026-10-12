package com.web.security;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.security.TokenStore;

/**
 * RW-0b — the menu catalogue from auth-service ({@code /api/auth/org/menus}, table {@code ui_menu}, V25): which
 * permission codes unlock each menu key. DATA, not code: a new menu or a changed rule is a row.
 *
 * <p>The rows are the same for every business, so ONE copy is kept and refreshed every few minutes, read with the
 * signed-in person's own token (any member may read it). If auth-service cannot be reached, the last copy is kept;
 * if there never was one, {@link #codesFor} answers "unknown" and the menu is shown — a menu is a convenience, the
 * server's refusal is the control, and an outage must not blank every screen.
 */
@Component
public class MenuCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(MenuCatalog.class);
    private static final long REFRESH_MS = 5 * 60 * 1000L;

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    private volatile Map<String, List<String>> codesByKey = null;
    private volatile long loadedAt = 0L;

    /** The codes that unlock {@code key} (empty = everyone signed in), or null when the key or catalogue is unknown. */
    public List<String> codesFor(String key) {
        Map<String, List<String>> now = current();
        return now == null ? null : now.get(key);
    }

    private Map<String, List<String>> current() {
        if (codesByKey == null || System.currentTimeMillis() - loadedAt > REFRESH_MS) refresh();
        return codesByKey;
    }

    @SuppressWarnings("unchecked")
    private synchronized void refresh() {
        if (codesByKey != null && System.currentTimeMillis() - loadedAt <= REFRESH_MS) return;   // another thread did it
        try {
            if (!tokenStore.hasAccessToken()) return;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            Map<String, Object> body = rest.exchange(gatewayUrl + "/api/auth/org/menus", HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
            Object data = body == null ? null : body.get("data");
            if (!(data instanceof List<?> rows)) return;
            Map<String, List<String>> next = new HashMap<>();
            for (Object r : rows) {
                if (!(r instanceof Map<?, ?> row) || row.get("key") == null) continue;
                Object codes = row.get("codes");
                next.put(String.valueOf(row.get("key")),
                        codes instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
            }
            codesByKey = Collections.unmodifiableMap(next);
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            // keep the last copy; the next page view tries again
            LOGGER.warn("menu catalogue not refreshed ({}); keeping {} rows", e.getMessage(),
                    codesByKey == null ? 0 : codesByKey.size());
            loadedAt = System.currentTimeMillis() - REFRESH_MS + 30_000L;   // retry in 30 s, not on every menu
        }
    }
}
