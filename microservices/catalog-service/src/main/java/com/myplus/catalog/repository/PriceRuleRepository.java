package com.myplus.catalog.repository;

import com.myplus.catalog.entity.PriceRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped price-rule reads. Org-scoped with the standard NULL-fallback, so a quote can never see
 * another tenant's negotiated rates.
 */
@Repository
public interface PriceRuleRepository extends JpaRepository<PriceRuleEntity, Long> {

    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    @Query("SELECT p FROM PriceRuleEntity p WHERE " + SCOPE + " ORDER BY p.id ASC")
    List<PriceRuleEntity> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: a by-id read that cannot reach another tenant's row. */
    @Query("SELECT p FROM PriceRuleEntity p WHERE p.id = :id AND " + SCOPE)
    Optional<PriceRuleEntity> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                             @Param("userId") Long userId);

    /**
     * Every ACTIVE rule for the tenant — the quote path reads this ONCE per sale and resolves in memory.
     * Deliberately not a per-line query: adding a database round trip per basket line would put the pricing
     * table on the checkout hot path.
     */
    @Query("SELECT p FROM PriceRuleEntity p WHERE p.active = true AND " + SCOPE)
    List<PriceRuleEntity> findActiveScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
