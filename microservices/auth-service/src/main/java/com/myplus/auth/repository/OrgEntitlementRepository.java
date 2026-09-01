package com.myplus.auth.repository;

import com.myplus.auth.entity.OrgEntitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * E1 — per-tenant entitlement deviations. Always keyed by organization; there is no global read.
 *
 * <p>{@link #findByOrganizationId} is the only read the resolver makes, and it is made ONCE per tenant behind
 * a bounded cache — the same grain {@code SettingsStore.findAll} uses, and for the same reason: a per-key
 * query would issue thirteen selects to answer one token mint.
 */
public interface OrgEntitlementRepository extends JpaRepository<OrgEntitlement, Long> {

    List<OrgEntitlement> findByOrganizationId(Long organizationId);

    Optional<OrgEntitlement> findByOrganizationIdAndCapability(Long organizationId, String capability);

    /** Does this tenant have ANY entitlement row? The grandfather seeder's idempotence check. */
    boolean existsByOrganizationId(Long organizationId);
}
