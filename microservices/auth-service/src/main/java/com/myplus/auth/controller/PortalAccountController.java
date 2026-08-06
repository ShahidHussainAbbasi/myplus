package com.myplus.auth.controller;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.AuthService;

/**
 * Slice 3.1b — service-to-service provisioning of portal sign-in accounts.
 * Design: microservices/docs/slices/edu-3.1b-portal-sign-in.md
 *
 * <p>Called by education-service when a school invites or revokes a guardian's portal access. It is not a
 * user-facing surface: no guardian, and no member of school staff, ever calls it directly — the school's
 * authority was already checked at {@code invitePortalAccess}, which is ADMIN-gated and audited.
 *
 * <h3>Why the internal secret and not a JWT</h3>
 *
 * auth-service authenticates callers by Bearer token, but the caller here is a <b>service</b> acting after
 * an admin's request has already been authorised downstream. The platform's established mechanism for that
 * is {@code X-Internal-Secret} (see {@code HeaderAuthFilter}).
 *
 * <h3>It FAILS CLOSED when no secret is configured</h3>
 *
 * An unconfigured secret means this endpoint refuses every call — it does not mean "open". That is
 * deliberate and worth stating plainly, because the opposite convention exists elsewhere in the platform:
 * {@code HeaderAuthFilter} skips its check when no secret is set, which is safe for a filter that only
 * READS identity, and would be unsafe here, where the operation CREATES a login. A deployment that forgets
 * the secret gets a portal that cannot be provisioned, never one anybody can provision.
 */
@RestController
@RequestMapping("/api/auth/portal")
@RequiredArgsConstructor
public class PortalAccountController {

    private final AuthService authService;

    @Value("${service.internal-secret:}")
    private String internalSecret;

    /** Create or link the account behind an invitation, and send its set-password email. */
    @PostMapping("/account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrLink(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
        if (!trusted(secret)) return refuse();
        Map<String, Object> created = authService.createOrLinkPortalUser(
                str(body.get("email")), toLong(body.get("organizationId")), str(body.get("role")));
        return ResponseEntity.ok(ApiResponse.success(created, "Portal account ready — set-password email sent."));
    }

    /** Withdraw a portal sign-in. The account is disabled, never deleted. */
    @PostMapping("/account/disable")
    public ResponseEntity<ApiResponse<String>> disable(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
        if (!trusted(secret)) return refuse();
        authService.disablePortalUser(str(body.get("email")));
        return ResponseEntity.ok(ApiResponse.success("disabled", "Portal sign-in withdrawn."));
    }

    /** Configured AND matching. An unset secret refuses — see the class javadoc. */
    private boolean trusted(String presented) {
        return internalSecret != null && !internalSecret.isEmpty() && internalSecret.equals(presented);
    }

    /** 404, not 403 — consistent with the portal's own refusals; a prober learns nothing. */
    private <T> ResponseEntity<ApiResponse<T>> refuse() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Not found", HttpStatus.NOT_FOUND.value()));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
