package com.myplus.auth.controller;

import com.myplus.auth.dto.*;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.UserRepository;
import com.myplus.auth.service.AuthService;
import com.myplus.auth.service.TwoFactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.register(request), "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request), "Login successful"));
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
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestBody Map<String, String> body) {
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
}
