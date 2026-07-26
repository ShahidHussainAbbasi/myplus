package com.myplus.party.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.party.entity.PartyRoleLink;

/** Role-index writes (idempotent upsert) + the single org-scoped read behind the contact view. */
@Repository
public interface PartyRoleLinkRepository extends JpaRepository<PartyRoleLink, Long> {

    /**
     * Idempotent link write. The bridge is retried by design (edit paths re-stamp, breaker cooldowns re-fire, the
     * backfill is re-runnable), so a repeat must be a no-op rather than a duplicate row or a constraint violation —
     * hence ON DUPLICATE KEY UPDATE on {@code uq_role_link} instead of a read-then-insert (also one round trip, not two).
     */
    @Modifying
    @Query(value = "INSERT INTO party_role_link (organization_id, party_id, module, role, local_id, label, created_at, updated_at) "
                 + "VALUES (:orgId, :partyId, :module, :role, :localId, :label, NOW(), NOW()) "
                 + "ON DUPLICATE KEY UPDATE label = VALUES(label), updated_at = NOW()", nativeQuery = true)
    void upsertLink(@Param("orgId") Long orgId, @Param("partyId") Long partyId, @Param("module") String module,
                    @Param("role") String role, @Param("localId") Long localId, @Param("label") String label);

    /** The contact view read: one hit on idx_role_link_org_party. Org-scoped with the same NULL-fallback as party. */
    @Query("SELECT l FROM PartyRoleLink l WHERE l.partyId = :partyId "
         + "AND (l.organizationId = :orgId OR l.organizationId IS NULL) ORDER BY l.module ASC, l.role ASC, l.localId ASC")
    List<PartyRoleLink> findForParty(@Param("partyId") Long partyId, @Param("orgId") Long orgId);
}
