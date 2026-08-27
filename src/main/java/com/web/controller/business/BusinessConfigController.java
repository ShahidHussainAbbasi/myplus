package com.web.controller.business;

import com.web.util.ProxyErrors;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

/**
 * Owner Configuration screen proxy → business-service's shared common-settings endpoint ({@code /settings}).
 * The catalog + per-org values (GET) and one override upsert (POST) both live at {@code /api/business/settings}
 * — the shared controller keys on the HTTP verb, so the monolith exposes distinct paths that map to each.
 */
@Controller
public class BusinessConfigController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    /**
     * The settings catalog with each entry's effective value for this org (self-rendering screen reads it).
     *
     * <p>C3c: MERGED from two owners. Ordinary trade settings live in business-service; capabilities and the
     * tenant shape ({@code org.cap.*}, {@code org.shape}) live in auth-service, because a capability is a
     * property of the TENANT and auth already owns the tenant. The owner still sees one Configuration screen —
     * the split is an implementation detail they have no reason to care about.
     *
     * <p>If auth's half fails the business half is still returned. Losing the capability switches is a
     * degraded screen; losing the whole screen because one of two services is slow would be worse.
     */
    @RequestMapping(value = "/getBusinessConfig", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getBusinessConfig(final HttpServletRequest request) {
        try {
            Map<String, Object> merged = client.get("/settings");
            try {
                Map<String, Object> caps = authGet("/settings");
                mergeCatalogInto(merged, caps);
            } catch (Exception capsFailed) {
                // Deliberately swallowed to a warning — see the javadoc. The trade settings still render.
                LOGGER.warn("capability settings unavailable from auth-service; "
                        + "the Configuration screen will render without them", capsFailed);
            }
            return merged;
        } catch (Exception e) {
            LOGGER.error("getBusinessConfig proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /**
     * Append auth-service's catalog entries onto business-service's list, in place.
     *
     * <p>Both services answer with the shared {@code ApiResponse} envelope, whose payload is a list of catalog
     * entries under {@code data}. Anything unexpected is skipped rather than thrown: a malformed second half
     * must not cost the owner the first half.
     */
    @SuppressWarnings("unchecked")
    private void mergeCatalogInto(Map<String, Object> target, Map<String, Object> extra) {
        if (target == null || extra == null) return;
        Object into = target.get("data");
        Object from = extra.get("data");
        if (into instanceof java.util.List && from instanceof java.util.List) {
            ((java.util.List<Object>) into).addAll((java.util.List<Object>) from);
        }
    }

    /**
     * C3 — the capability map for this tenant, driving what the dashboard renders.
     *
     * <p>Read-only and deliberately parameterless: the tenant comes from the caller's JWT on the way through,
     * so there is no org id a caller could substitute. Same reason the upstream endpoint takes none.
     *
     * <p>This answers what a tenant MAY do; it is not what stops them doing it. The refusal lives on the write
     * paths in the services (`CapabilityService.assertEnabled`). If this call fails the shell shows everything,
     * which is the deliberate fail-OPEN choice for visibility — a settings hiccup must not take away a screen
     * a shop was using, and it cannot grant anything the server will not also allow.
     */
    @RequestMapping(value = "/getCapabilities", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getCapabilities(final HttpServletRequest request) {
        try {
            // C3c: asked of the OWNER. Any service would answer — they all resolve from the same JWT claim —
            // but asking the owner means the screen shows what was actually stored rather than a copy that
            // could be a token refresh behind.
            return authGet("/capabilities");
        } catch (Exception e) {
            LOGGER.error("getCapabilities proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Upsert one override (key + value). Owner/admin — the server @PreAuthorize is the real gate. */
    @RequestMapping(value = "/saveBusinessConfig", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveBusinessConfig(final HttpServletRequest request) {
        try {
            String key = request.getParameter("key");
            String value = request.getParameter("value");
            // The shared endpoint reads key/value as query params (@RequestParam), so pass them on the URL.
            String qs = "key=" + enc(key) + (value != null ? "&value=" + enc(value) : "");
            /*
             * C3c — the write goes to whoever OWNS the key.
             *
             * Capabilities and the tenant shape belong to auth-service; everything else to business-service.
             * Routing on the key rather than on a separate endpoint keeps the Configuration screen unchanged:
             * it posts every switch the same way and does not need to know who stores what.
             *
             * Sending a capability to business-service would now be refused there anyway — it no longer
             * publishes the capability catalog, and SettingsService.set rejects keys it does not know. That
             * refusal is the safety net; this is the routing that makes it unnecessary.
             */
            if (ownedByAuth(key)) {
                Map<String, Object> saved = authPost("/settings?" + qs);
                /*
                 * C3c — take a fresh token so the change applies NOW, to this session.
                 *
                 * Capabilities are resolved at token mint and carried as a claim, which is what keeps them off
                 * every hot path. The cost is staleness, and it lands hardest exactly here: the owner who just
                 * switched a capability would keep the old answer until their token next changed. The screen
                 * would not update and the endpoint would not refuse — the switch would simply look broken,
                 * which is how a deliberate trade-off gets reported as a bug.
                 *
                 * Only this session is re-minted. Other sessions of the same tenant pick the change up on their
                 * next refresh, which is the eventual consistency C was chosen with.
                 *
                 * Best-effort: the setting IS saved either way, so a failed refresh must not turn a successful
                 * write into an error. It only means this session waits like any other.
                 */
                try {
                    gateway.refreshNow();
                } catch (Exception refreshFailed) {
                    LOGGER.warn("Capability saved but the session token could not be re-minted; the change "
                            + "will take effect for this session on its next refresh.", refreshFailed);
                }
                return saved;
            }
            return client.postJson("/settings?" + qs, java.util.Map.of());
        } catch (Exception e) {
            LOGGER.error("saveBusinessConfig proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── C3c: reaching auth-service, the owner of capabilities and shape ─────────────────────────────

    /** The gateway prefix auth-service is routed under. */
    private static final String AUTH_PREFIX = "/api/auth";

    /**
     * Keys auth-service owns.
     *
     * <p>{@code org.cap.*} is a reserved namespace and {@code org.shape} is the single shape key — both are
     * built in one place ({@code Capability.settingKey()}, {@code Shape.settingKey()}) precisely so the
     * prefixes cannot drift from the constants that generate them.
     */
    private static boolean ownedByAuth(String key) {
        return key != null && (key.startsWith("org.cap.") || "org.shape".equals(key));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.web.util.GatewayClient gateway;

    @org.springframework.beans.factory.annotation.Value("${auth.server.url:http://localhost:8765}")
    private String authDirectUrl;

    private Map<String, Object> authGet(String path) {
        return gateway.forMap(AUTH_PREFIX, authDirectUrl, path,
                org.springframework.http.HttpMethod.GET, null, null);
    }

    private Map<String, Object> authPost(String path) {
        return gateway.forMap(AUTH_PREFIX, authDirectUrl, path,
                org.springframework.http.HttpMethod.POST, java.util.Map.of(),
                org.springframework.http.MediaType.APPLICATION_JSON);
    }
}
