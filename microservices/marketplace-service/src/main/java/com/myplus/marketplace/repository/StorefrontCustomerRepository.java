package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.StorefrontCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Storefront shopper accounts (slice 61). */
public interface StorefrontCustomerRepository extends JpaRepository<StorefrontCustomer, Long> {
    Optional<StorefrontCustomer> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);
    Optional<StorefrontCustomer> findBySessionToken(String sessionToken);
}
