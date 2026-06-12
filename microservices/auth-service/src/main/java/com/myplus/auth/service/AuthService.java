package com.myplus.auth.service;

import com.myplus.auth.dto.*;
import com.myplus.auth.entity.*;
import com.myplus.auth.exception.DuplicateResourceException;
import com.myplus.auth.exception.ResourceNotFoundException;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.*;
import com.myplus.auth.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TwoFactorService twoFactorService;
    private final EmailService emailService;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final OrganizationService organizationService;

    @Value("${app.verification-token-expiry-hours:24}")
    private int verificationTokenExpiryHours;

    @Value("${app.password-reset-token-expiry-hours:1}")
    private int passwordResetTokenExpiryHours;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_TIME_MINUTES = 30;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered");
        }
        String username = request.getEmail().split("@")[0] + "_" + System.currentTimeMillis() % 10000;
        if (userRepository.existsByUsername(username)) {
            username = username + "_" + UUID.randomUUID().toString().substring(0, 4);
        }

        String userType = request.getUserType() != null ? request.getUserType().toUpperCase() : "BUSINESS";
        String roleName = "ROLE_" + userType + "_USER";
        Role defaultRole = roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.findByName("ROLE_BUSINESS_USER")
                        .orElseThrow(() -> new ResourceNotFoundException("Default role not found")));

        Set<Role> roles = new HashSet<>();
        roles.add(defaultRole);

        User user = User.builder()
                .username(username)
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .userType(userType)
                .enabled(false)
                .accountNonLocked(true)
                .twoFactorEnabled(false)
                .roles(roles)
                .build();
        user = userRepository.save(user);

        VerificationToken vt = VerificationToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(LocalDateTime.now().plusHours(verificationTokenExpiryHours))
                .build();
        verificationTokenRepository.save(vt);
        emailService.sendVerificationEmail(user.getEmail(), vt.getToken());

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        Map<String, Object> claims = buildClaims(user);
        String accessToken = jwtService.generateAccessToken(userDetails, claims);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());

        return buildAuthResponse(user, accessToken, refreshToken.getToken());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ValidationException("Invalid credentials"));

        if (!user.isAccountNonLocked()) {
            if (user.getLockTime() != null && user.getLockTime().plusMinutes(LOCK_TIME_MINUTES).isAfter(LocalDateTime.now())) {
                throw new ValidationException("Account is locked. Try again later.");
            }
            user.setAccountNonLocked(true);
            user.setFailedLoginAttempts(0);
            user.setLockTime(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setAccountNonLocked(false);
                user.setLockTime(LocalDateTime.now());
            }
            userRepository.save(user);
            throw new ValidationException("Invalid credentials");
        }

        if (user.isTwoFactorEnabled()) {
            if (request.getTwoFactorCode() == null || request.getTwoFactorCode().isBlank()) {
                return AuthResponse.builder()
                        .userId(user.getId())
                        .email(user.getEmail())
                        .twoFactorRequired(true)
                        .build();
            }
            if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), request.getTwoFactorCode())) {
                throw new ValidationException("Invalid 2FA code");
            }
        }

        user.setFailedLoginAttempts(0);
        userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        Map<String, Object> claims = buildClaims(user);
        String accessToken = jwtService.generateAccessToken(userDetails, claims);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());

        return buildAuthResponse(user, accessToken, refreshToken.getToken());
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken token = refreshTokenService.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new ValidationException("Invalid refresh token"));
        refreshTokenService.verifyExpiration(token);
        User user = token.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails, buildClaims(user));

        return buildAuthResponse(user, accessToken, token.getToken());
    }

    /** Build the standard login/refresh response, including roles + flattened privileges (Model A). */
    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .userType(user.getUserType())
                .roles(CustomUserDetailsService.getRoleNames(user.getRoles()))
                .privileges(CustomUserDetailsService.getPrivilegeNames(user.getRoles()))
                .twoFactorRequired(false)
                .build();
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenService.deleteByUserId(userId);
    }

    @Transactional
    public void verifyEmail(String token) {
        VerificationToken vt = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new ValidationException("Invalid verification token"));
        if (vt.getExpiryDate().isBefore(LocalDateTime.now())) {
            verificationTokenRepository.delete(vt);
            throw new ValidationException("Verification token expired");
        }
        User user = vt.getUser();
        user.setEnabled(true);
        userRepository.save(user);
        verificationTokenRepository.delete(vt);
    }

    @Transactional
    public void sendPasswordResetEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        PasswordResetToken prt = PasswordResetToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(LocalDateTime.now().plusHours(passwordResetTokenExpiryHours))
                .build();
        passwordResetTokenRepository.save(prt);
        emailService.sendPasswordResetEmail(email, prt.getToken());
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken prt = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new ValidationException("Invalid reset token"));
        if (prt.getExpiryDate().isBefore(LocalDateTime.now())) {
            passwordResetTokenRepository.delete(prt);
            throw new ValidationException("Reset token expired");
        }
        User user = prt.getUser();
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        passwordResetTokenRepository.delete(prt);
    }

    public Map<String, Object> validateToken(String token) {
        Map<String, Object> result = new HashMap<>();
        try {
            String email = jwtService.extractUsername(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            boolean valid = jwtService.validateToken(token, userDetails);
            result.put("valid", valid);
            if (valid) {
                result.put("email", email);
                result.put("userId", jwtService.extractUserId(token));
                result.put("roles", jwtService.extractRoles(token));
            }
        } catch (Exception ex) {
            result.put("valid", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildClaims(User user) {
        // Default active tenant: the user's primary org ("tenant #1"), auto-created on first login so
        // domain data has a home once domains move from userId- to org-scoping.
        return buildClaims(user, organizationService.getOrCreatePrimaryOrg(user).getId());
    }

    private Map<String, Object> buildClaims(User user, Long activeOrgId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("email", user.getEmail());
        claims.put("roles", new ArrayList<>(CustomUserDetailsService.getRoleNames(user.getRoles())));
        // Privilege-level authorities so privilege-based consumers (the monolith's
        // @PreAuthorize / sec:authorize checks) can rebuild their authority set from the token.
        claims.put("privileges", new ArrayList<>(CustomUserDetailsService.getPrivilegeNames(user.getRoles())));
        // Active tenant the request is scoped to. The gateway copies this into X-Org-Id.
        claims.put("activeOrgId", activeOrgId);
        // Free-trial demo account: the gateway caps writes (50/module) and the UI shows the upsell.
        claims.put("demo", user.isDemo());
        return claims;
    }

    /**
     * Re-issue tokens for {@code userId} scoped to {@code orgId}. Validates the user is a member of the
     * target org first (so a client can never widen its own scope) — the heart of safe org switching.
     */
    @Transactional
    public AuthResponse switchOrganization(Long userId, Long orgId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ValidationException("Invalid user"));
        if (!organizationService.isMember(userId, orgId)) {
            throw new ValidationException("Not a member of the requested organization");
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails, buildClaims(user, orgId));
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());
        return buildAuthResponse(user, accessToken, refreshToken.getToken());
    }
}
