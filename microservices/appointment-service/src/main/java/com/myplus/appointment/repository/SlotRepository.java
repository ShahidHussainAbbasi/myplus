package com.myplus.appointment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.appointment.entity.Slot;

@Repository
public interface SlotRepository extends JpaRepository<Slot, Long> {

    /** Everything a consumer published under one reference — "the slots for THIS parents' evening". */
    @Query("select s from Slot s where s.organizationId = :orgId and s.externalRef = :ref "
            + "order by s.startsAt, s.providerId")
    List<Slot> findByRefScoped(@Param("ref") String ref, @Param("orgId") Long orgId);

    /** One provider's slots in a window — what a teacher's own screen shows. */
    @Query("select s from Slot s where s.organizationId = :orgId and s.providerId = :providerId "
            + "and s.startsAt >= :from and s.startsAt < :to order by s.startsAt")
    List<Slot> findByProviderInWindow(@Param("providerId") Long providerId,
                                      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                      @Param("orgId") Long orgId);

    /**
     * Anti-IDOR: resolve ONE slot by a client-supplied id, within the caller's tenant.
     *
     * <p>Every booking call takes a slot id from the request, so this is the query that stops a caller in
     * org A booking a slot in org B. There is no unscoped {@code findById} on the booking path.
     */
    @Query("select s from Slot s where s.id = :id and s.organizationId = :orgId")
    Optional<Slot> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * Does this provider already have a slot starting at this instant?
     *
     * <p>Used to CONFIRM that a caught integrity violation really was {@code uk_slot_provider_time}, rather
     * than assuming it. Counting an unrelated failure as "already existed" would report a successful
     * publish for an evening that ended up with no slots at all.
     */
    boolean existsByOrganizationIdAndProviderIdAndStartsAt(Long orgId, Long providerId, LocalDateTime startsAt);
}
