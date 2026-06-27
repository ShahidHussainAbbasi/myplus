package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.myplus.marketplace.entity.StorefrontCustomer;
import com.myplus.marketplace.repository.StorefrontCustomerRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Slice 61 — storefront accounts. Pure Mockito (always runs): register hashes the password (never plaintext) and
 * rejects duplicates; login verifies BCrypt and rejects a wrong password; a session token resolves to its customer.
 */
@ExtendWith(MockitoExtension.class)
class CustomerAccountServiceTest {

    @Mock private StorefrontCustomerRepository repo;
    @InjectMocks private CustomerAccountService service;

    @Test
    void register_hashes_the_password_and_returns_a_session_token() {
        when(repo.findByOrganizationIdAndEmailIgnoreCase(1L, "a@b.com")).thenReturn(Optional.empty());
        when(repo.save(any(StorefrontCustomer.class))).thenAnswer(i -> { StorefrontCustomer c = i.getArgument(0); c.setId(5L); return c; });

        Map<String, Object> out = service.register(1L, "a@b.com", "secret123", "Ann");

        assertThat(out.get("token")).isNotNull();
        ArgumentCaptor<StorefrontCustomer> cap = ArgumentCaptor.forClass(StorefrontCustomer.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        assertThat(cap.getValue().getPasswordHash()).isNotEqualTo("secret123");            // hashed, not plaintext
        assertThat(new BCryptPasswordEncoder().matches("secret123", cap.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void register_rejects_a_duplicate_email_at_the_same_store() {
        when(repo.findByOrganizationIdAndEmailIgnoreCase(1L, "a@b.com"))
                .thenReturn(Optional.of(StorefrontCustomer.builder().id(9L).build()));
        assertThatThrownBy(() -> service.register(1L, "a@b.com", "secret123", "Ann"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void login_verifies_bcrypt_and_rejects_a_wrong_password() {
        StorefrontCustomer c = StorefrontCustomer.builder().id(5L).organizationId(1L).email("a@b.com").name("Ann")
                .passwordHash(new BCryptPasswordEncoder().encode("secret123")).build();
        when(repo.findByOrganizationIdAndEmailIgnoreCase(1L, "a@b.com")).thenReturn(Optional.of(c));
        when(repo.save(any(StorefrontCustomer.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.login(1L, "a@b.com", "secret123").get("token")).isNotNull();
        assertThatThrownBy(() -> service.login(1L, "a@b.com", "WRONG")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void authenticate_resolves_a_session_token_to_its_customer() {
        StorefrontCustomer c = StorefrontCustomer.builder().id(5L).sessionToken("tok-123").build();
        when(repo.findBySessionToken("tok-123")).thenReturn(Optional.of(c));
        assertThat(service.authenticate("tok-123").getId()).isEqualTo(5L);
        assertThatThrownBy(() -> service.authenticate("nope")).isInstanceOf(RuntimeException.class);
    }
}
