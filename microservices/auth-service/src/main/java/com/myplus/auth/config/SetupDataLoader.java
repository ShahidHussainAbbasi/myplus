package com.myplus.auth.config;

import com.myplus.auth.entity.Organization;
import com.myplus.auth.entity.Privilege;
import com.myplus.auth.entity.Role;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.PrivilegeRepository;
import com.myplus.auth.repository.RoleRepository;
import com.myplus.auth.repository.UserRepository;
import com.myplus.auth.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SetupDataLoader {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationService organizationService;

    // F15: never seed a known-password admin in prod. Override via env: APP_SEED_ADMIN=false (prod),
    // or APP_ADMIN_PASSWORD=<strong> if you do seed one.
    @org.springframework.beans.factory.annotation.Value("${app.seed-admin:true}")
    private boolean seedAdmin;
    @org.springframework.beans.factory.annotation.Value("${app.admin-password:}")
    private String adminPassword;
    // Per-module demo (sandbox) users — gated by its OWN flag so prod can keep an admin without ever
    // seeding shared demo accounts (set APP_SEED_DEMO=false in prod). DEMO tenants are capped at 50
    // entries/module by the gateway; userType routes each to its own module dashboard.
    @org.springframework.beans.factory.annotation.Value("${app.seed-demo:true}")
    private boolean seedDemo;
    @org.springframework.beans.factory.annotation.Value("${app.demo-password:}")
    private String demoPassword;
    /**
     * DEV TEST FIXTURES — the owner./admin./user. privilege ladder and the named store/branch teams. These are
     * test scaffolding, NOT product: they exist so Cypress can log in at a known privilege tier without hitting
     * the demo write cap. Separated from {@code app.seed-demo} because a public demo tenant IS a product feature
     * and may legitimately run in production, whereas a fixture never may.
     * Hard-blocked under the {@code prod} profile regardless of this flag — see {@link #fixturesAllowed()}.
     */
    @org.springframework.beans.factory.annotation.Value("${app.seed-test-fixtures:true}")
    private boolean seedTestFixtures;

    /** Fallback ONLY for non-prod: prod must supply APP_DEMO_PASSWORD explicitly (see demoPassword()). */
    private static final String DEV_DEMO_PASSWORD = "Demo@2025!";

    /** The password actually used for seeded accounts this run — resolved once in {@link #onStart()}. */
    private String demoPw;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    /** True when this JVM is running the production profile. */
    private boolean isProd() {
        for (String p : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    /**
     * Test fixtures may never be created in production, whatever the config says.
     *
     * A flag alone is not a control: {@code application-prod.yml} set {@code seed-admin:false} but was silent on
     * {@code seed-demo}, so it inherited the dev default of TRUE and only docker-compose's override stood between
     * a non-compose prod deploy and 45 known-password accounts. This is the second, independent gate that does not
     * depend on anyone remembering an env var.
     */
    private boolean fixturesAllowed() {
        if (!seedTestFixtures) return false;
        if (isProd()) {
            log.warn("app.seed-test-fixtures=true but the 'prod' profile is active — REFUSING to seed dev test "
                    + "fixtures (owner./admin./user. accounts). This is a hard block, not a config default.");
            return false;
        }
        return true;
    }

    /**
     * The password for seeded demo accounts. In prod it must be supplied explicitly; there is no default, because
     * a default here is a published credential on a live system. Returns null when demo seeding must be skipped.
     */
    private String demoPassword() {
        if (demoPassword != null && !demoPassword.isBlank()) return demoPassword;
        if (isProd()) {
            log.error("app.seed-demo=true but APP_DEMO_PASSWORD is not set under the 'prod' profile — SKIPPING "
                    + "demo seeding. Set an explicit strong password to run demo tenants in production, or set "
                    + "APP_SEED_DEMO=false to silence this.");
            return null;
        }
        return DEV_DEMO_PASSWORD;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStart() {
        log.info("SetupDataLoader: seeding roles, privileges, and admin user...");

        // ---- Privilege catalog: mirrors the monolith's role_privileges_*.properties so the JWT
        //      carries exactly the authorities the monolith (@PreAuthorize / sec:authorize) and the
        //      microservices check. (Model A: privileges live here and travel in the token.) ----
        Map<String, Privilege> p = new HashMap<>();
        for (String name : Arrays.asList(
                "LOGIN_PRIVILEGE", "READ_PRIVILEGE", "WRITE_PRIVILEGE", "UPDATE_PRIVILEGE",
                "DELETE_PRIVILEGE", "CHANGE_PASSWORD_PRIVILEGE",
                "SUPER_PRIVILEGE", "ADMIN_PRIVILEGE", "USER_PRIVILEGE", "GUEST_PRIVILEGE",
                "GET_COMPANY", "GET_VENDER", "GET_ITEM", "GET_ITEM_TYPE", "GET_ITEM_UNIT",
                "ADD_COMPANY", "ADD_VENDER", "ADD_ITEM", "ADD_ITEM_TYPE", "ADD_ITEM_UNIT",
                "UPDATE_COMPANY", "UPDATE_VENDER", "UPDATE_ITEM", "UPDATE_ITEM_TYPE", "UPDATE_ITEM_UNIT",
                "DELETE_COMPANY", "DELETE_VENDER", "DELETE_ITEM", "DELETE_ITEM_TYPE", "DELETE_ITEM_UNIT",
                "PUBLIC_ALERTS", "SYSTEM_ALERTS",
                "VOID_INVOICE",
                "DEMO_PRIVILEGE",
                // Right to run "Reset demo" (clear write counters + purge the caller's own org data).
                // DELIBERATELY separate from DEMO_PRIVILEGE (which also means "capped at 50/module"), so the
                // dev-seeded owner test account can reset without being a capped demo account — and so it can
                // never be granted implicitly to a real customer's owner, who must not have a one-click
                // "delete my organisation" button. Only DEMO_ROLE and DEMO_RESET_ROLE carry it.
                "DEMO_RESET_PRIVILEGE")) {
            p.put(name, createPrivilegeIfNotExists(name));
        }

        // ---- Privilege groups (cumulative: guest ⊂ user ⊂ admin ⊂ super) ----
        Set<Privilege> guest = pick(p, "LOGIN_PRIVILEGE", "READ_PRIVILEGE", "GUEST_PRIVILEGE");
        Set<Privilege> user = new HashSet<>(guest);
        user.addAll(pick(p, "CHANGE_PASSWORD_PRIVILEGE", "WRITE_PRIVILEGE", "UPDATE_PRIVILEGE", "USER_PRIVILEGE",
                "GET_COMPANY", "GET_VENDER", "GET_ITEM", "GET_ITEM_TYPE", "GET_ITEM_UNIT",
                "ADD_COMPANY", "ADD_VENDER", "ADD_ITEM", "ADD_ITEM_TYPE", "ADD_ITEM_UNIT",
                "UPDATE_COMPANY", "UPDATE_VENDER", "UPDATE_ITEM", "UPDATE_ITEM_TYPE", "UPDATE_ITEM_UNIT",
                "PUBLIC_ALERTS", "SYSTEM_ALERTS"));
        Set<Privilege> adminPrivileges = new HashSet<>(user);
        adminPrivileges.addAll(pick(p, "DELETE_PRIVILEGE", "ADMIN_PRIVILEGE",
                "DELETE_COMPANY", "DELETE_VENDER", "DELETE_ITEM", "DELETE_ITEM_TYPE", "DELETE_ITEM_UNIT",
                "VOID_INVOICE"));   // dedicated void right — admins/owner/super keep it (no regression); NOT in `user`
        Set<Privilege> superSet = new HashSet<>(adminPrivileges);
        superSet.addAll(pick(p, "SUPER_PRIVILEGE"));

        // ---- Roles: monolith names + existing auth-service names. Privilege sets are refreshed on
        //      every startup so catalog changes propagate. Re-linking in migration is BY NAME, so all
        //      monolith role names must exist here (see migrate_monolith_users.sql verification). ----
        for (String r : Arrays.asList("GUEST_ROLE", "ROLE_BUSINESS_GUEST", "ROLE_GENERAL")) {
            createOrUpdateRole(r, guest);
        }
        for (String r : Arrays.asList("USER_ROLE", "ROLE_USER", "ROLE_BUSINESS_USER", "ROLE_EDUCATION_USER",
                "ROLE_WELFARE_USER", "ROLE_AGRICULTURE_USER", "ROLE_PHARMA_USER",
                "ROLE_MARKETPLACE_BUYER", "ROLE_MARKETPLACE_SELLER",
                "ROLE_ANALYTICS_USER", "ROLE_CAMPAIGN_USER", "ROLE_INVENTORY_USER")) {
            createOrUpdateRole(r, user);
        }
        for (String r : Arrays.asList("ADMIN_ROLE", "ROLE_BUSINESS_ADMIN")) {
            createOrUpdateRole(r, adminPrivileges);
        }
        for (String r : Arrays.asList("SUPER_ROLE", "ROLE_BUSINESS_SUPER")) {
            createOrUpdateRole(r, superSet);
        }
        Role adminRole = createOrUpdateRole("ROLE_ADMIN", superSet);
        // Company OWNER = super user of their OWN tenant. Assigned to every self-signup / operator-
        // provisioned owner so all features of their module's dashboard are available. Deliberately NOT
        // the platform ROLE_ADMIN, so owners cannot reach operator-only endpoints (e.g. provision-tenant).
        // Org-scoping (X-Org-Id) keeps each owner's super access confined to their own company's data.
        // Finer per-user roles are assigned later by the owner via the upcoming user-management form.
        createOrUpdateRole("ROLE_OWNER", superSet);
        createOrUpdateRole("ROLE_APPOINTMENT_USER", user);
        // Demo accounts get full module privileges (so the privilege-gated dashboards work) plus
        // DEMO_PRIVILEGE, which the UI uses to show the demo banner. The 50/module write cap is the
        // only real limit (enforced at the gateway), not the privilege set.
        Set<Privilege> demoSet = new HashSet<>(superSet);
        demoSet.add(p.get("DEMO_PRIVILEGE"));
        demoSet.add(p.get("DEMO_RESET_PRIVILEGE"));   // demo accounts may reset themselves (unchanged behaviour)
        Role demoRole = createOrUpdateRole("DEMO_ROLE", demoSet);

        // Same rule as the demo password: prod must supply APP_ADMIN_PASSWORD explicitly. The dev default is a
        // published credential, and application-prod.yml maps ADMIN_PASSWORD to EMPTY — so an unguarded prod run
        // with seed-admin=true would have created admin@myplus.com with a blank password.
        String adminPw = adminPassword;
        if ((adminPw == null || adminPw.isBlank()) && isProd()) {
            if (seedAdmin) {
                log.error("app.seed-admin=true but APP_ADMIN_PASSWORD is not set under the 'prod' profile — "
                        + "SKIPPING admin seeding rather than creating a blank-password account.");
            }
            adminPw = null;
        } else if (adminPw == null || adminPw.isBlank()) {
            adminPw = "Admin@2025!";   // dev convenience only; unreachable under the prod profile
        }

        if (seedAdmin && adminPw != null && userRepository.findByEmail("admin@myplus.com").isEmpty()) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@myplus.com")
                    .password(passwordEncoder.encode(adminPw))
                    .firstName("Default")
                    .lastName("Admin")
                    .enabled(true)
                    .accountNonLocked(true)
                    .userType("ADMIN")
                    .roles(new HashSet<>(Collections.singletonList(adminRole)))
                    .build();
            userRepository.save(admin);
            log.info("Default admin user created: admin@myplus.com");
        }

        // Resolve the seeding password ONCE. Null means "no safe password available" (prod with no explicit
        // APP_DEMO_PASSWORD), in which case nothing password-bearing is seeded at all.
        demoPw = demoPassword();

        // Per-module DEMO users — the PRODUCT's try-before-buy tenants, not test fixtures. demo=true means the
        // gateway caps writes at 50/module and the UI shows the "register at maxtheservice.com" upsell, so these
        // may legitimately run in production — but only with an explicitly supplied password (see demoPassword()).
        // Self-healing on every startup so a restart always yields a working login.
        if (seedDemo && demoPw != null) {
            // One demo account per domain microservice. email, userType. All get DEMO_ROLE (full module
            // privileges + DEMO_PRIVILEGE); userType routes each to its own module dashboard.
            String[][] demos = {
                    {"demo.business@myplus.com",     "BUSINESS"},
                    {"demo.pharma@myplus.com",       "PHARMA"},      // pharmacy vertical — reuses the trade dashboard (slice 33)
                    {"demo.education@myplus.com",    "EDUCATION"},
                    {"demo.welfare@myplus.com",      "WELFARE"},
                    {"demo.agriculture@myplus.com",  "AGRICULTURE"},
                    {"demo.appointment@myplus.com",  "APPOINTMENT"},
                    {"demo.inventory@myplus.com",    "INVENTORY"},
                    {"demo.marketplace@myplus.com",  "MARKETPLACE"},
                    {"demo.campaign@myplus.com",     "CAMPAIGN"},
                    {"demo.analytics@myplus.com",    "ANALYTICS"},
            };
            for (String[] d : demos) {
                final String email = d[0];
                User u = userRepository.findByEmail(email)
                        .orElseGet(() -> User.builder().username(email.split("@")[0]).email(email).build());
                u.setPassword(passwordEncoder.encode(demoPw));
                u.setFirstName("Demo");
                u.setLastName(d[1]);
                u.setEnabled(true);
                u.setAccountNonLocked(true);
                u.setFailedLoginAttempts(0);
                u.setLockTime(null);
                u.setUserType(d[1]);
                u.setDemo(true);
                u.setRoles(new HashSet<>(Collections.singletonList(demoRole)));
                userRepository.save(u);
            }
            log.info("Demo module users ensured ({} users, demo=true, 50-entry/module cap)", demos.length);
        }

        // ── DEV TEST FIXTURES (never production) ───────────────────────────────────────────────────────
        // Everything below is test scaffolding, not product: the owner./admin./user. privilege ladder and the
        // named store/branch teams. Gated by its OWN flag AND hard-blocked under the prod profile, so it cannot
        // reach a live system through a forgotten env var — which is exactly how the demo flag was exposed.
        if (fixturesAllowed()) {
            // A real BUSINESS OWNER for testing owner-gated UI (Finance menu, Settings, Team). Unlike the
            // demo.* accounts (DEMO_ROLE + 50-write cap) this carries ROLE_OWNER with no cap. Its organization
            // is auto-provisioned on first login (OrganizationService.getOrCreatePrimaryOrg). Self-healing on
            // every startup. Login: owner.business@myplus.com.
            Role ownerRole = roleRepository.findByName("ROLE_OWNER")
                    .orElseThrow(() -> new IllegalStateException("ROLE_OWNER not seeded"));
            // ROLE_OWNER *plus* a second, single-privilege role carrying DEMO_RESET_PRIVILEGE — so this dev test
            // account can run "Reset demo" (clear counters + purge its OWN org) while staying uncapped. The
            // privilege rides a SEPARATE role on purpose: adding it to ROLE_OWNER would hand every real customer's
            // owner a one-click "delete my organisation" button. Seeded only here, inside the dev-only seed flag.
            Role demoResetRole = createOrUpdateRole("DEMO_RESET_ROLE", pick(p, "DEMO_RESET_PRIVILEGE"));
            User owner = ensureOwner("owner.business@myplus.com", "Business", "BUSINESS", ownerRole, demoResetRole);
            log.info("Business OWNER test user ensured: owner.business@myplus.com (ROLE_OWNER + DEMO_RESET_ROLE, demo=false)");

            // Multi-location team fixture — the owner's ADMIN + two cashiers, in the owner's org, with a KNOWN
            // password. Needed because the real onboarding path (createOrgUser) sets a throwaway password and
            // mails a reset link, so no test can ever authenticate as a member it creates. Store grants are NOT
            // seeded here: stores live in business-service, so the test grants them at runtime via /assignStores.
            // Same dev-only gate as the accounts above (app.seed-demo=false in prod).
            Organization ownerOrg = organizationService.getOrCreatePrimaryOrg(owner);
            Role memberAdminRole = roleRepository.findByName("ADMIN_ROLE")
                    .orElseThrow(() -> new IllegalStateException("ADMIN_ROLE not seeded"));
            Role memberUserRole = roleRepository.findByName("ROLE_BUSINESS_USER")
                    .orElseThrow(() -> new IllegalStateException("ROLE_BUSINESS_USER not seeded"));
            String[][] members = {
                    // email, firstName, membership role, global role
                    {"admin.store@myplus.com",   "Store",   "ADMIN", "ADMIN_ROLE"},
                    {"cashier.a@myplus.com",     "Cashier", "USER",  "ROLE_BUSINESS_USER"},
                    {"cashier.b@myplus.com",     "Cashier", "USER",  "ROLE_BUSINESS_USER"},
            };
            for (String[] m : members) {
                final String email = m[0];
                User u = userRepository.findByEmail(email)
                        .orElseGet(() -> User.builder().username(email.split("@")[0]).email(email).build());
                u.setPassword(passwordEncoder.encode(demoPw));
                u.setFirstName(m[1]);
                u.setLastName(email.split("@")[0]);
                u.setEnabled(true);
                u.setAccountNonLocked(true);
                u.setFailedLoginAttempts(0);
                u.setLockTime(null);
                u.setUserType("BUSINESS");
                u.setDemo(false);
                u.setRoles(new HashSet<>(Collections.singletonList(
                        "ADMIN_ROLE".equals(m[3]) ? memberAdminRole : memberUserRole)));
                u = userRepository.save(u);
                organizationService.addMember(u.getId(), ownerOrg.getId(), m[2]);   // idempotent
            }
            log.info("Multi-location team fixture ensured in org {}: admin.store@, cashier.a@, cashier.b@",
                    ownerOrg.getId());

            // P4 — the same fixture for EDUCATION: an owner + two teachers in one org, so the branch (school)
            // scoping can be tested. Their grants point at school ids, not store ids (module=EDUCATION), and are
            // assigned at runtime by the spec because schools live in education-service.
            User eduOwner = ensureOwner("owner.education@myplus.com", "Education", "EDUCATION", ownerRole);
            Organization eduOrg = organizationService.getOrCreatePrimaryOrg(eduOwner);   // already created; re-read
            Role teacherRole = roleRepository.findByName("ROLE_EDUCATION_USER")
                    .orElseThrow(() -> new IllegalStateException("ROLE_EDUCATION_USER not seeded"));
            for (String email : new String[]{"teacher.a@myplus.com", "teacher.b@myplus.com"}) {
                User t = userRepository.findByEmail(email)
                        .orElseGet(() -> User.builder().username(email.split("@")[0]).email(email).build());
                t.setPassword(passwordEncoder.encode(demoPw));
                t.setFirstName("Teacher");
                t.setLastName(email.split("@")[0]);
                t.setEnabled(true);
                t.setAccountNonLocked(true);
                t.setFailedLoginAttempts(0);
                t.setLockTime(null);
                t.setUserType("EDUCATION");
                t.setDemo(false);
                t.setRoles(new HashSet<>(Collections.singletonList(teacherRole)));
                t = userRepository.save(t);
                organizationService.addMember(t.getId(), eduOrg.getId(), "USER");   // idempotent
            }
            log.info("Multi-branch education fixture ensured in org {}: owner.education@, teacher.a@, teacher.b@",
                    eduOrg.getId());

            // B2B P0.5 — the MULTI-MODULE fixture: ONE login that belongs to a commerce org AND a school.
            //
            // This is the case the platform could not previously express. Routing used to key off the single
            // User.userType, so such a person was pinned to one module forever; it now follows the ACTIVE ORG.
            // No real customer runs two modules yet (confirmed), so the fixture has to be seeded rather than
            // borrowed — otherwise the two-org hop stays untested, which is exactly the gap org-switcher.cy.js
            // documented in its own scope note.
            //
            // A DEDICATED account on purpose: adding a second membership to owner.business@ would silently
            // change what every existing commerce spec sees in the org switcher. ROLE_OWNER carries the full
            // cross-module privilege set, so this user genuinely works in both dashboards rather than merely
            // landing on them. userType stays BUSINESS precisely so the tests prove the ORG wins over it.
            // Built directly rather than via ensureOwner(): that helper calls getOrCreatePrimaryOrg(), which
            // would mint a THIRD organization of its own before these memberships exist — leaving the fixture
            // in an org nobody asked for and showing three entries in the switcher. Seeding the memberships
            // first means the login's default active org resolves to the commerce org below.
            final String mmEmail = "multi.module@myplus.com";
            User multiModule = userRepository.findByEmail(mmEmail)
                    .orElseGet(() -> User.builder().username(mmEmail.split("@")[0]).email(mmEmail).build());
            multiModule.setPassword(passwordEncoder.encode(demoPw));
            multiModule.setFirstName("Multi");
            multiModule.setLastName("Module");
            multiModule.setEnabled(true);
            multiModule.setAccountNonLocked(true);
            multiModule.setFailedLoginAttempts(0);
            multiModule.setLockTime(null);
            multiModule.setUserType("BUSINESS");
            multiModule.setDemo(false);
            multiModule.setRoles(new HashSet<>(Collections.singletonList(ownerRole)));
            multiModule = userRepository.save(multiModule);
            organizationService.addMember(multiModule.getId(), ownerOrg.getId(), "ADMIN");   // idempotent
            organizationService.addMember(multiModule.getId(), eduOrg.getId(), "ADMIN");     // idempotent
            log.info("Multi-module fixture ensured: multi.module@ is a member of commerce org {} AND education org {}",
                    ownerOrg.getId(), eduOrg.getId());

            // The remaining 8 module OWNERs — 10 in total across the platform.
            //
            // NOT IN THIS LIST, because they are seeded ABOVE with extra fixtures rather than being missing:
            //   • owner.business@    — needs DEMO_RESET_ROLE too, and anchors the store team (admin.store@,
            //                          cashier.a@, cashier.b@) used by the multi-location specs
            //   • owner.education@   — anchors the branch team (teacher.a@, teacher.b@)
            // All 10 go through ensureOwner(), so the account shape is identical either way.
            //
            // Why every module gets one: the demo.* accounts are capped at 50 writes per module, so any spec that
            // seeds more than that fails partway through at an unrelated-looking write. Pharmacy hit exactly this —
            // its specs seed products per test and there was no uncapped pharmacy login to fall back to.
            // Password is the same ${app.demo-password}; each gets its own organization, so they are also the
            // fixtures for cross-tenant isolation tests.
            String[][] moduleOwners = {
                    {"owner.pharma@myplus.com",       "Pharma",      "PHARMA"},
                    {"owner.welfare@myplus.com",      "Welfare",     "WELFARE"},
                    {"owner.agriculture@myplus.com",  "Agriculture", "AGRICULTURE"},
                    {"owner.appointment@myplus.com",  "Appointment", "APPOINTMENT"},
                    {"owner.inventory@myplus.com",    "Inventory",   "INVENTORY"},
                    {"owner.marketplace@myplus.com",  "Marketplace", "MARKETPLACE"},
                    {"owner.campaign@myplus.com",     "Campaign",    "CAMPAIGN"},
                    {"owner.analytics@myplus.com",    "Analytics",   "ANALYTICS"},
            };
            for (String[] o : moduleOwners) {
                ensureOwner(o[0], o[1], o[2], ownerRole);
            }
            log.info("Module OWNER test users ensured ({}, ROLE_OWNER, demo=false, own org): {}",
                    moduleOwners.length,
                    Arrays.stream(moduleOwners).map(o -> o[0]).collect(java.util.stream.Collectors.joining(", ")));

            // ── The full privilege ladder, for every module ────────────────────────────────────────────────
            // Per module the platform now seeds four accounts, all on ${app.demo-password}:
            //   demo.<m>@   DEMO_ROLE  — full module privileges BUT capped at 50 writes/module (the upsell demo)
            //   user.<m>@   ROLE_<M>_USER — WRITE/UPDATE, no DELETE_PRIVILEGE, no ADMIN_PRIVILEGE
            //   admin.<m>@  ADMIN_ROLE — adds DELETE_PRIVILEGE + ADMIN_PRIVILEGE + VOID_INVOICE
            //   owner.<m>@  ROLE_OWNER — the super set, uncapped
            //
            // admin/user are seeded as MEMBERS OF THE OWNER'S ORG on purpose. A privilege test needs two accounts
            // that differ only by role inside one tenant; if they sat in separate orgs, a refusal would prove
            // org-scoping worked, not that the privilege gate did. This is the shape cypress/e2e/security/
            // method-authz.cy.js already relies on for business + education.
            //
            // The named fixtures seeded above (admin.store@, cashier.a/b@, teacher.a/b@) are KEPT — existing specs
            // reference them by name, and they carry location/branch grants these generic ones deliberately do not.
            String[][] moduleTeams = {
                    // email prefix   display     userType        per-module USER role
                    {"business",     "Business",    "BUSINESS",    "ROLE_BUSINESS_USER"},
                    {"education",    "Education",   "EDUCATION",   "ROLE_EDUCATION_USER"},
                    {"pharma",       "Pharma",      "PHARMA",      "ROLE_PHARMA_USER"},
                    {"welfare",      "Welfare",     "WELFARE",     "ROLE_WELFARE_USER"},
                    {"agriculture",  "Agriculture", "AGRICULTURE", "ROLE_AGRICULTURE_USER"},
                    {"appointment",  "Appointment", "APPOINTMENT", "ROLE_APPOINTMENT_USER"},
                    {"inventory",    "Inventory",   "INVENTORY",   "ROLE_INVENTORY_USER"},
                    {"marketplace",  "Marketplace", "MARKETPLACE", "ROLE_MARKETPLACE_SELLER"},
                    {"campaign",     "Campaign",    "CAMPAIGN",    "ROLE_CAMPAIGN_USER"},
                    {"analytics",    "Analytics",   "ANALYTICS",   "ROLE_ANALYTICS_USER"},
            };
            Role genericAdminRole = roleRepository.findByName("ADMIN_ROLE")
                    .orElseThrow(() -> new IllegalStateException("ADMIN_ROLE not seeded"));
            for (String[] m : moduleTeams) {
                User modOwner = userRepository.findByEmail("owner." + m[0] + "@myplus.com")
                        .orElseThrow(() -> new IllegalStateException("owner." + m[0] + "@ not seeded — it must be"
                                + " created before its team, since the team joins ITS organization"));
                Long orgId = organizationService.getOrCreatePrimaryOrg(modOwner).getId();   // idempotent
                Role userRole = roleRepository.findByName(m[3])
                        .orElseThrow(() -> new IllegalStateException(m[3] + " not seeded"));
                ensureMember("admin." + m[0] + "@myplus.com", "Admin", m[1], m[2], genericAdminRole, orgId, "ADMIN");
                ensureMember("user."  + m[0] + "@myplus.com", "User",  m[1], m[2], userRole,         orgId, "USER");
            }
            log.info("Module ADMIN + USER test users ensured ({} modules): admin.<module>@ / user.<module>@, "
                    + "each a member of that module's owner org", moduleTeams.length);
        }

        // NOTE: real customers are NEVER seeded here. A client is onboarded through self-service signup
        // (POST /api/auth/register) or the operator endpoint (POST /api/auth/admin/provision-tenant) —
        // see slice 32. Seeding a known-password customer row used to run in prod; that has been removed.
    }

    /**
     * Create/refresh a dev OWNER test account for one module, and make sure it has an organization.
     *
     * An owner is what a real customer's admin looks like: ROLE_OWNER (the super privilege set, scoped to their own
     * org by X-Org-Id) and {@code demo=false}, so it is NOT subject to the 50-entry/module demo cap. That cap is the
     * reason these exist — a Cypress spec that seeds more than 50 rows fails at an arbitrary later write when run as
     * a demo.* account, which reads as a product bug rather than a quota.
     *
     * Self-healing: re-runs on every startup reset the password/enabled/lock state, so a locked-out or
     * password-drifted fixture repairs itself. Dev-only — the whole block is gated by {@code app.seed-demo}.
     */
    private User ensureOwner(String email, String lastName, String userType, Role ownerRole, Role... extraRoles) {
        User u = userRepository.findByEmail(email)
                .orElseGet(() -> User.builder().username(email.split("@")[0]).email(email).build());
        u.setPassword(passwordEncoder.encode(demoPw));
        u.setFirstName("Owner");
        u.setLastName(lastName);
        u.setEnabled(true);
        u.setAccountNonLocked(true);
        u.setFailedLoginAttempts(0);
        u.setLockTime(null);
        u.setUserType(userType);
        u.setDemo(false);
        Set<Role> roles = new HashSet<>();
        roles.add(ownerRole);
        Collections.addAll(roles, extraRoles);
        u.setRoles(roles);
        u = userRepository.save(u);
        organizationService.getOrCreatePrimaryOrg(u);   // idempotent — every owner needs a tenant to scope into
        return u;
    }

    /**
     * Create/refresh a dev team member (admin or plain user) INSIDE an existing organization.
     *
     * The org membership is the point. A privilege test is only meaningful when the accounts differ by ROLE while
     * sharing a TENANT — otherwise a refusal could just as easily be org-scoping doing its job, and the test proves
     * nothing about @PreAuthorize. Same self-healing reset as {@link #ensureOwner}.
     */
    private void ensureMember(String email, String firstName, String lastName, String userType,
                              Role role, Long orgId, String membershipRole) {
        User u = userRepository.findByEmail(email)
                .orElseGet(() -> User.builder().username(email.split("@")[0]).email(email).build());
        u.setPassword(passwordEncoder.encode(demoPw));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setEnabled(true);
        u.setAccountNonLocked(true);
        u.setFailedLoginAttempts(0);
        u.setLockTime(null);
        u.setUserType(userType);
        u.setDemo(false);
        u.setRoles(new HashSet<>(Collections.singletonList(role)));
        u = userRepository.save(u);
        organizationService.addMember(u.getId(), orgId, membershipRole);   // idempotent
    }

    private Privilege createPrivilegeIfNotExists(String name) {
        return privilegeRepository.findByName(name)
                .orElseGet(() -> privilegeRepository.save(Privilege.builder().name(name).build()));
    }

    /** Create the role if missing, and (re)assign its privilege set on every run so the catalog stays in sync. */
    private Role createOrUpdateRole(String name, Set<Privilege> privileges) {
        Role role = roleRepository.findByName(name).orElseGet(() -> Role.builder().name(name).build());
        role.setPrivileges(new HashSet<>(privileges));
        return roleRepository.save(role);
    }

    private Set<Privilege> pick(Map<String, Privilege> catalog, String... names) {
        Set<Privilege> set = new HashSet<>();
        for (String n : names) {
            set.add(catalog.get(n));
        }
        return set;
    }
}
