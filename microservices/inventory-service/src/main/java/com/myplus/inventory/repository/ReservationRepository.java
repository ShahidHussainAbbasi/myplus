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
}
