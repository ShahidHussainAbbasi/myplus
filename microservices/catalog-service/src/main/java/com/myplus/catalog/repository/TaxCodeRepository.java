package com.myplus.catalog.repository;

import com.myplus.catalog.entity.TaxCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped tax-code reads (multi-rate tax). Org-scoped with the standard NULL-fallback. */
@Repository
public interface TaxCodeRepository extends JpaRepository<TaxCode, Long> {

    String SCOPE = "(t.organizationId = :orgId OR (t.organizationId IS NULL AND t.userId = :userId))";

    @Query("SELECT t FROM TaxCode t WHERE " + SCOPE + " ORDER BY t.name ASC")
    List<TaxCode> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT t FROM TaxCode t WHERE t.id = :id AND " + SCOPE)
    Optional<TaxCode> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** All codes for an org (used to resolve product rates in one query — no per-product lookup). */
    List<TaxCode> findByOrganizationId(Long organizationId);

    @Query("SELECT t FROM TaxCode t WHERE t.isDefault = true AND " + SCOPE)
    Optional<TaxCode> findDefaultScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
