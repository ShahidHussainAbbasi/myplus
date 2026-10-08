package com.myplus.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.catalog.entity.PriceChangeRequest;

/** PR-4 — every read is scoped by the caller's organisation; an id from another tenant is simply not found. */
public interface PriceChangeRequestRepository extends JpaRepository<PriceChangeRequest, Long> {

    String ORG = "(r.organizationId = :orgId or (r.organizationId is null and :orgId is null))";

    @Query("select r from PriceChangeRequest r where r.id = :id and " + ORG)
    Optional<PriceChangeRequest> findScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    /** Newest first, one status (or all when null). */
    @Query("select r from PriceChangeRequest r where " + ORG + " and (:status is null or r.status = :status) order by r.id desc")
    List<PriceChangeRequest> findByStatusScoped(@Param("status") String status, @Param("orgId") Long orgId, Pageable page);

    @Query("select r from PriceChangeRequest r where r.productId = :productId and r.status = 'PENDING' and " + ORG)
    List<PriceChangeRequest> findPendingForProduct(@Param("productId") Long productId, @Param("orgId") Long orgId);

    @Query("select count(r) from PriceChangeRequest r where r.status = 'PENDING' and " + ORG)
    long countPending(@Param("orgId") Long orgId);
}
