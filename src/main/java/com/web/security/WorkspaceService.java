package com.web.security;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.security.TokenStore;

/**
 * RW-3 (2026-10-12) — this person's workspaces (auth-service {@code ui_workspace}, V27): where each of their jobs
 * lands. Asked ONCE, at sign-in (and when the tile home opens), and kept in the session — the top-bar switcher on
 * every page reads the session copy and never calls a service (performance: no inter-service call on a page view).
 * Anything that fails answers "no workspaces", and sign-in lands where it always did.
 */
@Component("workspaces")
public class WorkspaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceService.class);
    static final String SESSION_KEY = "rw3.workspaces";

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    /** Ask auth-service now and keep the answer in the session. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> load(HttpSession session) {
        List<Map<String, Object>> rows = List.of();
        try {
            if (tokenStore.hasAccessToken()) {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(tokenStore.getAccessToken());
                Map<String, Object> body = rest.exchange(gatewayUrl + "/api/auth/org/workspaces", HttpMethod.GET,
                        new HttpEntity<>(headers), Map.class).getBody();
                Object data = body == null ? null : body.get("data");
                if (data instanceof List<?> list) {
                    rows = list.stream().filter(r -> r instanceof Map<?, ?>).map(r -> (Map<String, Object>) r).toList();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("workspaces not loaded ({}) — sign-in lands where it always did", e.getMessage());
        }
        if (session != null) session.setAttribute(SESSION_KEY, rows);
        return rows;
    }

    /** The session copy, for the header switcher — never a service call. Empty when none. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> mine() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpSession session = attrs == null ? null : attrs.getRequest().getSession(false);
            Object v = session == null ? null : session.getAttribute(SESSION_KEY);
            return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
