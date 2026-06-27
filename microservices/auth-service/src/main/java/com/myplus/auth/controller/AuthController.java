package com.myplus.auth.controller;

import com.myplus.auth.dto.*;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.UserRepository;
import com.myplus.auth.service.AuthService;
import com.myplus.auth.service.TwoFactorService;
import com.myplus.common.captcha.CaptchaVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TwoFactorService twoFactorService;
    private final UserRepository userRepository;
    private final CaptchaVerifier captchaVerifier;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request,
            @RequestHeader(value = "g-recaptcha-response", required = false) String captchaToken,
            HttpServletRequest httpRequest) {
        verifyCaptcha(captchaToken, httpRequest);
        return ResponseEntity.ok(ApiResponse.success(authService.register(request), "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "g-recaptcha-response", required = false) String captchaToken,
            HttpServletRequest httpRequest) {
        verifyCaptcha(captchaToken, httpRequest);
        return ResponseEntity.ok(ApiResponse.success(authService.login(request), "Login successful"));
    }

    // Platform-operator onboarding: create a client tenant without a redeploy — the SaaS replacement
    // for seeding customers. The owner receives a password-reset email to set their own credential.
    // Gated on the platform ROLE_ADMIN *role* (not the SUPER/ADMIN privilege): company owners now hold
    // SUPER privileges within their own tenant (ROLE_OWNER), so a privilege gate would let any owner
    // create new tenants. ROLE_ADMIN keeps this to the platform operator (admin@myplus.com).
    @PostMapping("/admin/provision-tenant")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> provisionTenant(@Valid @RequestBody ProvisionTenantRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.provisionTenant(request), "Tenant provisioned"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.refreshToken(request), "Token refreshed"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow();
        authService.logout(user.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Logged out"));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.success(null, "Email verified"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestBody Map<String, String> body,
            @RequestHeader(value = "g-recaptcha-response", required = false) String captchaToken,
            HttpServletRequest httpRequest) {
        verifyCaptcha(captchaToken, httpRequest);
        authService.sendPasswordResetEmail(body.get("email"));
        return ResponseEntity.ok(ApiResponse.success(null, "Password reset email sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Password reset successfully"));
    }

    @PostMapping("/2fa/setup")
    public ResponseEntity<ApiResponse<Map<String, String>>> setup2fa(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        String qrUrl = twoFactorService.enableTwoFactor(user.getId());
        Map<String, String> response = new HashMap<>();
        response.put("qrUrl", qrUrl);
        return ResponseEntity.ok(ApiResponse.success(response, "2FA setup initiated"));
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> verify2fa(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean ok = twoFactorService.verifyCode(user.getTwoFactorSecret(), body.get("code"));
        return ResponseEntity.ok(ApiResponse.success(Map.of("verified", ok), ok ? "Verified" : "Invalid code"));
    }

    @DeleteMapping("/2fa/disable")
    public ResponseEntity<ApiResponse<Void>> disable2fa(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        twoFactorService.disableTwoFactor(user.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "2FA disabled"));
    }

    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        return ResponseEntity.ok(ApiResponse.success(authService.validateToken(token)));
    }

    // Captcha enforcement at the IdP (slice 33, Phase 9 step 2e). When app.captcha.enabled=true the verifier
    // REQUIRES a valid token (a blank/absent g-recaptcha-response is rejected), so enabling captcha actually
    // enforces it for every client — closing the "submit without solving" bypass. When disabled it is a
    // no-op. Keep this flag in sync with the monolith's app.captcha.enabled, since the monolith only forwards
    // a token when its own captcha is on.
    private void verifyCaptcha(String captchaToken, HttpServletRequest request) {
        captchaVerifier.verify(captchaToken, clientIp(request));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff == null || xff.isBlank()) ? request.getRemoteAddr() : xff.split(",")[0].trim();
    }
}
