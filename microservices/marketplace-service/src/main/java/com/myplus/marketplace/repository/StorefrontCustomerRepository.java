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

    // P4 contact-view backfill: shoppers bridged BEFORE the role index existed carry a party_id already, so the
    // bridge's skip-guard means they never bridge again and would never appear in a contact view. Walked by an id
    // cursor so the admin job can resume in batches. Org-only scope — a storefront account has no owning user.
    @org.springframework.data.jpa.repository.Query(
            "select c from StorefrontCustomer c where c.partyId is not null and c.id > :afterId "
          + "and c.organizationId = :orgId order by c.id asc")
    java.util.List<StorefrontCustomer> findBridgedAfter(
            @org.springframework.data.repository.query.Param("afterId") Long afterId,
            @org.springframework.data.repository.query.Param("orgId") Long orgId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "select count(c) from StorefrontCustomer c where c.partyId is not null and c.id > :afterId "
          + "and c.organizationId = :orgId")
    long countBridgedAfter(@org.springframework.data.repository.query.Param("afterId") Long afterId,
                           @org.springframework.data.repository.query.Param("orgId") Long orgId);
}
