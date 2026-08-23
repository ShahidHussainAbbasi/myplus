package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.InstallmentReminder;

/**
 * INST-3a — the chase log.
 *
 * <p><b>Every query here is org-scoped except one, and the exception is named so it cannot be used by
 * accident.</b> The scanner runs on a {@code @Scheduled} thread with no authenticated user; the worklist and
 * the mark-as-chased action are ordinary request threads and are scoped and anti-IDOR checked. Mixing the two
 * up leaks one tenant's debtors to another, which is the assertion the gate leads with.
 */
public interface InstallmentReminderRepo extends JpaRepository<InstallmentReminder, Long> {

    /**
     * The scanner's idempotency check.
     *
     * <p>Deliberately NOT org-scoped: {@code dedupe_key} is globally unique by constraint, and scoping this
     * lookup would let a key collide across tenants and then fail on the INSERT instead — turning a silent
     * no-op into a stack trace on a timer.
     */
    Optional<InstallmentReminder> findByDedupeKey(String dedupeKey);

    /**
     * The collections worklist: this org, most urgent first.
     *
     * <p>Outstanding chases before completed ones, then oldest due date — a shopkeeper works down this list
     * from the top and should reach the person who has been owing longest without sorting anything.
     */
    @Query("SELECT r FROM InstallmentReminder r WHERE r.organizationId = :orgId "
         + "AND (:stage IS NULL OR r.stage = :stage) "
         + "ORDER BY CASE WHEN r.actedAt IS NULL THEN 0 ELSE 1 END ASC, r.dueDate ASC, r.id ASC")
    List<InstallmentReminder> findScoped(@Param("orgId") Long orgId, @Param("stage") String stage);

    /**
     * Anti-IDOR read for the mark-as-chased action.
     *
     * <p>By id AND org, never by id alone: {@code findById(x)} on a guessed number is how one tenant edits
     * another's records, and every write path in this service is expected to prove ownership in the query
     * rather than after it.
     */
    @Query("SELECT r FROM InstallmentReminder r WHERE r.id = :id AND r.organizationId = :orgId")
    Optional<InstallmentReminder> findScopedById(@Param("id") Long id, @Param("orgId") Long orgId);
}
