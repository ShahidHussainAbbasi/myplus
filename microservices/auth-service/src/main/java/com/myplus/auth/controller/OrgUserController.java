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
 * JwtAuthFilter already validated it and SUPER_PRIVILEGE is enforced below).
 */
@RestController
@RequestMapping("/api/auth/org/users")
@RequiredArgsConstructor
public class OrgUserController {

    private final AuthService authService;
    private final JwtService jwtService;

    @PostMapping
    @PreAuthorize("hasAuthority('SUPER_PRIVILEGE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, String> body,
            @RequestHeader("Authorization") String auth) {
        String token = bearer(auth);
        Long callerUserId = jwtService.extractUserId(token);
        Long orgId = orgId(token);
        Map<String, Object> created = authService.createOrgUser(
                body.get("firstName"), body.get("lastName"), body.get("email"), body.get("role"),
                orgId, callerUserId);
        return ResponseEntity.ok(ApiResponse.success(created,
                "Team member created — a set-password email was sent."));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SUPER_PRIVILEGE')")
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
