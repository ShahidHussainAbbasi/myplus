package com.myplus.clinical.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.clinical.entity.QueueToken;

/** HMS S2 — today's line. Every read is scoped to the caller's organisation (from the token, never the request). */
public interface QueueTokenRepo extends JpaRepository<QueueToken, Long> {

    Optional<QueueToken> findByIdAndOrganizationId(Long id, Long organizationId);

    /** The reception board: every doctor's line for the day, in doctor then token order. */
    @Query("select t from QueueToken t where t.organizationId = :org and t.visitDate = :day order by t.providerName, t.tokenNo")
    List<QueueToken> board(@Param("org") Long org, @Param("day") LocalDate day);

    /** One doctor's line for the day, in token order. */
    @Query("select t from QueueToken t where t.organizationId = :org and t.providerId = :provider and t.visitDate = :day order by t.tokenNo")
    List<QueueToken> line(@Param("org") Long org, @Param("provider") Long provider, @Param("day") LocalDate day);

    /** Against the daily limit: every token issued that day except the cancelled ones (a no-show used its place). */
    @Query("select count(t) from QueueToken t where t.organizationId = :org and t.providerId = :provider and t.visitDate = :day and t.status <> 'CANCELLED'")
    long countIssued(@Param("org") Long org, @Param("provider") Long provider, @Param("day") LocalDate day);

    /** The patient's tokens of the day in the given states (multi-doctor rule and "with Dr X now"). */
    @Query("select t from QueueToken t where t.organizationId = :org and t.patientId = :patient and t.visitDate = :day and t.status in :states")
    List<QueueToken> patientTokens(@Param("org") Long org, @Param("patient") Long patient, @Param("day") LocalDate day,
                                   @Param("states") Collection<String> states);

    /** The patient's most recent token — whose doctor the front desk preselects next time (02c). */
    Optional<QueueToken> findFirstByOrganizationIdAndPatientIdOrderByIdDesc(Long organizationId, Long patientId);

    Optional<QueueToken> findFirstByOrganizationIdAndVisitDateAndTokenLabel(Long organizationId, LocalDate day, String label);

    /** The next patient for "call next": the lowest WAITING token of the doctor's day. */
    Optional<QueueToken> findFirstByOrganizationIdAndProviderIdAndVisitDateAndStatusOrderByTokenNoAsc(
            Long organizationId, Long providerId, LocalDate day, String status);

    /**
     * ONE status change, and only from an allowed state: 1 row = done, 0 rows = refused (someone else moved it
     * first, or the move is not allowed from where it is). Never load-modify-save — two doctors calling the same
     * token must not both win. The version is bumped here because a bulk UPDATE bypasses JPA's @Version.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE queue_token SET status = :to, version = version + 1, updated_at = :now, "
            + "called_at = CASE WHEN :to = 'CALLED' THEN :now ELSE called_at END, "
            + "called_by = CASE WHEN :to = 'CALLED' THEN :user ELSE called_by END, "
            + "started_at = CASE WHEN :to = 'IN_CONSULTATION' AND started_at IS NULL THEN :now ELSE started_at END, "
            + "parked_at = CASE WHEN :to = 'PARKED' THEN :now ELSE parked_at END, "
            + "park_reason = CASE WHEN :to = 'PARKED' THEN :reason ELSE park_reason END, "
            + "completed_at = CASE WHEN :to = 'COMPLETED' THEN :now ELSE completed_at END, "
            + "cancelled_at = CASE WHEN :to IN ('CANCELLED', 'NO_SHOW') THEN :now ELSE cancelled_at END, "
            + "cancel_reason = CASE WHEN :to IN ('CANCELLED', 'NO_SHOW') THEN :reason ELSE cancel_reason END "
            + "WHERE id = :id AND organization_id = :org AND status IN (:from)", nativeQuery = true)
    int transition(@Param("id") Long id, @Param("org") Long org, @Param("from") Collection<String> from,
                   @Param("to") String to, @Param("now") LocalDateTime now, @Param("user") Long user,
                   @Param("reason") String reason);
}
