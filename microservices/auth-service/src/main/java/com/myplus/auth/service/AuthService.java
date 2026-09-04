package com.myplus.auth.service;

import com.myplus.auth.dto.*;
import com.myplus.auth.entity.*;
import com.myplus.auth.exception.DuplicateResourceException;
import com.myplus.auth.exception.ResourceNotFoundException;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.*;
import com.myplus.auth.security.CustomUserDetailsService;
import com.myplus.common.settings.OrganizationStatus;
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
@lombok.extern.slf4j.Slf4j
public class AuthService {

    /**
     * C3c — resolves the tenant's capabilities while minting a token.
     *
     * <p>Field-injected rather than a constructor argument so the several tests that build this service
     * directly keep their argument lists; {@code @Autowired} without {@code required = false} so a real
     * context that cannot supply it fails at startup instead of quietly minting tokens with no capability
     * claim for the rest of the deployment's life.
     *
     * <p>The {@code != null} check at the call site therefore guards only direct construction in tests, not a
     * missing bean in production.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.myplus.common.settings.CapabilityService capabilityService;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    /**
     * E5 — the operator's open support sessions, resolved once per token mint.
     *
     * <p>{@code ObjectProvider} rather than a plain field so this class keeps booting if the bean is ever
     * unavailable during startup ordering; the claim is simply absent, which resolves to "no session" and is
     * the safe answer. A support scope that failed OPEN would be the entire slice undone.
     */
    private final org.springframework.beans.factory.ObjectProvider<SupportSessionService> supportSessions;
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

        return buildAuthResponse(user, accessToken, refreshToken.getToken(), claims);
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

        /*
         * ONB-1 — the business type, validated against the Shape enum HERE and not left to byCode.
         *
         * `Shape.byCode` falls back permissively to GENERAL, which is right for a READ — an unreadable stored
         * value must never strip a working tenant's screens. It is wrong at this write: it would turn an
         * operator's typo into "show this customer the entire product", which is precisely the defect this
         * slice closes. So an unrecognised value is refused, not guessed.
         */
        com.myplus.common.settings.Shape shape = null;
        String requestedShape = request.getShape() == null ? null : request.getShape().trim();
        if (requestedShape != null && !requestedShape.isEmpty()) {
            for (com.myplus.common.settings.Shape candidate : com.myplus.common.settings.Shape.values()) {
                if (candidate.code().equalsIgnoreCase(requestedShape)) shape = candidate;
            }
        }
        if (shape == null) {
            throw new ValidationException("Choose a business type for this tenant: "
                    + java.util.Arrays.stream(com.myplus.common.settings.Shape.values())
                            .map(com.myplus.common.settings.Shape::code)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }

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

        Organization org = organizationService.createTenant(
                user, request.getOrganizationName(), userType, plan, shape.code());
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
        // B2B P0.5: the ORG being joined decides which of those it is — an admin whose own type is BUSINESS
        // adding a member to a school must grant school branches, not same-numbered stores.
        String locModule = moduleForOrg(
                callerOrgId == null ? null : organizationService.findById(callerOrgId), userType);
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

    // ── Slice 3.1b: portal accounts (guardians, later students) ───────────────────────────────────

    /**
     * Create — or LINK — the sign-in account behind a portal invitation.
     *
     * <p>Deliberately separate from {@link #createOrgUser}: a portal user is not a team member. They get
     * {@code ROLE_PORTAL} (LOGIN + CHANGE_PASSWORD only), <b>no location grants</b> (a guardian belongs to no
     * branch — their reach is defined by which children are theirs, resolved per request), and a membership
     * role naming what they are ({@code GUARDIAN}, later {@code STUDENT}).
     *
     * <p><b>Create or link, never fail.</b> One adult can be a guardian at two schools on this platform, and
     * they are one person with one login. An existing email therefore gains a membership in this
     * organisation rather than being refused as a duplicate — which is also why this cannot reuse
     * {@code createOrgUser}, whose {@code existsByEmail} check throws.
     *
     * <p><b>They cannot sign in yet, and that is the point (design D5).</b> The password is a throwaway
     * random value nobody knows — not even the school — so the ONLY way in is the emailed set-password
     * token. That makes the invitation double as address verification: a typo'd address never becomes a
     * working account, it simply never gets used. This pays 3.1 §6's carried requirement that
     * {@code Guardian.email} is unverified free text.
     *
     * <p>Returns the userId so the caller can record which account its invitation created.
     */
    @Transactional
    public Map<String, Object> createOrLinkPortalUser(String email, Long orgId, String membershipRole) {
        if (email == null || email.isBlank()) throw new ValidationException("Email is required");
        if (orgId == null) throw new ValidationException("Organization is required");
        String addr = email.trim();
        String memberRole = (membershipRole == null || membershipRole.isBlank())
                ? "GUARDIAN" : membershipRole.trim().toUpperCase();

        // Slice 3.3 — the security role follows the MEMBERSHIP role, so a student gets ROLE_STUDENT and a
        // guardian ROLE_GUARDIAN. Both are listed in education's `myplus.portal.confined-roles`, so both are
        // confined to /portal/**.
        //
        // ⚠ ALLOWLISTED, not derived by string concatenation. "ROLE_" + memberRole would happily mint
        // ROLE_TEACHER, or ROLE_ANYTHING, from a caller-supplied value — and an unrecognised role is not in
        // confined-roles, which means UNCONFINED. That is the fail-OPEN direction, so an unknown value must
        // be refused here rather than resolved into a role nobody confines.
        String roleName = switch (memberRole) {
            case "GUARDIAN" -> "ROLE_GUARDIAN";
            case "STUDENT"  -> "ROLE_STUDENT";
            default -> throw new ValidationException(
                    "Unsupported portal role: " + memberRole + " (expected GUARDIAN or STUDENT)");
        };
        Role portalRole = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleName));

