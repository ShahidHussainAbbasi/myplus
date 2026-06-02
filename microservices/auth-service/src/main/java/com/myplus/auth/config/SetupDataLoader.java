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

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStart() {
        log.info("SetupDataLoader: seeding default roles, privileges, and admin user...");

        Privilege loginPriv = createPrivilegeIfNotExists("LOGIN_PRIVILEGE");
        Privilege adminPriv = createPrivilegeIfNotExists("ADMIN_PRIVILEGE");
        Privilege readPriv = createPrivilegeIfNotExists("READ_PRIVILEGE");
        Privilege writePriv = createPrivilegeIfNotExists("WRITE_PRIVILEGE");
        Privilege deletePriv = createPrivilegeIfNotExists("DELETE_PRIVILEGE");

        Set<Privilege> adminPrivileges = new HashSet<>(Arrays.asList(loginPriv, adminPriv, readPriv, writePriv, deletePriv));
        Set<Privilege> userPrivileges = new HashSet<>(Arrays.asList(loginPriv, readPriv, writePriv));

        Role adminRole = createRoleIfNotExists("ROLE_ADMIN", adminPrivileges);
        createRoleIfNotExists("ROLE_BUSINESS_USER", userPrivileges);
        createRoleIfNotExists("ROLE_EDUCATION_USER", userPrivileges);
        createRoleIfNotExists("ROLE_WELFARE_USER", userPrivileges);
        createRoleIfNotExists("ROLE_AGRICULTURE_USER", userPrivileges);
        createRoleIfNotExists("ROLE_PHARMA_USER", userPrivileges);
        createRoleIfNotExists("ROLE_MARKETPLACE_BUYER", userPrivileges);
        createRoleIfNotExists("ROLE_MARKETPLACE_SELLER", userPrivileges);

        if (userRepository.findByEmail("admin@myplus.com").isEmpty()) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@myplus.com")
                    .password(passwordEncoder.encode("Admin@2025!"))
                    .firstName("Default")
                    .lastName("Admin")
                    .enabled(true)
                    .accountNonLocked(true)
                    .userType("ADMIN")
                    .roles(new HashSet<>(Collections.singletonList(adminRole)))
                    .build();
            userRepository.save(admin);
            log.info("Default admin user created: admin@myplus.com / Admin@2025!");
        }
    }

    private Privilege createPrivilegeIfNotExists(String name) {
        return privilegeRepository.findByName(name)
                .orElseGet(() -> privilegeRepository.save(Privilege.builder().name(name).build()));
    }

    private Role createRoleIfNotExists(String name, Set<Privilege> privileges) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).privileges(privileges).build()));
    }
}
