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
    private final com.myplus.auth.repository.UserLocationAccessRepository userLocationAccessRepository;

    @Value("${app.verification-token-expiry-hours:24}")
    private int verificationTokenExpiryHours;

    @Value("${app.password-reset-token-expiry-hours:1}")
    private int passwordResetTokenExpiryHours;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_TIME_MINUTES = 30;

    /**
     * The OWNER role (super user of a single company), seeded in SetupDataLoader. Every self-signup /
     * provisioned owner gets this so they hold SUPER privileges within their own tenant — and can later
     * create ADMIN / USER accounts for their company (but not another SUPER) via the user-management form.
     */
    private Role ownerRole() {
        return roleRepository.findByName("ROLE_OWNER")
                .orElseThrow(() -> new ResourceNotFoundException("Owner role (ROLE_OWNER) is not seeded"));
    }

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
        // The self-signup user is the OWNER (super user) of the company they create — give them
        // ROLE_OWNER so every feature of their module's dashboard is available. userType still drives
        // dashboard routing; the role drives privileges. Per-user roles are assigned later by the owner.
        Set<Role> roles = new HashSet<>();
        roles.add(ownerRole());

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

        // Provision the tenant atomically with the user (slice 32): one transaction yields user +
        // organization + OWNER membership. A self-signup org starts on a time-boxed TRIAL.
        Organization org = organizationService.createTenant(
                user, request.getOrganizationName(), userType, "TRIAL");

        VerificationToken vt = VerificationToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(LocalDateTime.now().plusHours(verificationTokenExpiryHours))
                .build();
        verificationTokenRepository.save(vt);
        emailService.sendVerificationEmail(user.getEmail(), vt.getToken());

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        Map<String, Object> claims = buildClaims(user, org);
        String accessToken = jwtService.generateAccessToken(userDetails, claims);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());

        return buildAuthResponse(user, accessToken, refreshToken.getToken());
    }

    /**
     * Operator-only: create a client tenant (owner user + organization) without a redeploy — the
     * replacement for seeding customers in SetupDataLoader. No known password is ever issued; the owner
     * sets their own via the password-reset email. Authorized at the controller (SUPER/ADMIN).
     */
    @Transactional
    public Map<String, Object> provisionTenant(ProvisionTenantRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered");
        }
        String username = request.getEmail().split("@")[0] + "_" + System.currentTimeMillis() % 10000;
        if (userRepository.existsByUsername(username)) {
            username = username + "_" + UUID.randomUUID().toString().substring(0, 4);
        }

        String userType = request.getUserType() != null ? request.getUserType().toUpperCase() : "BUSINESS";
        // Operator-provisioned tenants also get an OWNER (super user of that company).
        Set<Role> roles = new HashSet<>();
        roles.add(ownerRole());

        String plan = (request.getPlan() == null || request.getPlan().isBlank())
                ? "PRO" : request.getPlan().toUpperCase();

        User user = User.builder()
                .username(username)
                .email(request.getEmail())
                // Throwaway secret — the owner sets a real password via the reset email below.
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .userType(userType)
                .enabled(true)
                .accountNonLocked(true)
                .twoFactorEnabled(false)
                .roles(roles)
                .build();
        user = userRepository.save(user);

        Organization org = organizationService.createTenant(user, request.getOrganizationName(), userType, plan);
        // Owner sets their own password via the reset link (no operator-known credential).
        sendPasswordResetEmail(user.getEmail());

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("email", user.getEmail());
        result.put("organizationId", org.getId());
        result.put("organizationName", org.getName());
        result.put("plan", org.getPlan());
        return result;
    }

    /**
     * Owner-only: create a team member (ADMIN or USER — never SUPER/OWNER) inside the caller's OWN
     * organization. The user is created with no known password and receives a reset email to set one.
     * Authorized at the controller (SUPER_PRIVILEGE) + confined to {@code callerOrgId}.
     */
    @Transactional
    public Map<String, Object> createOrgUser(String firstName, String lastName, String email,
                                             String roleChoice, Long callerOrgId, Long callerUserId,
                                             boolean callerIsOwner, java.util.List<Long> storeIds) {
        if (email == null || email.isBlank())
            throw new ValidationException("Email is required");
        if (userRepository.existsByEmail(email))
            throw new DuplicateResourceException("Email already registered");
        String rc = (roleChoice == null ? "USER" : roleChoice.trim().toUpperCase());
        if (!rc.equals("ADMIN") && !rc.equals("USER"))
            throw new ValidationException("Role must be ADMIN or USER");
        // Hierarchy (Pattern A): only an OWNER may create an ADMIN; an ADMIN may create USER members only.
        if (rc.equals("ADMIN") && !callerIsOwner)
            throw new ValidationException("Only an owner can create an admin.");
        // New member inherits the owner's module (userType) so they land on the same dashboard.
        String userType = userRepository.findById(callerUserId)
                .map(User::getUserType).filter(t -> t != null && !t.isBlank())
                .map(String::toUpperCase).orElse("BUSINESS");

        // Location grants for the new member: an ADMIN may grant only locations they hold (never widen scope);
        // an ADMIN with no explicit choice inherits their own. Stores for commerce, schools for education.
        String locModule = moduleFor(userType);
        java.util.List<Long> grantStores =
                sanitizeStoreGrants(storeIds, callerUserId, callerOrgId, callerIsOwner, locModule);
        // Global role drives privileges: ADMIN -> ADMIN_ROLE (admin set); USER -> ROLE_<type>_USER.
        String globalRoleName = rc.equals("ADMIN") ? "ADMIN_ROLE" : ("ROLE_" + userType + "_USER");
        Role role = roleRepository.findByName(globalRoleName)
                .orElseGet(() -> roleRepository.findByName("ROLE_BUSINESS_USER")
                        .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + globalRoleName)));

        String username = email.split("@")[0] + "_" + System.currentTimeMillis() % 10000;
        if (userRepository.existsByUsername(username))
            username = username + "_" + UUID.randomUUID().toString().substring(0, 4);

        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(UUID.randomUUID().toString())) // throwaway; set via reset email
                .firstName(firstName)
                .lastName(lastName)
                .userType(userType)
                .enabled(true)
                .accountNonLocked(true)
                .twoFactorEnabled(false)
                .roles(new HashSet<>(java.util.Collections.singletonList(role)))
                .build();
        user = userRepository.save(user);

        organizationService.addMember(user.getId(), callerOrgId, rc);   // join the caller's org
        grantLocations(user.getId(), callerOrgId, grantStores, rc, locModule);   // store (commerce) / school (education) access
        sendPasswordResetEmail(user.getEmail());                        // user sets their own password

        Map<String, Object> r = new HashMap<>();
        r.put("userId", user.getId());
        r.put("email", user.getEmail());
        r.put("role", rc);
        return r;
    }

    // ── Multi-location store grants (Pattern A) ───────────────────────────────────────────────────

    /**
     * Assign store access to an existing user (owner/admin action). An OWNER may grant any store in the org;
     * an ADMIN may grant only stores they hold. Idempotent per (user, store) — re-granting is a no-op.
     * Used by the Manage Users store picker and by an owner self-assigning to their store(s).
     */
    @Transactional
    public void assignLocations(Long callerUserId, Long callerOrgId, boolean callerIsOwner,
                                Long targetUserId, java.util.List<Long> storeIds, String roleAtLocation) {
        String module = moduleOf(callerUserId);
        java.util.List<Long> stores = sanitizeStoreGrants(storeIds, callerUserId, callerOrgId, callerIsOwner, module);
        String role = (roleAtLocation == null ? "USER" : roleAtLocation.trim().toUpperCase());
        grantLocations(targetUserId, callerOrgId, stores, role, module);
    }

    /**
     * P4 — which location registry a user's grants belong to: EDUCATION grants point at school ids (branches),
     * everything else at business `store` ids. PHARMA/MARKETPLACE deliberately map to BUSINESS: they reuse the
     * commerce core and therefore its stores. Derived from the user's vertical, never taken from the client.
     */
    static String moduleFor(String userType) {
        return "EDUCATION".equalsIgnoreCase(userType) ? "EDUCATION" : "BUSINESS";
    }

    private String moduleOf(Long userId) {
        return moduleFor(userRepository.findById(userId).map(User::getUserType).orElse(null));
    }

    /** Write one ACTIVE grant per location (skips duplicates). */
    private void grantLocations(Long userId, Long orgId, java.util.List<Long> storeIds, String roleAtLocation,
                                String module) {
        for (Long storeId : storeIds) {
            if (!userLocationAccessRepository
                    .findByOrganizationIdAndModuleAndLocationId(orgId, module, storeId).stream()
                    .anyMatch(g -> g.getUserId().equals(userId))) {
                userLocationAccessRepository.save(com.myplus.auth.entity.UserLocationAccess.builder()
                        .userId(userId).organizationId(orgId).module(module)
                        .locationId(storeId).roleAtLocation(roleAtLocation).status("ACTIVE").build());
            }
        }
    }

    /** Locations to grant: owner may grant any (as requested); admin only ones they hold; empty allowed. */
    private java.util.List<Long> sanitizeStoreGrants(java.util.List<Long> requested, Long callerUserId,
                                                     Long callerOrgId, boolean callerIsOwner, String module) {
        if (requested == null || requested.isEmpty())
            return callerIsOwner ? java.util.List.of() : callerStoreIds(callerUserId, callerOrgId, module);
        java.util.List<Long> distinct = requested.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (callerIsOwner) return distinct;
        java.util.Set<Long> allowed = new java.util.HashSet<>(callerStoreIds(callerUserId, callerOrgId, module));
        return distinct.stream().filter(allowed::contains).toList();   // never grant a location the admin lacks
    }

    /** The location ids the given user currently holds in that module. */
    private java.util.List<Long> callerStoreIds(Long userId, Long orgId, String module) {
        return userLocationAccessRepository.findByUserIdAndOrganizationIdAndStatus(userId, orgId, "ACTIVE")
                .stream().filter(g -> module.equals(g.getModule()))
                .map(com.myplus.auth.entity.UserLocationAccess::getLocationId).distinct().toList();
    }

    /** Owner-only: list the team (members) of the caller's organization. */
    public java.util.List<Map<String, Object>> listOrgUsers(Long callerOrgId) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.myplus.auth.entity.Membership m : organizationService.membersOf(callerOrgId)) {
            userRepository.findById(m.getUserId()).ifPresent(u -> {
                Map<String, Object> row = new HashMap<>();
                row.put("userId", u.getId());
                row.put("name", ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                        + (u.getLastName() == null ? "" : u.getLastName())).trim());
                row.put("email", u.getEmail());
                row.put("role", m.getRole());
                row.put("enabled", u.isEnabled());
                out.add(row);
            });
        }
        return out;
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

        // Email-verification gate (slice 32): a registered-but-unverified account cannot log in. Checked
        // after the password match so we never reveal account/verification state without the credential.
        if (!user.isEnabled()) {
            throw new ValidationException("Account not verified. Please check your email to verify your account.");
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
                .demo(user.isDemo())
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
        // domain data has a home (legacy safety net — new signups create the tenant at registration).
        return buildClaims(user, organizationService.getOrCreatePrimaryOrg(user));
    }

    /** Overload used by {@link #switchOrganization}: resolve the org by id, then build claims. */
    private Map<String, Object> buildClaims(User user, Long activeOrgId) {
        return buildClaims(user, organizationService.findById(activeOrgId));
    }

    private Map<String, Object> buildClaims(User user, Organization activeOrg) {
        return buildClaims(user, activeOrg, null);
    }

    private Map<String, Object> buildClaims(User user, Organization activeOrg, Long preferredLocationId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("email", user.getEmail());
        claims.put("roles", new ArrayList<>(CustomUserDetailsService.getRoleNames(user.getRoles())));
        // Privilege-level authorities so privilege-based consumers (the monolith's
        // @PreAuthorize / sec:authorize checks) can rebuild their authority set from the token.
        claims.put("privileges", new ArrayList<>(CustomUserDetailsService.getPrivilegeNames(user.getRoles())));
        // Active tenant the request is scoped to. The gateway copies this into X-Org-Id.
        claims.put("activeOrgId", activeOrg != null ? activeOrg.getId() : null);
        // Tenant entitlement (slice 32): the plan is the source of truth for limits; trialEndsAt time-boxes
        // a TRIAL. The gateway will move from the demo boolean to these without a breaking change.
        if (activeOrg != null) {
            claims.put("plan", activeOrg.getPlan());
            if (activeOrg.getTrialEndsAt() != null) {
                claims.put("trialEndsAt", activeOrg.getTrialEndsAt().toString());
            }
            if (activeOrg.getEntryCap() != null) {
                claims.put("entryCap", activeOrg.getEntryCap());
            }
        }
        // Free-trial demo account: the gateway caps writes (50/module) and the UI shows the upsell.
        claims.put("demo", user.isDemo());
        // Multi-location (Pattern A): the stores/branches this user may access in the active org + the active
        // one + their role there. EMPTY until locations + grants exist (P2+), so services stay single-location.
        addLocationClaims(claims, user, activeOrg, preferredLocationId);
        return claims;
    }

    /**
     * Populate the location claims from {@code user_location_access} for the active org. When the user has
     * exactly one accessible location it is auto-selected as active (single-store convenience); with several,
     * activeLocationId is left null until the UI switches (a later slice). No grants => empty/null => the
     * gateway stamps no location headers and every service behaves exactly as before (single-location).
     */
    private void addLocationClaims(Map<String, Object> claims, User user, Organization activeOrg) {
        addLocationClaims(claims, user, activeOrg, null);
    }

    /** As above, but with an explicit active location (P5b store switcher). {@code preferred} is only honoured
     *  when the user actually holds a grant for it — a client can never widen or fake its own active store. */
    private void addLocationClaims(Map<String, Object> claims, User user, Organization activeOrg, Long preferred) {
        java.util.List<Long> locIds = new java.util.ArrayList<>();
        java.util.Map<Long, String> roleByLoc = new java.util.HashMap<>();
        if (activeOrg != null) {
            // Only this user's own vertical: a school id and a store id are both just numbers, so mixing
            // modules here would hand an education user "access" to a same-numbered store.
            String module = moduleFor(user.getUserType());
            var grants = userLocationAccessRepository
                    .findByUserIdAndOrganizationIdAndStatus(user.getId(), activeOrg.getId(), "ACTIVE");
            var seen = new java.util.LinkedHashSet<Long>();
            for (var g : grants) {
                if (!module.equals(g.getModule())) continue;
                if (seen.add(g.getLocationId())) locIds.add(g.getLocationId());
                roleByLoc.putIfAbsent(g.getLocationId(), g.getRoleAtLocation());
            }
        }
        // Active store: the one explicitly switched to, else the only one they hold (single-store convenience),
        // else none — with several stores and no choice made, writes stay unstamped until the user picks one.
        Long active = (preferred != null && locIds.contains(preferred)) ? preferred
                : (locIds.size() == 1 ? locIds.get(0) : null);
        claims.put("accessibleLocationIds", locIds);
        claims.put("activeLocationId", active);
        claims.put("roleAtLocation", active != null ? roleByLoc.get(active) : null);
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

    /**
     * P5b — re-issue tokens with {@code locationId} as the ACTIVE store, the location twin of
     * {@link #switchOrganization}. A user holding several stores has no active one until they choose (so a
     * write is never silently attributed to the wrong store); this is how they choose. The grant is verified
     * server-side, so a client cannot switch itself into a store it was never given.
     */
    @Transactional
    public AuthResponse switchLocation(Long userId, Long orgId, Long locationId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ValidationException("Invalid user"));
        if (orgId == null || !organizationService.isMember(userId, orgId)) {
            throw new ValidationException("Not a member of the active organization");
        }
        String module = moduleFor(user.getUserType());
        boolean holdsGrant = userLocationAccessRepository
                .findByUserIdAndOrganizationIdAndStatus(userId, orgId, "ACTIVE").stream()
                .filter(g -> module.equals(g.getModule()))
                .anyMatch(g -> g.getLocationId() != null && g.getLocationId().equals(locationId));
        if (!holdsGrant) {
            throw new ValidationException("You do not have access to that store.");
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails,
                buildClaims(user, organizationService.findById(orgId), locationId));
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());
        return buildAuthResponse(user, accessToken, refreshToken.getToken());
    }

    /** The caller's location grants in the active org (id + role) — feeds the store/branch switcher. */
    public java.util.List<Map<String, Object>> myLocations(Long userId, Long orgId) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (orgId == null) return out;
        String module = moduleOf(userId);
        for (var g : userLocationAccessRepository.findByUserIdAndOrganizationIdAndStatus(userId, orgId, "ACTIVE")) {
            if (!module.equals(g.getModule())) continue;
            Map<String, Object> row = new HashMap<>();
            row.put("locationId", g.getLocationId());
            row.put("roleAtLocation", g.getRoleAtLocation());
            row.put("module", g.getModule());
            out.add(row);
        }
        return out;
    }
}
