package com.myplus.catalog.repository;

import com.myplus.catalog.entity.BonusSchemeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Bonus schemes, tenant-scoped (task #17 P1).
 *
 * <p>Every read carries the scope predicate INSIDE the query — the same arrangement
 * {@code PriceRuleRepository} uses — so no caller can forget it. Scheme CODES are operator-chosen and will
 * collide across tenants ("SUP-OIL-10-1" is an obvious name for anyone), which is exactly why the predicate,
 * and not the key, is what keeps them apart.
 */
public interface BonusSchemeRepository extends JpaRepository<BonusSchemeEntity, Long> {

    String SCOPE = "(b.organizationId = :orgId OR (b.organizationId IS NULL AND b.userId = :userId))";

    @Query("SELECT b FROM BonusSchemeEntity b WHERE " + SCOPE + " ORDER BY b.priority DESC, b.id ASC")
    List<BonusSchemeEntity> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: by id AND scope, never by id alone. */
    @Query("SELECT b FROM BonusSchemeEntity b WHERE b.id = :id AND " + SCOPE)
    Optional<BonusSchemeEntity> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                               @Param("userId") Long userId);

    /**
     * ACTIVE schemes only. Date-window filtering happens in Java rather than SQL because "live" is evaluated
     * against the transaction's date, and the resolver already holds the set in memory to pick one winner —
     * the same shape price-rule resolution uses.
     */
    @Query("SELECT b FROM BonusSchemeEntity b WHERE b.status = 'ACTIVE' AND " + SCOPE
         + " ORDER BY b.priority DESC, b.id ASC")
    List<BonusSchemeEntity> findActiveScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One scheme by its operator-facing code, within the tenant. Backs the unique-code rule. */
    @Query("SELECT b FROM BonusSchemeEntity b WHERE b.code = :code AND " + SCOPE)
    Optional<BonusSchemeEntity> findByCodeScoped(@Param("code") String code, @Param("orgId") Long orgId,
                                                  @Param("userId") Long userId);
}
