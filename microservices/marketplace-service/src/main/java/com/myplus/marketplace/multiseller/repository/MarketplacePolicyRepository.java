package com.myplus.marketplace.multiseller.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;

public interface MarketplacePolicyRepository extends JpaRepository<MarketplacePolicy, Long> {

    /** Served by idx_mkt_policy_type_active. Policies are few (tens), so a list is bounded by nature. */
    List<MarketplacePolicy> findByPolicyTypeAndActiveTrueOrderByNameAsc(String policyType);

    List<MarketplacePolicy> findAllByOrderByPolicyTypeAscNameAsc();

    Optional<MarketplacePolicy> findFirstByPolicyTypeAndActiveTrueAndIsDefaultTrue(String policyType);
}
