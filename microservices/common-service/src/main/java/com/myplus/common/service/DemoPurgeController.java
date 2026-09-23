package com.myplus.common.service;

import com.myplus.common.security.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Shared "Reset demo" purge — ONE implementation auto-applied to every JPA service via
 * {@code common-service}. Deletes the calling DEMO account's rows in <em>this</em> service, scoped by
 * whichever tenancy column each entity carries ({@code organizationId} or {@code userId}) for the caller
 * only — so it is tenant-safe and never touches another account's data.
 * <p>
 * Guarded two ways — {@code @PreAuthorize} and an explicit authority check (so it stays safe even if a
 * service lacks method security) — to {@code DEMO_PRIVILEGE} (capped demo accounts) or
 * {@code DEMO_RESET_PRIVILEGE} (the dev-seeded owner test account). Mapped at {@code /demo/purge}; the
 * gateway routes {@code /api/<module>/demo/**} here (StripPrefix).
 */
@RestController
@RequestMapping("/demo")
public class DemoPurgeController {

    private static final Logger LOG = LoggerFactory.getLogger(DemoPurgeController.class);

    @PersistenceContext
    private EntityManager em;

    private final Environment environment;

    /**
     * The production kill-switch. Defaults to FALSE, so a {@code prod} deployment refuses to purge unless
     * somebody turns it on deliberately. See docs/deploy/ZERO-DATA-LOSS-MIGRATION-PLAN.md.
     */
    private final boolean purgeEnabled;

    public DemoPurgeController(Environment environment,
                               @Value("${app.demo.purge-enabled:false}") boolean purgeEnabled) {
        this.environment = environment;
        this.purgeEnabled = purgeEnabled;
    }

    /** True when this service is running under the {@code prod} profile. */
    private boolean isProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }

    /** The two authorities allowed to purge: a capped demo account, or the dev-seeded owner test account
     *  (DEMO_RESET_PRIVILEGE, granted only by DEMO_ROLE/DEMO_RESET_ROLE — never by ROLE_OWNER, so a real
     *  customer's owner can never wipe their organisation from a button). */
    private static final Set<String> RESET_AUTHORITIES = Set.of("DEMO_PRIVILEGE", "DEMO_RESET_PRIVILEGE");

    @DeleteMapping("/purge")
    @PreAuthorize("hasAuthority('DEMO_PRIVILEGE') or hasAuthority('DEMO_RESET_PRIVILEGE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> purge(@AuthenticationPrincipal AuthenticatedUser user) {
        /*
         * PRODUCTION REFUSES, FIRST -- before the privilege check, because this is not about who is asking.
         *
         * Everything below deletes every row carrying the caller's organizationId, in this service, with no
         * backup and no undo. Until now the only thing between a real tenant and that was CONFIGURATION:
         * app.seed-demo=false in prod means nobody holds DEMO_PRIVILEGE, so nobody can reach it. True -- and one
         * environment variable deep. Setting SEED_DEMO=true in production, to show the product to a customer,
         * seeds demo tenants AND a known-credential owner account carrying DEMO_RESET_PRIVILEGE, and the
         * whole-organisation delete becomes reachable from a button.
         *
         * Not a hypothetical failure. On 2026-09-22 a full-suite Cypress run reached demo-reset.cy.js, which
         * signs in and calls this endpoint; it purged that tenant in dev. The spec PASSED -- clearing the org is
         * the feature -- and the loss surfaced only as four unrelated-looking spec failures afterwards.
         *
         * So the guard is structural rather than configuration-deep: under the prod profile this refuses
         * outright unless someone has deliberately set app.demo.purge-enabled=true.
         */
        if (isProduction() && !purgeEnabled) {
            LOG.warn("REFUSED demo purge in production: user={} org={} -- set app.demo.purge-enabled=true to allow",
                    user == null ? null : user.getUserId(), user == null ? null : user.getOrganizationId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", "Demo reset is disabled in production."));
        }

        boolean demo = user != null && user.getAuthorities() != null
                && user.getAuthorities().stream().anyMatch(a -> RESET_AUTHORITIES.contains(a.getAuthority()));
        if (!demo) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Demo/reset accounts only"));
        }
        Long org = user.getOrganizationId();
        Long uid = user.getUserId();
        long total = 0;

        /*
         * Say what is about to be destroyed BEFORE destroying it, and what was destroyed per entity afterwards.
         * A purge reporting only a grand total leaves nobody able to answer "what did we lose?" -- the first
         * question asked when one of these runs by accident.
         */
        LOG.warn("DEMO PURGE starting: user={} org={} service={}", uid, org,
                environment.getProperty("spring.application.name", "unknown"));
        Map<String, Long> perEntity = new TreeMap<>();
        // MySQL: relax FK ordering for the duration of the purge (best-effort).
        try { em.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate(); } catch (Exception ignore) { }
        for (EntityType<?> et : em.getMetamodel().getEntities()) {
            Set<String> attrs = et.getAttributes().stream().map(a -> a.getName()).collect(Collectors.toSet());
            int n = 0;
            if (attrs.contains("organizationId") && org != null) {
                n = em.createQuery("delete from " + et.getName() + " e where e.organizationId = :v")
                        .setParameter("v", org).executeUpdate();
            } else if (attrs.contains("userId") && uid != null) {
                n = em.createQuery("delete from " + et.getName() + " e where e.userId = :v")
                        .setParameter("v", uid).executeUpdate();
            }
            if (n > 0) perEntity.put(et.getName(), (long) n);
            total += n;
        }
        try { em.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate(); } catch (Exception ignore) { }

        LOG.warn("DEMO PURGE complete: user={} org={} deleted={} breakdown={}", uid, org, total, perEntity);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("deleted", total);
        body.put("perEntity", perEntity);
        body.put("message", "Demo data cleared");
        return ResponseEntity.ok(body);
    }
}
