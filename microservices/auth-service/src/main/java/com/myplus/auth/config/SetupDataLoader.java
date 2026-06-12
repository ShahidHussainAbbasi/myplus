package com.myplus.auth.config;

import com.myplus.auth.entity.Privilege;
import com.myplus.auth.entity.Role;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.PrivilegeRepository;
import com.myplus.auth.repository.RoleRepository;
import com.myplus.auth.repository.UserRepository;
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

    // F15: never seed a known-password admin in prod. Override via env: SEED_ADMIN=false (prod),
    // or ADMIN_PASSWORD=<strong> if you do seed one.
    @org.springframework.beans.factory.annotation.Value("${app.seed-admin:true}")
    private boolean seedAdmin;
    @org.springframework.beans.factory.annotation.Value("${app.admin-password:Admin@2025!}")
    private String adminPassword;
    // Per-module demo users (dev only — gated by the same seed flag). Free-trial accounts capped at
    // 50 entries/module by the gateway; userType routes each to its own module dashboard.
    @org.springframework.beans.factory.annotation.Value("${app.demo-password:Demo@2025!}")
    private String demoPassword;

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
                "PUBLIC_ALERTS", "SYSTEM_ALERTS")) {
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
                "DELETE_COMPANY", "DELETE_VENDER", "DELETE_ITEM", "DELETE_ITEM_TYPE", "DELETE_ITEM_UNIT"));
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
                "ROLE_MARKETPLACE_BUYER", "ROLE_MARKETPLACE_SELLER")) {
            createOrUpdateRole(r, user);
        }
        for (String r : Arrays.asList("ADMIN_ROLE", "ROLE_BUSINESS_ADMIN")) {
            createOrUpdateRole(r, adminPrivileges);
        }
        for (String r : Arrays.asList("SUPER_ROLE", "ROLE_BUSINESS_SUPER")) {
            createOrUpdateRole(r, superSet);
        }
        Role adminRole = createOrUpdateRole("ROLE_ADMIN", superSet);
        Role appointmentRole = createOrUpdateRole("ROLE_APPOINTMENT_USER", user);

        if (seedAdmin && userRepository.findByEmail("admin@myplus.com").isEmpty()) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@myplus.com")
                    .password(passwordEncoder.encode(adminPassword))
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

        // Per-module DEMO users (dev seed flag) — self-healing on every startup so a restart always
        // yields a working login. demo=true -> the gateway caps writes at 50/module and the UI shows the
        // "register at maxtheservice.com" upsell. userType routes each to its own module dashboard.
        if (seedAdmin) {
            // email, userType, roleName
            String[][] demos = {
                    {"demo.business@myplus.com",     "BUSINESS",     "ROLE_BUSINESS_USER"},
                    {"demo.education@myplus.com",    "EDUCATION",    "ROLE_EDUCATION_USER"},
                    {"demo.welfare@myplus.com",      "WELFARE",      "ROLE_WELFARE_USER"},
                    {"demo.agriculture@myplus.com",  "AGRICULTURE",  "ROLE_AGRICULTURE_USER"},
                    {"demo.appointment@myplus.com",  "APPOINTMENT",  "ROLE_APPOINTMENT_USER"},
            };
            for (String[] d : demos) {
                final String email = d[0];
                Role role = roleRepository.findByName(d[2]).orElse(appointmentRole);
                User u = userRepository.findByEmail(email)
                        .orElseGet(() -> User.builder().username(email.split("@")[0]).email(email).build());
                u.setPassword(passwordEncoder.encode(demoPassword));
                u.setFirstName("Demo");
                u.setLastName(d[1]);
                u.setEnabled(true);
                u.setAccountNonLocked(true);
                u.setFailedLoginAttempts(0);
                u.setLockTime(null);
                u.setUserType(d[1]);
                u.setDemo(true);
                u.setRoles(new HashSet<>(Collections.singletonList(role)));
                userRepository.save(u);
            }
            log.info("Demo module users ensured ({} users, demo=true, 50-entry/module cap)", demos.length);
        }
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
