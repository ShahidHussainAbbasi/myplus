package com.web.security;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * RW-0b/RW-0c — one loader for the permission catalogues auth-service keeps as data (menus, endpoint rules): a list
 * of rows read with the signed-in person's own token, kept for a few minutes, the same for every business.
 *
 * <p>If auth-service cannot be reached the LAST copy is kept and retried after 30 s; if there never was one,
 * {@link #rows} answers null and each caller decides what "not known yet" means (a menu is shown; a write is held —
 * RW-0c option (a)).
 */
@Component
public class AuthCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthCatalog.class);
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final long RETRY_MS = 30 * 1000L;

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    private record Copy(List<Map<String, Object>> rows, long loadedAt) {}

    private final Map<String, Copy> copies = new ConcurrentHashMap<>();
    private final Map<String, Long> nextTry = new ConcurrentHashMap<>();

    /** The rows at {@code path} (e.g. /api/auth/org/menus), or null when never loaded. */
    public List<Map<String, Object>> rows(String path) {
        Copy c = copies.get(path);
        long now = System.currentTimeMillis();
        if ((c == null || now - c.loadedAt() > REFRESH_MS) && now >= nextTry.getOrDefault(path, 0L)) load(path);
        c = copies.get(path);
        return c == null ? null : c.rows();
    }

    @SuppressWarnings("unchecked")
    private synchronized void load(String path) {
        Copy c = copies.get(path);
        long now = System.currentTimeMillis();
        if (c != null && now - c.loadedAt() <= REFRESH_MS) return;          // another thread just did it
        try {
            if (!tokenStore.hasAccessToken()) return;                        // nobody signed in on this request
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            Map<String, Object> body = rest.exchange(gatewayUrl + path, HttpMethod.GET, new HttpEntity<>(headers),
                    Map.class).getBody();
            Object data = body == null ? null : body.get("data");
            if (data instanceof List<?> list) {
                List<Map<String, Object>> rows = list.stream().filter(r -> r instanceof Map<?, ?>)
                        .map(r -> (Map<String, Object>) r).toList();
                copies.put(path, new Copy(rows, now));
            }
        } catch (Exception e) {
            LOGGER.warn("auth catalogue {} not loaded ({}); keeping {} rows", path, e.getMessage(),
                    c == null ? 0 : c.rows().size());
            nextTry.put(path, now + RETRY_MS);
        }
    }
}
