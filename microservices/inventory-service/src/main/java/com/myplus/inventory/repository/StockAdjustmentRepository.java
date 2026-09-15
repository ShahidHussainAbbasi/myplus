package com.myplus.inventory.repository;

import com.myplus.inventory.entity.StockAdjustment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Tenant-scoped reads of stock corrections (BLK-5).
 *
 * <p>The unscoped {@code findByProductId} that lived here is GONE: it had no caller, and a read keyed only by a
 * product id is exactly the shape that hands one tenant another's history (anti-IDOR, ARCHITECTURE-MULTITENANCY).
 *
 * <p>SCOPE is inventory's standard clause with {@code adjustedBy} as the author column. A pre-BLK-5 row has no
 * tenant and is visible only to its author — and those rows carry no author either, so no scoped read returns them.
 */
@Repository
public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    String SCOPE = "(a.organizationId = :orgId OR (a.organizationId IS NULL AND a.adjustedBy = :userId))";

    /**
     * The adjustment a key already recorded, oldest first. A List rather than an Optional: within a tenant
     * {@code uq_adj_org_idem} allows one row, but NULL-org rows are not covered by it (NULLs are distinct), and a
     * second match must not turn a replay into an exception.
     */
    @Query("SELECT a FROM StockAdjustment a WHERE a.idempotencyKey = :key AND " + SCOPE + " ORDER BY a.id ASC")
    List<StockAdjustment> findByIdempotencyKeyScoped(@Param("key") String key,
                                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** A product's corrections within the caller's tenant, newest first, bounded by the page. */
    @Query("SELECT a FROM StockAdjustment a WHERE a.productId = :productId AND " + SCOPE + " ORDER BY a.id DESC")
    List<StockAdjustment> findByProductScoped(@Param("productId") Long productId,
                                              @Param("orgId") Long orgId, @Param("userId") Long userId,
                                              Pageable page);
}
