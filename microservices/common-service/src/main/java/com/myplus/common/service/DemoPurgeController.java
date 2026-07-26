package com.myplus.common.service;

import com.myplus.common.security.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
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

    @PersistenceContext
    private EntityManager em;

    /** The two authorities allowed to purge: a capped demo account, or the dev-seeded owner test account
     *  (DEMO_RESET_PRIVILEGE, granted only by DEMO_ROLE/DEMO_RESET_ROLE — never by ROLE_OWNER, so a real
     *  customer's owner can never wipe their organisation from a button). */
    private static final Set<String> RESET_AUTHORITIES = Set.of("DEMO_PRIVILEGE", "DEMO_RESET_PRIVILEGE");

    @DeleteMapping("/purge")
    @PreAuthorize("hasAuthority('DEMO_PRIVILEGE') or hasAuthority('DEMO_RESET_PRIVILEGE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> purge(@AuthenticationPrincipal AuthenticatedUser user) {
        boolean demo = user != null && user.getAuthorities() != null
                && user.getAuthorities().stream().anyMatch(a -> RESET_AUTHORITIES.contains(a.getAuthority()));
        if (!demo) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Demo/reset accounts only"));
        }
        Long org = user.getOrganizationId();
        Long uid = user.getUserId();
        long total = 0;
        // MySQL: relax FK ordering for the duration of the purge (best-effort).
        try { em.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate(); } catch (Exception ignore) { }
        for (EntityType<?> et : em.getMetamodel().getEntities()) {
            Set<String> attrs = et.getAttributes().stream().map(a -> a.getName()).collect(Collectors.toSet());
            if (attrs.contains("organizationId") && org != null) {
                total += em.createQuery("delete from " + et.getName() + " e where e.organizationId = :v")
                        .setParameter("v", org).executeUpdate();
            } else if (attrs.contains("userId") && uid != null) {
                total += em.createQuery("delete from " + et.getName() + " e where e.userId = :v")
                        .setParameter("v", uid).executeUpdate();
            }
        }
        try { em.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate(); } catch (Exception ignore) { }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("deleted", total);
        body.put("message", "Demo data cleared");
        return ResponseEntity.ok(body);
    }
}