        User user = userRepository.findByEmail(addr).orElse(null);
        boolean created = false;
        if (user == null) {
            String username = addr.split("@")[0] + "_" + System.currentTimeMillis() % 10000;
            if (userRepository.existsByUsername(username)) {
                username = username + "_" + UUID.randomUUID().toString().substring(0, 4);
            }
            user = userRepository.save(User.builder()
                    .username(username)
                    .email(addr)
                    // Throwaway: the set-password email is the only way in. See the javadoc above.
                    .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .userType("EDUCATION")
                    .enabled(true)
                    .accountNonLocked(true)
                    .twoFactorEnabled(false)
                    .roles(new HashSet<>(java.util.Collections.singletonList(portalRole)))
                    .build());
            created = true;
        } else {
            // An existing account gains the portal role; it never LOSES the roles it already had, because
            // the same person may legitimately be staff at one school and a guardian at another.
            user.getRoles().add(portalRole);
            user.setEnabled(true);
            userRepository.save(user);
        }

        organizationService.addMember(user.getId(), orgId, memberRole);   // idempotent
        sendPasswordResetEmail(user.getEmail());                          // set-password = verification

        Map<String, Object> r = new HashMap<>();
        r.put("userId", user.getId());
        r.put("email", user.getEmail());
        r.put("created", created);
        return r;
    }

    /**
     * Withdraw a portal sign-in (revoke).
     *
     * <p>Disables the account so the credential stops working immediately. The {@code user} row and the
     * membership are KEPT — "who used to be able to read this child's record" is exactly what an
     * investigation needs, and it is the same rule 3.1 applied to {@code GuardianPortalAccess} (REVOKED,
     * never deleted) and 1.5/1.6 applied to superseded cards and reversed promotions.
     *
     * <p>Silently succeeds when there is no such account: revoke must be safe to call for a guardian who
     * was invited before accounts existed, or whose invitation was never taken up.
     */
    @Transactional
    public void disablePortalUser(String email) {
        if (email == null || email.isBlank()) return;
        userRepository.findByEmail(email.trim()).ifPresent(u -> {
            u.setEnabled(false);
            userRepository.save(u);
        });
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
        assignLocations(callerUserId, callerOrgId, callerIsOwner, targetUserId, storeIds, roleAtLocation, false);
    }

    /**
     * As above, but {@code replace=true} makes the request the member's COMPLETE set of locations: locations
     * missing from it are REVOKED. Without this the screen could only ever add — an owner had no way to move
     * someone from Store A to Store B, or to take access away at all, which made "assign" a one-way door.
     *
     * <p>Revocation respects the same authority as granting: an OWNER may revoke anything, an ADMIN only the
     * locations they themselves hold — so an admin can never strip a member of a store they do not manage.
     */
    @Transactional
    public void assignLocations(Long callerUserId, Long callerOrgId, boolean callerIsOwner,
                                Long targetUserId, java.util.List<Long> storeIds, String roleAtLocation,
                                boolean replace) {
        String module = moduleOf(callerUserId, callerOrgId);   // B2B P0.5: the org being worked in decides
        // In REPLACE mode an empty list means "none" — it is the member's complete set. The additive path's
        // "empty ⇒ inherit the caller's own locations" default would otherwise turn a clear into a re-grant.
        java.util.List<Long> desired = replace
                ? allowedStoreGrants(storeIds, callerUserId, callerOrgId, callerIsOwner, module)
                : sanitizeStoreGrants(storeIds, callerUserId, callerOrgId, callerIsOwner, module);
        String role = (roleAtLocation == null ? "USER" : roleAtLocation.trim().toUpperCase());

        if (replace) {
            java.util.Set<Long> keep = new java.util.HashSet<>(desired);
            // What the caller is ALLOWED to take away: everything (owner) or only their own locations (admin).
            java.util.Set<Long> revocable = callerIsOwner ? null
                    : new java.util.HashSet<>(callerStoreIds(callerUserId, callerOrgId, module));
            for (var g : userLocationAccessRepository
                    .findByUserIdAndOrganizationIdAndStatus(targetUserId, callerOrgId, "ACTIVE")) {
                if (!module.equals(g.getModule())) continue;          // never touch another vertical's grants
                if (keep.contains(g.getLocationId())) continue;       // still wanted
                if (revocable != null && !revocable.contains(g.getLocationId())) continue;  // not the admin's to remove
                userLocationAccessRepository.delete(g);
            }
        }
        grantLocations(targetUserId, callerOrgId, desired, role, module);
    }

    /**
     * P4 — which location registry a user's grants belong to: EDUCATION grants point at school ids (branches),
     * everything else at business `store` ids. PHARMA/MARKETPLACE deliberately map to BUSINESS: they reuse the
     * commerce core and therefore its stores. Derived from the user's vertical, never taken from the client.
     */
    static String moduleFor(String userType) {
        return "EDUCATION".equalsIgnoreCase(userType) ? "EDUCATION" : "BUSINESS";
    }

    /**
     * B2B P0.5 — the location module for the tenant the user is actually working in.
     *
     * <p>Resolution order is the platform-wide one: the ACTIVE ORG's type, then the user's own type when the
     * org has none (every tenant created before {@code Organization.type} was populated). Keeping the fallback
     * is what makes this change invisible to existing tenants.
     */
    static String moduleForOrg(Organization activeOrg, String userType) {
        String orgType = (activeOrg != null) ? activeOrg.getType() : null;
        return moduleFor((orgType != null && !orgType.isBlank()) ? orgType : userType);
    }

    /**
     * B2B P0.5 — the module for a user AS THEY ARE WORKING IN {@code orgId}. Prefer this everywhere an org
     * is in scope: once one login can be active in another module's org, the person's own type stops being
     * a reliable answer to "which module's locations are we talking about".
     */
    private String moduleOf(Long userId, Long orgId) {
        return moduleForOrg(orgId == null ? null : organizationService.findById(orgId),
                userRepository.findById(userId).map(User::getUserType).orElse(null));
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

    /** Locations to grant: owner may grant any (as requested); admin only ones they hold; empty ⇒ an admin
     *  inherits their own locations (the create-a-member default), an owner grants none. */
    private java.util.List<Long> sanitizeStoreGrants(java.util.List<Long> requested, Long callerUserId,
                                                     Long callerOrgId, boolean callerIsOwner, String module) {
        if (requested == null || requested.isEmpty())
            return callerIsOwner ? java.util.List.of() : callerStoreIds(callerUserId, callerOrgId, module);
        return allowedStoreGrants(requested, callerUserId, callerOrgId, callerIsOwner, module);
    }

    /** Exactly what was asked for, minus anything the caller has no authority to grant. No inherit default —
     *  an empty request stays empty, which is what REPLACE mode needs to express "revoke everything". */
    private java.util.List<Long> allowedStoreGrants(java.util.List<Long> requested, Long callerUserId,
                                                    Long callerOrgId, boolean callerIsOwner, String module) {
        if (requested == null) return java.util.List.of();
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

    /** The team (members) of the caller's organization — owner OR admin (both manage people).
     *  Each row carries the member's CURRENT location grants, so the screen can show who works where and
     *  pre-fill the picker when reassigning them; without this, assignment was a one-way door. */
    public java.util.List<Map<String, Object>> listOrgUsers(Long callerOrgId) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        // B2B P0.5: the module is a property of the ORG being listed, so resolve it ONCE here rather than
        // per member from each person's own type — in a school, every row's grants are school branches even
        // for a member whose userType says BUSINESS. (Also one findById instead of one per row.)
        final Organization listOrg = (callerOrgId == null) ? null : organizationService.findById(callerOrgId);
        for (com.myplus.auth.entity.Membership m : organizationService.membersOf(callerOrgId)) {
            userRepository.findById(m.getUserId()).ifPresent(u -> {
                Map<String, Object> row = new HashMap<>();
                row.put("userId", u.getId());
                row.put("name", ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                        + (u.getLastName() == null ? "" : u.getLastName())).trim());
                row.put("email", u.getEmail());
                row.put("role", m.getRole());
                row.put("enabled", u.isEnabled());
                String module = moduleForOrg(listOrg, u.getUserType());
                row.put("locationIds", userLocationAccessRepository
                        .findByUserIdAndOrganizationIdAndStatus(u.getId(), callerOrgId, "ACTIVE").stream()
                        .filter(g -> module.equals(g.getModule()))
                        .map(com.myplus.auth.entity.UserLocationAccess::getLocationId)
                        .distinct().toList());
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

        return buildAuthResponse(user, accessToken, refreshToken.getToken(), claims);
    }

    /**
     * E5 — a fresh access token for a user whose SCOPE has just changed, without a refresh-token round trip.
     *
     * <h3>Why the open-session endpoint must hand one back</h3>
     * The support scope is a claim, so opening a session does nothing at all for the token the operator is
     * already holding: they would open a session, click into the customer, and be answered about their own
     * organization — a wrong number under the customer's name, which is the failure ONB-3 and E4 both hit
     * and the hardest kind to notice.
     *
     * <p>Reuses {@link #buildClaims} rather than adding the claim by hand, so a token minted here can never
     * disagree with one minted by login about anything else the claims carry.
     *
     * @return a signed access token carrying the caller's claims as they stand right now
     */
    @Transactional(readOnly = true)
    public String mintAccessTokenFor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ValidationException("No such user"));
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        return jwtService.generateAccessToken(userDetails, buildClaims(user));
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken token = refreshTokenService.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new ValidationException("Invalid refresh token"));
        refreshTokenService.verifyExpiration(token);
        User user = token.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        Map<String, Object> claims = buildClaims(user);
        String accessToken = jwtService.generateAccessToken(userDetails, claims);

        return buildAuthResponse(user, accessToken, token.getToken(), claims);
    }

    /**
     * Build the standard login/refresh response, including roles + flattened privileges (Model A).
     *
     * <p>B2B P0.5: takes the claims that were just minted rather than re-deriving anything, so the response
     * body and the token it accompanies can never disagree about the active tenant. Clients that build a
     * principal from the response (the monolith does — see {@code AuthServerAuthenticationProvider}) then see
     * exactly what the token says.
     */
    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken,
                                           Map<String, Object> claims) {
        Object orgType = (claims != null) ? claims.get("activeOrgType") : null;
        return buildAuthResponseBuilder(user, accessToken, refreshToken)
                .activeOrgType(orgType != null ? orgType.toString() : null)
                .build();
    }

    private AuthResponse.AuthResponseBuilder buildAuthResponseBuilder(User user, String accessToken,
                                                                      String refreshToken) {
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
                .demo(user.isDemo());
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

    /**
     * E3 — refuse a token to a tenant that is not trading.
     *
     * <h3>Why the check lives HERE and not in login()</h3>
     * Every way a session begins or continues converges on this method:
     * <pre>
     *   login()              → buildClaims(user)     → buildClaims(user, primaryOrg)
     *   refreshToken()       → buildClaims(user)     → buildClaims(user, primaryOrg)
     *   switchOrganization() → buildClaims(user, id) → buildClaims(user, org)
     *   register()           → buildClaims(user, newOrg)
     * </pre>
     * One guard covers all four <b>and any fifth path added later</b>, which is the property that matters:
     * three copies drift, and the next caller forgets. A new session path inherits this by construction
     * rather than by review.
     *
     * <h3>Why this needs no per-request check anywhere</h3>
     * Refusing at REFRESH is what does the work. An already-open session simply fails to renew, so it dies
     * within the access-token lifetime ({@code jwt.access-token-expiration-ms}, 15 minutes) — at zero cost on
     * any hot path and with no new remote call. That bound is the one the platform already lives with for
     * capabilities, so this adds no new class of staleness.
     *
     * <h3>ROLE_ADMIN is exempt, and it is not a convenience</h3>
     * The operator's own organization must never be able to lock the operator out of the console that would
     * undo the suspension. Google Workspace exempts super-admins for the same reason. This is the second of
     * two independent guards — {@code OrganizationAdminService.changeStatus} also refuses an operator
     * suspending their own tenant — because a foot-gun with no undo deserves both.
     *
     * <h3>Checked AFTER the password, by construction</h3>
     * {@code login()} verifies credentials before it reaches any {@code buildClaims} call, so a suspended
     * tenant's status can never be used to enumerate accounts. Same ordering the existing
     * email-verification gate already relies on.
     *
     * <h3>The message names the way back</h3>
     * MaxTheService has no in-product billing, so unlike a Shopify "frozen" store there is no payment screen
     * to keep reachable — the sentence IS the remediation path. An owner told only "invalid credentials" has
     * no idea they need to contact anybody.
     */
    private void assertTenantMaySignIn(User user, Organization activeOrg) {
        if (activeOrg == null) return;   // no tenant resolved; nothing to refuse, and other paths handle it

        OrganizationStatus status = OrganizationStatus.byCode(activeOrg.getStatus());
        if (status.allowsSignIn()) return;

        if (isPlatformOperator(user)) return;

        throw new ValidationException(status == OrganizationStatus.CLOSED
                ? "This account has been closed. Please contact MaxTheService if this is unexpected."
                : "This account is suspended. Please contact MaxTheService to restore access.");
    }

    /**
     * Is this the MaxTheService operator, rather than a customer?
     *
     * <p>Keys on the platform {@code ROLE_ADMIN} <b>role</b> and never on {@code ADMIN_PRIVILEGE}: every
     * tenant owner holds that privilege inside their own organization, so a privilege check here would exempt
     * every customer from suspension — turning the lever off for precisely the people it exists for.
     */
    private boolean isPlatformOperator(User user) {
        return user != null && user.getRoles() != null
                && user.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r.getName()));
    }

    private Map<String, Object> buildClaims(User user, Organization activeOrg, Long preferredLocationId) {
        // E3 — the tenant-lifecycle guard, at THE choke point. See assertTenantMaySignIn.
        assertTenantMaySignIn(user, activeOrg);
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("email", user.getEmail());
        claims.put("roles", new ArrayList<>(CustomUserDetailsService.getRoleNames(user.getRoles())));
        // Privilege-level authorities so privilege-based consumers (the monolith's
        // @PreAuthorize / sec:authorize checks) can rebuild their authority set from the token.
        claims.put("privileges", new ArrayList<>(CustomUserDetailsService.getPrivilegeNames(user.getRoles())));
        // Active tenant the request is scoped to. The gateway copies this into X-Org-Id.
        claims.put("activeOrgId", activeOrg != null ? activeOrg.getId() : null);
        // B2B P0.5: which MODULE that tenant is (BUSINESS/PHARMA/MARKETPLACE/EDUCATION/WELFARE/AGRICULTURE/
        // APPOINTMENT) — the same vocabulary as User.userType, deliberately, so the two can never drift into
        // separate dialects. This is what lets ONE login reach every module: routing follows the org the user
        // is working in, not the single type stamped on the person. NULL for tenants created before the column
        // was populated; every consumer falls back to userType, which is exactly today's behaviour.
        claims.put("activeOrgType", activeOrg != null ? activeOrg.getType() : null);
        /*
         * E5 — the SUPPORT SCOPE.
         *
         * Before this, `CurrentUser.organizationIdFor` asked "are you ROLE_ADMIN?" and a yes reached every
         * tenant for ever. It now asks whether an OPEN SESSION names the tenant, and this is how that answer
         * travels — the same mechanism as `caps` (C3c), for the same reason: resolved once at the door, so no
         * service acquires a request-path dependency on auth-service.
         *
         * ⚠ Only the FIRST open session is carried. An operator supporting two customers at once has two
         * sessions, and the claim names the newest; the console opens one at a time and closing is one click.
         * Carrying a list would let a single token reach several tenants at once, which is the standing grant
         * in a smaller costume.
         *
         * ⚠ `supportUntil` is carried so a callee can answer "is this still valid?" without calling back. Its
         * cost is stated in the design's §2: a session CLOSED early stays usable until the token refreshes,
         * inside the 15-minute access-token life. Sessions are short by default so expiry, not closure, is the
         * normal ending — and every access is recorded either way.
         */
        try {
            SupportSessionService sessionService = supportSessions.getIfAvailable();
            if (sessionService != null) {
                java.util.List<com.myplus.auth.entity.SupportSession> open =
                        sessionService.openFor(user.getId());
                if (!open.isEmpty()) {
                    com.myplus.auth.entity.SupportSession s = open.get(0);
                    claims.put("supportOrg", s.getSubjectOrgId());
                    claims.put("supportUntil", s.getExpiresAt().toString());
                    // The customer's consent for WRITES (D-2), carried separately: an operator may look at a
                    // shop's figures to answer their question without being able to change their records.
                    claims.put("supportWrite", s.isWriteApproved());
                }
            }
        } catch (RuntimeException scopeUnavailable) {
            // A support scope that cannot be resolved is ABSENT, never assumed. Failing open here would hand
            // back the standing grant this slice exists to remove, silently, on exactly the deployment where
            // something else was already wrong.
            log.warn("Support scope could not be resolved for user {}; the token carries none.",
                    user.getId(), scopeUnavailable);
        }
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
        /*
         * C3c — the tenant's CAPABILITIES, resolved once here and carried to every service.
         *
         * Why the token rather than a lookup in each service: org_setting is per-SERVICE but a capability is
         * per-TENANT, so the old arrangement gave N answers to one question. An owner switched rxRequired off,
         * the row landed in business-service's table, pharma read its own, found nothing, defaulted to ON and
         * never refused — correct code that could not fire.
         *
         * And it must not become a remote call. V44 already settled that argument for the sale path: asking
         * another service mid-sale "would fail OPEN the moment it is slow or down — the guarantee would be
         * worth nothing precisely when the shop is busiest". Resolving at mint costs one cached read per login.
         *
         * The honest cost is STALENESS: switching a capability off takes effect at the tenant's next login (or
         * token refresh). Acceptable for a switch an owner touches during onboarding, and the trade was made
         * deliberately rather than discovered.
         *
         * Fails OPEN by OMITTING the claim: a downstream service that receives no claim falls back to its own
         * store, which is exactly the behaviour before this change. A settings hiccup must never cost a tenant
         * its screens, and it cannot grant anything either — every guarded endpoint still refuses on its own.
         */
        if (activeOrg != null && capabilityService != null) {
            try {
                claims.put("caps", capabilityService.encodeFor(activeOrg.getId()));
            } catch (RuntimeException capsUnavailable) {
                log.warn("Could not resolve capabilities for org {} while minting a token; the claim is "
                        + "omitted and services will fall back to their own settings store.",
                        activeOrg.getId(), capsUnavailable);
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
            // B2B P0.5: read the module from the ACTIVE ORG, falling back to userType. Once one login can be
            // active in another module's org, userType and the org's module disagree — and filtering by the
            // person's type would select the wrong module's grants, which is precisely what the line above
            // exists to prevent. The safety property is unchanged; only where we learn the module has moved.
            String module = moduleForOrg(activeOrg, user.getUserType());
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
        Map<String, Object> claims = buildClaims(user, orgId);
        String accessToken = jwtService.generateAccessToken(userDetails, claims);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());
        return buildAuthResponse(user, accessToken, refreshToken.getToken(), claims);
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
        // B2B P0.5: same resolution as addLocationClaims — the grant check must ask about the module of the
        // org being worked in, or a user active in another module's org is refused their own store.
        String module = moduleForOrg(organizationService.findById(orgId), user.getUserType());
        boolean holdsGrant = userLocationAccessRepository
                .findByUserIdAndOrganizationIdAndStatus(userId, orgId, "ACTIVE").stream()
                .filter(g -> module.equals(g.getModule()))
                .anyMatch(g -> g.getLocationId() != null && g.getLocationId().equals(locationId));
        if (!holdsGrant) {
            throw new ValidationException("You do not have access to that store.");
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        Map<String, Object> claims = buildClaims(user, organizationService.findById(orgId), locationId);
        String accessToken = jwtService.generateAccessToken(userDetails, claims);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());
        return buildAuthResponse(user, accessToken, refreshToken.getToken(), claims);
    }

    /** The caller's location grants in the active org (id + role) — feeds the store/branch switcher. */
    public java.util.List<Map<String, Object>> myLocations(Long userId, Long orgId) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (orgId == null) return out;
        // B2B P0.5: must match addLocationClaims exactly — if the switcher's list and the token's claims
        // disagreed about the module, the UI would offer a store the token refuses to accept.
        String module = moduleOf(userId, orgId);
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
