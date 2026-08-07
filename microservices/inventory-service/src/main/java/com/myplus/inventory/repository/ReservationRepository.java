package com.myplus.inventory.repository;

import com.myplus.inventory.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Tenant-scoped reservation lookups (slice 33, Phase 6a). */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    String SCOPE = "(r.organizationId = :orgId OR (r.organizationId IS NULL AND r.userId = :userId))";

    @Query("SELECT r FROM Reservation r WHERE r.reservationId = :rid AND " + SCOPE)
    Optional<Reservation> findByReservationIdScoped(@Param("rid") String rid, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT r FROM Reservation r WHERE r.idempotencyKey = :key AND " + SCOPE)
    Optional<Reservation> findByIdempotencyKeyScoped(@Param("key") String key, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // ── OMS O5a — expiry sweep ────────────────────────────────────────────────────────────────────────────

    /**
     * Holds that have outlived their deadline (OMS-6).
     *
     * <p>Deliberately NOT org-scoped: the sweeper is a system job with no tenant identity, and a leak in one
     * tenant is not another tenant's business to clean up — but nor can it be left, so the job runs across all
     * of them. Each candidate's own {@code organizationId} is what later resolves its TTL.
     *
     * <p>{@code expiresAt IS NULL} rows are excluded by the comparison itself: a null deadline means expiry is
     * switched off for that tenant, or the row predates V6. Neither should be swept automatically.
     *
     * <p>Bounded by the caller's {@code Pageable}. The first run after this ships may face a backlog of leaks
     * accumulated over months; there is no reason to clear it in one transaction, and every reason not to.
     * Ordered oldest-first so the most-stuck stock comes back first.
     */
    @Query("SELECT r FROM Reservation r WHERE r.status = com.myplus.commerce.contracts.dto.ReservationStatus.RESERVED "
            + "AND r.expiresAt IS NOT NULL AND r.expiresAt < :now ORDER BY r.expiresAt ASC")
    java.util.List<Reservation> findExpired(@Param("now") java.time.LocalDateTime now,
                                            org.springframework.data.domain.Pageable page);

    /** The same, for ONE tenant — what the manual sweep endpoint uses so an operator only ever frees their own. */
    @Query("SELECT r FROM Reservation r WHERE r.status = com.myplus.commerce.contracts.dto.ReservationStatus.RESERVED "
            + "AND r.expiresAt IS NOT NULL AND r.expiresAt < :now AND " + SCOPE + " ORDER BY r.expiresAt ASC")
    java.util.List<Reservation> findExpiredScoped(@Param("now") java.time.LocalDateTime now,
                                                  @Param("orgId") Long orgId, @Param("userId") Long userId,
                                                  org.springframework.data.domain.Pageable page);

    /**
     * Re-read one reservation under a write lock, so the sweeper cannot race a concurrent confirm.
     *
     * <p>This is the difference between fixing a stock leak and creating a stock overstatement: between the
     * candidate query and the update, a confirm may land. Returning the hold then would put back stock that has
     * just been sold, and the shop would oversell. The status is re-checked inside the locked transaction.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> lockById(@Param("id") Long id);
}
