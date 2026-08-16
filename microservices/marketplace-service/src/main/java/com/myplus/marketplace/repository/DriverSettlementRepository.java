package com.myplus.marketplace.repository;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.marketplace.entity.DriverSettlement;

/** OMS O7 D5 — day-end remittances (V22). */
@Repository
public interface DriverSettlementRepository extends JpaRepository<DriverSettlement, Long> {

    /**
     * Next per-org settlement number. MAX+1 inside the creating transaction, made safe by
     * UNIQUE(organization_id, settlement_seq) — a read-then-write check alone loses to a concurrent settlement.
     * COALESCE so the first remittance in a new org starts at 1. Exactly the {@code SHP-} recipe (V15).
     */
    @Query("SELECT COALESCE(MAX(s.settlementSeq), 0) FROM DriverSettlement s WHERE s.organizationId = :orgId")
    long maxSeqForOrg(@Param("orgId") Long orgId);

    /**
     * The tenant's remittances, newest first. Paged — a distributor settles drivers every working day, so this
     * list grows without bound and an unpaged read is OMS-7 again.
     *
     * <p>NULL-org fallback exactly as {@code findScoped} does everywhere else, so pre-migration rows behave
     * identically.
     */
    @Query("SELECT s FROM DriverSettlement s WHERE (s.organizationId = :orgId "
         + "  OR (s.organizationId IS NULL AND s.settledByUserId = :userId)) "
         + "AND (:driver IS NULL OR s.driverName = :driver) "
         + "AND (:from IS NULL OR s.settlementDate >= :from) "
         + "AND (:to IS NULL OR s.settlementDate <= :to) "
         + "ORDER BY s.settlementDate DESC, s.id DESC")
    Page<DriverSettlement> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                      @Param("driver") String driver,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to,
                                      Pageable pageable);

    /**
     * ONE settlement, org-scoped — the anti-IDOR read, because the id arrives from a path.
     *
     * <p>D2's rule: whether a read needs scoping depends on where the id CAME FROM, not on which method reads
     * it. Another tenant's settlement reads as absent, identically to a missing one.
     */
    @Query("SELECT s FROM DriverSettlement s WHERE s.id = :id AND (s.organizationId = :orgId "
         + "OR (s.organizationId IS NULL AND s.settledByUserId = :userId))")
    java.util.Optional<DriverSettlement> findByIdScoped(@Param("id") Long id,
                                                        @Param("orgId") Long orgId, @Param("userId") Long userId);
}
