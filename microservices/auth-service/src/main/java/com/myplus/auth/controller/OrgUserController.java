package com.myplus.auth.controller;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.AuthService;
import com.myplus.auth.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Owner/team management: a company's SUPER owner creates ADMIN/USER members in their OWN organization
 * and lists them. Confined to the caller's ACTIVE org, read from the Bearer JWT — /api/auth/** has no
 * gateway org-injection, so we read activeOrgId from the token here (the token is trusted: the
 * JwtAuthFilter already validated it and ROLE_OWNER is enforced below). Gated on the OWNER *role*
 * (not SUPER_PRIVILEGE) so a DEMO account — which has super privileges to use the app but is NOT an
 * owner — cannot create team members.
 */
@RestController
@RequestMapping("/api/auth/org")
@RequiredArgsConstructor
public class OrgUserController {

    private final AuthService authService;
    private final JwtService jwtService;

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_ROLE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        Long callerUserId = jwtService.extractUserId(token);
        Long orgId = orgId(token);
        // Owner creates ADMIN/USER; admin creates USER only (enforced in the service). storeIds = optional
        // store grants for the new member (an admin may only grant stores they hold).
        Map<String, Object> created = authService.createOrgUser(
                str(body.get("firstName")), str(body.get("lastName")), str(body.get("email")), str(body.get("role")),
                orgId, callerUserId, isOwner(token), toLongList(body.get("storeIds")));
        return ResponseEntity.ok(ApiResponse.success(created,
                "Team member created — a set-password email was sent."));
    }

    /**
     * Assign location access to a user (owner: any location; admin: only ones they hold). userId omitted = self.
     * <p>{@code replace:true} makes the list the member's COMPLETE set — locations left out are revoked. That is
     * what "reassign" needs: without it the endpoint could only ever add, so an owner could never move someone
     * from one store to another, or take access away.
     */
    @PostMapping("/locations/grant")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_ROLE')")
    public ResponseEntity<ApiResponse<String>> grantLocations(
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        Long callerUserId = jwtService.extractUserId(token);
        Long orgId = orgId(token);
        Long targetUserId = body.get("userId") != null ? Long.valueOf(String.valueOf(body.get("userId"))) : callerUserId;
        authService.assignLocations(callerUserId, orgId, isOwner(token), targetUserId,
                toLongList(body.get("storeIds")), str(body.get("roleAtLocation")),
                Boolean.parseBoolean(String.valueOf(body.get("replace"))));
        return ResponseEntity.ok(ApiResponse.success("OK", "Access updated."));
    }

    /** P5b — the caller's own store grants (any member, not just an owner): feeds the store switcher. */
    @GetMapping("/locations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myLocations(
            @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        return ResponseEntity.ok(ApiResponse.success(
                authService.myLocations(jwtService.extractUserId(token), orgId(token)), "OK"));
    }

    /** P5b — set the ACTIVE store and re-issue the tokens (the location twin of /switch-organization).
     *  No role gate: the grant itself is the authority, and it is checked server-side. */
    @PostMapping("/locations/switch")
    public ResponseEntity<ApiResponse<com.myplus.auth.dto.AuthResponse>> switchLocation(
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        Long locationId = body.get("storeId") != null ? Long.valueOf(String.valueOf(body.get("storeId"))) : null;
        return ResponseEntity.ok(ApiResponse.success(
                authService.switchLocation(jwtService.extractUserId(token), orgId(token), locationId),
                "Active store switched"));
    }

    private boolean isOwner(String token) {
        Object roles = jwtService.extractClaim(token, c -> c.get("roles"));
        return roles != null && roles.toString().contains("ROLE_OWNER");
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static java.util.List<Long> toLongList(Object o) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        if (o instanceof java.util.List<?> l)
            for (Object x : l) { try { out.add(Long.valueOf(String.valueOf(x))); } catch (Exception ignored) {} }
        return out;
    }

    /** Owner OR admin: an ADMIN may create members (below), so refusing them the list left them managing people
     *  blind — they could add someone but never see the team. Always confined to the caller's active org. */
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_ROLE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestHeader("Authorization") String auth) {
        Long orgId = orgId(bearer(auth));
        return ResponseEntity.ok(ApiResponse.success(authService.listOrgUsers(orgId), "OK"));
    }

    private static String bearer(String auth) {
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : auth;
    }

    /** The caller's active org id, from the JWT activeOrgId claim. */
    private Long orgId(String token) {
        Object v = jwtService.extractClaim(token, c -> c.get("activeOrgId"));
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.valueOf(v.toString());
    }
}
