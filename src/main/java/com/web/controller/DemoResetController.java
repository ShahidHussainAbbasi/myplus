package com.web.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * "Reset demo" — puts the logged-in demo (or dev owner) account back to a clean slate: clears the write
 * counters at the gateway so the 50/module trial restarts on demand (it otherwise auto-resets daily), then
 * deletes the account's own org data in every purge-capable service. Proxies to the gateway with the session
 * Bearer token. Destructive by design, which is why both the counter reset and each purge are guarded to
 * DEMO_PRIVILEGE / DEMO_RESET_PRIVILEGE server-side.
 */
@Controller
public class DemoResetController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    /** Bounded on purpose: the reset now fans out to a dozen services in sequence, so an unresponsive one must
     *  fail fast rather than hang the page. A timed-out purge is reported as "unavailable, run it again" — the
     *  operation is idempotent, so a second run finishes the job. */
    private final RestTemplate rest = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2_000);
        f.setReadTimeout(8_000);
        return new RestTemplate(f);
    }

    // Every service-parent child auto-exposes the shared common-service DemoPurgeController at
    // /api/<module>/demo/purge, and each purge deletes ONLY the caller's org rows and is privilege-guarded —
    // so the reset simply calls them ALL. That is deliberate: a demo account's data is spread far wider than
    // its "own" module (a POS demo also writes catalog products, inventory stock, finance AR/AP + GL journal
    // and party contacts), and the old userType->one-service map silently went stale every time a service was
    // added, leaving stock without products and ledger rows without invoices. A service holding nothing for
    // this org just deletes 0.
    //
    // NO exclusions: a reset removes EVERYTHING this account created, in all 15 purge-capable services —
    // including the audit trail and notification logs. A demo tenant restarting from zero should keep nothing that
    // describes records which no longer exist. The audit trail's append-only guarantee still holds where it
    // matters: the purge is gated to DEMO_PRIVILEGE / DEMO_RESET_PRIVILEGE, which no real customer's user carries.
    private static final List<String> PURGE_PATHS = List.of(
            "/api/business/demo/purge",
            "/api/education/demo/purge",
            "/api/welfare/demo/purge",
            "/api/agriculture/demo/purge",
            "/api/appointment/demo/purge",
            "/api/pharma/demo/purge",        // pharmacy has its OWN service (prescriptions/dispense) as well as
            "/api/catalog/demo/purge",       // reusing the trade backend — the old map only cleared the latter
            "/api/inventory/demo/purge",
            "/api/finance/demo/purge",
            "/api/party/demo/purge",
            "/api/marketplace/demo/purge",
            "/api/campaign/demo/purge",
            "/api/analytics/demo/purge",
            "/api/notifications/demo/purge",
            "/api/audit/demo/purge");        // last: clear the trail only after the records it describes are gone

    /** Authorities allowed to reset: a capped demo account, or the dev-seeded owner test account. */
    private static final Set<String> RESET_AUTHORITIES = Set.of("DEMO_PRIVILEGE", "DEMO_RESET_PRIVILEGE");

    @RequestMapping(value = "/demo/reset", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse reset() {
        GenericResponse response = new GenericResponse();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            // 1) clear the write counters at the gateway (restart the 50/module trial)
            rest.exchange(gatewayUrl + "/demo/reset", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
            // 2) delete this account's data across EVERY purge-capable service. Only for a demo/reset
            //    principal — and each endpoint is itself privilege-guarded, so a real tenant can never be
            //    purged by mistake. One service failing must not abort the rest: a partial reset is still
            //    better than none, and every purge is idempotent, so re-running finishes the job.
            long deleted = 0;
            int cleared = 0, failed = 0;
            if (mayReset()) {
                for (String path : PURGE_PATHS) {
                    try {
                        ResponseEntity<Map> r = rest.exchange(gatewayUrl + path, HttpMethod.DELETE,
                                new HttpEntity<>(headers), Map.class);
                        Object n = r.getBody() == null ? null : r.getBody().get("deleted");
                        if (n instanceof Number num) deleted += num.longValue();
                        cleared++;
                    } catch (Exception pe) {
                        failed++;
                        LOGGER.warn("demo purge skipped for {}: {}", path, pe.getMessage());
                    }
                }
            }
            // The trial line only makes sense for a capped demo account — the owner test account is uncapped.
            String trial = isDemoPrincipal() ? " The 50-entry trial is fresh again." : "";
            if (cleared == 0) {
                response.setMessage("Demo reset — nothing to clear." + trial);
            } else if (failed > 0) {
                response.setMessage("Demo reset — cleared " + deleted + " records across " + cleared
                        + " services (" + failed + " unavailable, run it again to finish)." + trial);
            } else {
                response.setMessage("Demo reset — cleared " + deleted + " records across " + cleared
                        + " services." + trial);
            }
        } catch (Exception e) {
            LOGGER.error("demo reset failed", e);
            response.setError("ResetFailed");
            response.setMessage("Could not reset the demo right now. Please try again.");
        }
        return response;
    }

    /**
     * May the logged-in principal purge? True for a demo account, and for any principal holding
     * DEMO_RESET_PRIVILEGE — which is the dev-seeded owner test account (uncapped, demo=false) and never a
     * real customer's owner, since the privilege rides its own role rather than ROLE_OWNER.
     */
    private boolean mayReset() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return false;
        if (isDemoPrincipal()) return true;
        return a.getAuthorities() != null
                && a.getAuthorities().stream().anyMatch(g -> RESET_AUTHORITIES.contains(g.getAuthority()));
    }

    /** A capped demo.* account (as opposed to the uncapped owner test account, which may also reset). */
    private boolean isDemoPrincipal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof User u && u.isDemo();
    }
}
