package com.myplus.marketplace.service;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.entity.StorefrontCustomer;
import com.myplus.marketplace.repository.StorefrontCustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Storefront shopper accounts (slice 61, E4) — store-scoped register/login with BCrypt + an opaque session token.
 * Self-contained (no staff auth-service coupling). Anonymous callers; the store org comes in the request body.
 */
@Service
@RequiredArgsConstructor
public class CustomerAccountService {

    private final StorefrontCustomerRepository repo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Transactional
    public Map<String, Object> register(Long orgId, String email, String password, String name) {
        if (orgId == null) throw new ValidationException("Store is required");
        if (email == null || email.isBlank()) throw new ValidationException("Email is required");
        if (password == null || password.length() < 6) throw new ValidationException("Password must be at least 6 characters");
        String e = email.trim();
        if (repo.findByOrganizationIdAndEmailIgnoreCase(orgId, e).isPresent())
            throw new ValidationException("An account with this email already exists at this store");

        StorefrontCustomer c = StorefrontCustomer.builder()
                .organizationId(orgId).email(e).name(name == null ? e : name.trim())
                .passwordHash(encoder.encode(password))
                .sessionToken(UUID.randomUUID().toString() + "-" + UUID.randomUUID())
                .build();
        return session(repo.save(c));
    }

    @Transactional
    public Map<String, Object> login(Long orgId, String email, String password) {
        if (orgId == null || email == null || password == null) throw new ValidationException("Email and password are required");
        StorefrontCustomer c = repo.findByOrganizationIdAndEmailIgnoreCase(orgId, email.trim()).orElse(null);
        if (c == null || !encoder.matches(password, c.getPasswordHash()))
            throw new ValidationException("Invalid email or password");
        c.setSessionToken(UUID.randomUUID().toString() + "-" + UUID.randomUUID());   // rotate on each login
        return session(repo.save(c));
    }

    /** Resolve a session token to its customer, or throw (invalid/expired). */
    public StorefrontCustomer authenticate(String token) {
        if (token == null || token.isBlank()) throw new ResourceNotFoundException("Not signed in");
        return repo.findBySessionToken(token.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Session expired — please sign in again"));
    }

    private Map<String, Object> session(StorefrontCustomer c) {
        return Map.of("token", c.getSessionToken(), "name", c.getName(), "email", c.getEmail(), "customerId", c.getId());
    }
}
