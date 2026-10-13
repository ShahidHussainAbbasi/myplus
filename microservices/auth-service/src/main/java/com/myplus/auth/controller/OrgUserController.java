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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final com.myplus.auth.service.PermissionService permissionService;
    private final JwtService jwtService;
    private final com.myplus.auth.repository.OrganizationRepository organizationRepo;

    /**
     * Which permission vocabulary this tenant speaks — see {@code PermissionService.moduleOf}.
     *
     * <p>The matrix must be drawn from the tenant's OWN catalogue. Read unfiltered, a school is offered
     * sale.create, purchase.create and Cashier — codes and sets that mean nothing on its screens and that
     * would be minted the moment somebody ticked one.
     */
    private String callerModule(Long orgId) {
        return organizationRepo.findById(orgId)
                .map(o -> com.myplus.auth.service.PermissionService.moduleOf(String.valueOf(o.getType())))
                .orElse(null);
    }
    private final com.myplus.auth.service.OrganizationAdminService organizationAdminService;
    /** H3a — what this business has switched on decides which clinic / pharmacist sets the Team screen offers. */
    private final com.myplus.common.settings.CapabilityService capabilityService;
    private final com.myplus.auth.repository.UiMenuRepository uiMenuRepository;
    private final com.myplus.auth.repository.UiWorkspaceRepository uiWorkspaceRepository;
    private final com.myplus.auth.service.TeamMemberService teamMembers;

    /**
     * ONB-1 — the tenant changes its OWN business type, from its Configuration screen.
     *
     * <h3>Why this is not just a settings write</h3>
     * The Configuration screen posts every switch through {@code /settings}, which upserts one row. For
     * {@code org.shape} that would change the FALLBACK and leave every {@code org.cap.*} override standing —
     * so an owner picking "Pharmacy" would watch nothing happen. That is the complaint that started this
     * slice, and re-applying the preset is more than one row.
     *
     * <p>Gated like {@code SettingsController.save}: owner or admin. Scoped to the caller's own organization
     * by {@code CurrentUser}, so there is no id to tamper with.
     */
    @PostMapping("/shape")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<ApiResponse<Void>> changeOwnShape(@RequestBody Map<String, Object> body) {
        organizationAdminService.changeOwnShape(body.get("shape") == null ? null : String.valueOf(body.get("shape")));
        return ResponseEntity.ok(ApiResponse.success(null, "Business type updated"));
    }

    /** ONB-1 — what changing to this business type would turn on and off, for the confirmation. */
    @GetMapping("/shape-preview")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewOwnShape(@RequestParam String shape) {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        return ResponseEntity.ok(ApiResponse.success(
                organizationAdminService.previewShape(org, shape), "Preview"));
    }

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
                orgId, callerUserId, isOwner(token), toLongList(body.get("storeIds")), str(body.get("permissionSet")));
        return ResponseEntity.ok(ApiResponse.success(created,
                "Team member created — a set-password email was sent."));
    }

    /**
     * Assign location access to a user (owner: any location; admin: only ones they hold). userId omitted = self.
     * <p>{@code replace:true} makes the list the member's COMPLETE set — locations left out are revoked. That is
     * what "reassign" needs: without it the endpoint could only ever add, so an owner could never move someone
     * from one store to another, or take access away.
     */
    // ── PERM-1: permission sets ────────────────────────────────────────────────────────────────
    //
    // ⚠ OWNER ONLY, every one of them — the owner's own ruling, and it removes a whole class of problem
    // with it. If an admin could edit sets, an admin holding team.edit could grant themselves finance,
    // and the model would need a "you cannot grant what you do not hold" rule enforced on every path.
    // Only the owner grants, and the owner already holds everything, so there is nothing to escalate to.

    /** The catalog and this tenant's sets — everything the matrix screen draws itself from. */
    // ── TEAM-1 (2026-10-13): after adding a member — resend the invitation, remove (reversible), reactivate ──────────
    // Same guard as adding a member; the who-may rules (owner / admin for ordinary members, never the owner or
    // oneself, only this business) are TeamMemberService's.

    @PostMapping("/users/{userId}/resend-invite")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_ROLE')")
    public ResponseEntity<ApiResponse<Void>> resendInvite(@org.springframework.web.bind.annotation.PathVariable Long userId,
                                                          @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        teamMembers.resendInvite(jwtService.extractUserId(token), orgId(token), isOwner(token), userId);
        return ResponseEntity.ok(ApiResponse.success(null, "A new set-password e-mail was sent. Older links no longer work."));
    }

    @PostMapping("/users/{userId}/remove")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_ROLE')")
    public ResponseEntity<ApiResponse<Void>> removeMember(@org.springframework.web.bind.annotation.PathVariable Long userId,
                                                          @RequestBody(required = false) Map<String, Object> body,
                                                          @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        teamMembers.remove(jwtService.extractUserId(token), orgId(token), isOwner(token), userId,
                body == null ? null : str(body.get("reason")));
        return ResponseEntity.ok(ApiResponse.success(null, "Removed. They are signed out and cannot sign in to this business. You can reactivate them."));
    }

    @PostMapping("/users/{userId}/reactivate")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_ROLE')")
    public ResponseEntity<ApiResponse<Void>> reactivateMember(@org.springframework.web.bind.annotation.PathVariable Long userId,
                                                              @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        teamMembers.reactivate(jwtService.extractUserId(token), orgId(token), isOwner(token), userId);
        return ResponseEntity.ok(ApiResponse.success(null, "Reactivated. They can sign in again with their own password."));
    }

    /**
     * RW-3 — THIS person's workspaces (V27 rows): those whose setting is on for their business and whose codes they
     * hold (the owner and an administrator hold all), in order. One → sign-in lands there; several → the tile home;
     * none → the landing they always had (a plain shop: every row needs the clinic). Nothing about a workspace is
     * decided in code — only "does the row's setting hold" and "does the person hold a row's code".
     */
    @GetMapping("/workspaces")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> workspaces(@RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        Long org = orgId(token);
        java.util.Set<String> on = com.myplus.auth.service.PermissionVisibility.enabled(
                org == null ? null : capabilityService.enabledMapFor(org));
        Object claim = jwtService.extractClaim(token, c -> c.get("privileges"));
        java.util.Set<String> held = new java.util.HashSet<>();
        if (claim instanceof java.util.Collection<?> col) col.forEach(x -> held.add(String.valueOf(x)));
        boolean everything = isOwner(token) || held.contains("ADMIN_PRIVILEGE") || held.contains("SUPER_PRIVILEGE");
        List<Map<String, Object>> out = uiWorkspaceRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(w -> com.myplus.auth.service.PermissionVisibility.visible(w.getCapability(), on))
                .filter(w -> com.myplus.auth.service.PermissionVisibility.opens(w.getCodes(), held, everything))
                .map(w -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("key", w.getWsKey());
                    row.put("label", w.getLabelKey());
                    row.put("icon", w.getIcon());
                    row.put("url", w.getUrl());
                    return row;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(out, "Workspaces"));
    }

    /**
     * RW-0b — the menu catalogue (V25): each menu's key and the permission codes that unlock it. The monolith reads it
     * to decide which menus a person sees; the pages name only the KEY. Any signed-in member may read it — it says
     * which codes open which menu, nothing about any person — and the rows are the same for every business.
     */
    @GetMapping("/menus")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> menus() {
        List<Map<String, Object>> out = uiMenuRepository.findAllByOrderBySortOrderAsc().stream().map(m -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("key", m.getMenuKey());
            row.put("parent", m.getParentKey());
            row.put("codes", m.getCodes() == null || m.getCodes().isBlank() ? List.of()
                    : java.util.Arrays.stream(m.getCodes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            row.put("capability", m.getCapability());
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(out, "Menus"));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> permissions() {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String module = callerModule(org);
        // RW-0a: a code or set is offered when the setting its row names is on (V24 capability columns) — no names here
        java.util.Set<String> on = com.myplus.auth.service.PermissionVisibility.enabled(capabilityService.enabledMapFor(org));
        java.util.List<com.myplus.auth.entity.Permission> catalog = permissionService.catalog(module);
        java.util.Map<String, String> capabilityOf = new java.util.HashMap<>();
        catalog.forEach(p -> capabilityOf.put(p.getCode(), p.getCapability()));
        out.put("catalog", catalog.stream()
                .filter(p -> com.myplus.auth.service.PermissionVisibility.visible(p.getCapability(), on)).map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("code", p.getCode());
            m.put("area", p.getArea());
            m.put("action", p.getAction());
            m.put("label", p.getLabel());
            m.put("implies", p.getImplies());
            return m;
        }).toList());
        out.put("sets", permissionService.setsFor(org, module).stream()
                .filter(ps -> com.myplus.auth.service.PermissionVisibility.visible(ps.getCapability(), on)).map(ps -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", ps.getId());
            m.put("name", ps.getName());
            m.put("description", ps.getDescription());
            m.put("scope", ps.getScope());
            m.put("builtin", ps.isBuiltin());
            m.put("codes", permissionService.codesOf(ps.getId()).stream()
                    .filter(c -> com.myplus.auth.service.PermissionVisibility.visible(capabilityOf.get(c), on)).toList());
            return m;
        }).toList());
        return ResponseEntity.ok(ApiResponse.success(out, "Permissions"));
    }

    /**
     * Create or update one of this tenant's own sets.
     *
     * <p>The CLOSURE runs in the service, not here and not only in the browser: a set stored through this
     * endpoint by anything other than the matrix screen must still be coherent, or it produces a member
     * whose sale screen has an empty item picker and no explanation.
     */
    @PostMapping("/permissions/sets")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveSet(@RequestBody Map<String, Object> body) {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        var set = permissionService.save(org, callerModule(org), toLong(body.get("id")), str(body.get("name")),
                str(body.get("description")), str(body.get("scope")), toStringList(body.get("codes")));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", set.getId());
        m.put("codes", permissionService.codesOf(set.getId()));
        return ResponseEntity.ok(ApiResponse.success(m, "Permission set saved."));
    }

    /**
     * Put a member on a set.
     *
     * <p>⚠ Takes effect within 15 minutes, not instantly — the permissions travel in the access token and
     * the token is not re-minted until it refreshes. The owner accepted that trade deliberately; it is
     * repeated here because "I removed him and he could still sell" is otherwise reported as a defect.
     */
    @PostMapping("/permissions/assign")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<String>> assign(@RequestBody Map<String, Object> body) {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        permissionService.assign(toLong(body.get("userId")), toLong(body.get("setId")), org);
        return ResponseEntity.ok(ApiResponse.success("assigned",
                "Saved. It applies the next time they sign in, and within 15 minutes at the latest."));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/permissions/sets/{id}")
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    public ResponseEntity<ApiResponse<String>> deleteSet(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        permissionService.delete(id, com.myplus.common.security.CurrentUser.organizationId());
        return ResponseEntity.ok(ApiResponse.success("deleted", "Permission set deleted."));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> toStringList(Object v) {
        if (!(v instanceof java.util.List<?> l)) return java.util.List.of();
        return l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        try { return Long.valueOf(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }

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
