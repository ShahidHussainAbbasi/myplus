package com.myplus.auth.controller;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.EntitlementService;
import com.myplus.auth.service.JwtService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * E1 — the platform operator's entitlement API. The screen that calls it is E2.
 *
 * <h3>Gated on the platform ROLE_ADMIN, never on a privilege</h3>
 * Company owners hold the super privilege set inside their own tenant ({@code ROLE_OWNER}), so
 * {@code hasAuthority('ADMIN_PRIVILEGE')} here would let any owner grant themselves entitlements — E1's hole,
 * reopened one layer up and with more authority than it had before. {@code provision-tenant} already records
 * this reasoning; this endpoint decides what a customer has PAID for, so it matters more, not less.
 *
 * <h3>Why it ships in E1 rather than waiting for its screen</h3>
 * Two reasons, and the second is a standard. The gate needs a way to withdraw an entitlement through the
 * product's own path rather than a DB write — a fixture that takes a shortcut proves the shortcut works. And
 * <i>a slice is not done until something calls it</i>: shipping the ceiling with no way for an operator to act
 * on it would make E1 the eighth capability in this codebase that works and is unreachable.
 */
@RestController
@RequestMapping("/api/auth/admin")
@RequiredArgsConstructor
public class EntitlementAdminController {

    private final EntitlementService entitlements;
    private final JwtService jwtService;

    /** Every capability for one tenant: in-plan, row status, and the effective answer. */
    @GetMapping("/entitlements")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestParam Long organizationId) {
        return ResponseEntity.ok(ApiResponse.success(entitlements.forOrganization(organizationId), "Entitlements"));
    }

    /**
     * Grant, revoke or time-box one capability for one tenant.
     *
     * <p>Body: {@code organizationId, capability, status, source?, startsAt?, endsAt?, reason?}. Dates are ISO
     * local date-times; absent means unbounded in that direction.
     */
    @PostMapping("/entitlements")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> set(@RequestBody Map<String, Object> body,
                                                 @RequestHeader("Authorization") String auth) {
        // The operator who made the change, for the audit question E4 will ask. Read from the validated token
        // rather than from the body: a caller-supplied actor id is not an actor id.
        Long actor = jwtService.extractUserId(bearer(auth));
        entitlements.set(
                asLong(body.get("organizationId")),
                str(body.get("capability")),
                str(body.get("status")),
                str(body.get("source")),
                asDate(body.get("startsAt")),
                asDate(body.get("endsAt")),
                str(body.get("reason")),
                actor);
        return ResponseEntity.ok(ApiResponse.success(null, "Entitlement saved"));
    }

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

    /**
     * An ISO local date-time, or null.
     *
     * <p>Rejects a malformed date rather than silently treating it as "no end" — an unbounded entitlement is
     * exactly the wrong thing to produce from a typo in an expiry field.
     */
    private static LocalDateTime asDate(Object o) {
        String s = str(o);
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim());
        } catch (RuntimeException bad) {
            throw new IllegalArgumentException("Not a date-time (expected 2026-01-31T00:00:00): " + s);
        }
    }
}
