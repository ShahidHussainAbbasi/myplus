package com.myplus.auth.controller;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.dto.AuthResponse;
import com.myplus.auth.service.AuthService;
import com.myplus.auth.service.JwtService;
import com.myplus.auth.service.OrganizationService;
import com.myplus.auth.service.OrganizationService.OrgView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Organization listing + switching for the active user. Routed via the gateway's {@code /api/auth/**}
 * rule, which (unlike the resource-service routes) has NO JWT filter and NO StripPrefix — so identity
 * is read from the Bearer token here, exactly like {@code /api/auth/validate}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final AuthService authService;
    private final JwtService jwtService;

    /** The organizations the caller belongs to, with the active one flagged. */
    @GetMapping("/organizations")
    public ResponseEntity<ApiResponse<List<OrgView>>> organizations(
            @RequestHeader("Authorization") String authHeader) {
        String token = bearer(authHeader);
        Long userId = jwtService.extractUserId(token);
        Long activeOrgId = activeOrgId(token);
        return ResponseEntity.ok(ApiResponse.success(
                organizationService.listForUser(userId, activeOrgId)));
    }

    /** Re-issue tokens scoped to the chosen org (membership-validated in {@link AuthService}). */
    @PostMapping("/switch-organization")
    public ResponseEntity<ApiResponse<AuthResponse>> switchOrganization(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> body) {
        String token = bearer(authHeader);
        Long userId = jwtService.extractUserId(token);
        Long orgId = toLong(body.get("organizationId"));
        return ResponseEntity.ok(ApiResponse.success(
                authService.switchOrganization(userId, orgId), "Active organization switched"));
    }

    private String bearer(String authHeader) {
        return authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
    }

    private Long activeOrgId(String token) {
        return toLong(jwtService.extractClaim(token, claims -> claims.get("activeOrgId")));
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(value.toString());
    }
}
