package com.myplus.auth.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.entity.SupportSession;
import com.myplus.auth.service.AuthService;
import com.myplus.auth.service.JwtService;
import com.myplus.auth.service.SupportSessionService;

import lombok.RequiredArgsConstructor;

/**
 * E5 — support sessions, from both sides.
 *
 * <h3>Two roots, deliberately, and the split is the security</h3>
 * {@code /api/auth/admin/support-sessions} is the OPERATOR's and is gated on {@code ROLE_ADMIN}.
 * {@code /api/auth/support-sessions} is the CUSTOMER's and is gated on {@code ROLE_OWNER}. They are separate
 * mappings rather than one path with a branch inside, because the branch is the kind of thing that gets
 * simplified away by someone who does not know why it is there — and simplifying it away would let a tenant
 * owner open a session over another tenant.
 *
 * <h3>ROLE_ADMIN, never ADMIN_PRIVILEGE</h3>
 * Every tenant owner holds the super privilege set inside their own organization, so a privilege gate here
 * would hand every customer a support session over every other. The same reasoning E1, E2 and E4 all record;
 * it matters most here, because this endpoint is the one that reaches a customer's actual data.
 */
@RestController
@RequiredArgsConstructor
public class SupportSessionController {

    private final SupportSessionService sessions;
    private final JwtService jwtService;
    private final AuthService authService;

    // ── the operator's side ───────────────────────────────────────────────────────────────────────

    /**
     * Open a session over one tenant.
     *
     * <p>Answers with a <b>re-minted access token</b>. The scope is a claim, so without one the operator
     * would open a session, click into the customer, and be answered about their own organization — a wrong
     * number under someone else's name.
     *
     * <p>Body: {@code organizationId, reason, minutes?}.
     */
    @PostMapping("/api/auth/admin/support-sessions")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> open(@RequestBody Map<String, Object> body,
                                                                 @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        Long actor = jwtService.extractUserId(token);
        Long actorOrgId = asLong(jwtService.extractClaim(token, c -> c.get("activeOrgId")));

        SupportSession s = sessions.open(asLong(body.get("organizationId")), str(body.get("reason")),
                asInt(body.get("minutes")), actor, actorOrgId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("organizationId", s.getSubjectOrgId());
        // Offset-carrying, for the same reason the list is — see SupportSessionService.iso.
        out.put("expiresAt", s.getExpiresAt()
                .atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString());
        out.put("accessToken", authService.mintAccessTokenFor(actor));
        return ResponseEntity.ok(ApiResponse.success(out, "Support session open"));
    }

    /** End a session early. The operator's own; the customer has their own route below. */
    @PostMapping("/api/auth/admin/support-sessions/{id}/close")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> close(@PathVariable("id") Long id,
                                                                   @RequestHeader("Authorization") String auth) {
        Long actor = jwtService.extractUserId(bearer(auth));
        sessions.close(id, actor, null);
        Map<String, Object> out = new LinkedHashMap<>();
        // A closed session must stop reaching the tenant, and the claim is what carries the scope — so the
        // operator gets a token WITHOUT it rather than being left holding the one that still works.
        out.put("accessToken", authService.mintAccessTokenFor(actor));
        return ResponseEntity.ok(ApiResponse.success(out, "Support session closed"));
    }

    /** One tenant's support history, for the console's detail panel. */
    @GetMapping("/api/auth/admin/support-sessions")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestParam Long organizationId,
                                                                  @RequestParam(defaultValue = "25") int limit) {
        return ResponseEntity.ok(ApiResponse.success(sessions.forOrganization(organizationId, limit), "Sessions"));
    }

    // ── the customer's side ───────────────────────────────────────────────────────────────────────

    /**
     * Every support session over the caller's OWN organization.
     *
     * <p>The half of "audited" that means anything to the person being supported. Scoped to
     * {@code activeOrgId} from the validated token — there is no id parameter, so there is nothing to tamper
     * with and no anti-IDOR rule to get wrong.
     */
    @GetMapping("/api/auth/support-sessions/mine")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mine(@RequestHeader("Authorization") String auth,
                                                                  @RequestParam(defaultValue = "25") int limit) {
        Long orgId = asLong(jwtService.extractClaim(bearer(auth), c -> c.get("activeOrgId")));
        return ResponseEntity.ok(ApiResponse.success(sessions.forOrganization(orgId, limit), "Sessions"));
    }

    /**
     * The customer allows this session to change their records.
     *
     * <p>Only the tenant the session is over can call this — an approval the operator could grant themselves
     * is not consent, it is paperwork.
     */
    @PostMapping("/api/auth/support-sessions/{id}/approve-writes")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable("id") Long id,
                                                      @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        sessions.approveWrites(id, asLong(jwtService.extractClaim(token, c -> c.get("activeOrgId"))),
                jwtService.extractUserId(token));
        return ResponseEntity.ok(ApiResponse.success(null, "Changes allowed"));
    }

    /**
     * The customer ends a session.
     *
     * <p>⚠ It stops reaching them when the operator's token next refreshes — inside 15 minutes — not at the
     * instant of the click. The console says so in the confirmation rather than implying otherwise: an access
     * record the subject can read but not stop is a notice, and one that claims to stop it instantly when it
     * does not is worse than either.
     */
    @PostMapping("/api/auth/support-sessions/{id}/end")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<Void>> end(@PathVariable("id") Long id,
                                                  @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        sessions.close(id, jwtService.extractUserId(token),
                asLong(jwtService.extractClaim(token, c -> c.get("activeOrgId"))));
        return ResponseEntity.ok(ApiResponse.success(null, "Access ended"));
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────────────────

    private static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("organizationId must be a number");
        }
    }

    /** Minutes, or null for the default. A malformed value is REFUSED rather than silently defaulted — a
     *  typo in a duration must not quietly become a longer session than the operator asked for. */
    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("minutes must be a number");
        }
    }
}
