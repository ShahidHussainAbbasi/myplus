package com.myplus.market.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.market.entity.MarketPolicy;

import jakarta.persistence.LockModeType;

public interface MarketPolicyRepo extends JpaRepository<MarketPolicy, Long> {

    Optional<MarketPolicy> findByIdAndOrganizationId(Long id, Long organizationId);

    List<MarketPolicy> findByOrganizationIdOrderByPolicyTypeAscVersionNoDesc(Long organizationId);

    List<MarketPolicy> findByOrganizationIdAndPolicyTypeOrderByVersionNoDesc(Long organizationId, String policyType);

    List<MarketPolicy> findByOrganizationIdAndStatus(Long organizationId, String status);

    @Query("select coalesce(max(p.versionNo), 0) from MarketPolicy p "
            + "where p.organizationId = :org and p.policyType = :type")
    int maxVersion(@Param("org") Long org, @Param("type") String type);

    /** The current published version of a type, locked: publishing serialises on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MarketPolicy p where p.organizationId = :org and p.publishedSlot = :type")
    Optional<MarketPolicy> lockPublished(@Param("org") Long org, @Param("type") String type);
}
