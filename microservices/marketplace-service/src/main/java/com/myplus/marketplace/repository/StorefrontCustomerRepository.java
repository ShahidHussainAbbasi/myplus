package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.StorefrontCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Storefront shopper accounts (slice 61). */
public interface StorefrontCustomerRepository extends JpaRepository<StorefrontCustomer, Long> {
    Optional<StorefrontCustomer> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);
    Optional<StorefrontCustomer> findBySessionToken(String sessionToken);

    /** Party bridge: stamp ONLY party_id (targeted — never a full-entity save, which could clobber other columns). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update storefront_customer set party_id = :partyId where id = :id", nativeQuery = true)
    void updatePartyId(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("partyId") Long partyId);
}
