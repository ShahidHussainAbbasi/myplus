package com.myplus.education.repository;

import com.myplus.education.entity.GuardianPortalAccess;
import com.myplus.education.entity.PortalStatus;
import com.myplus.education.entity.PortalSubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuardianPortalAccessRepository extends JpaRepository<GuardianPortalAccess, Long> {

    /**
     * THE authentication lookup: who is this signed-in email, and may they still use the portal?
     *
     * Runs on every portal request, which is why (organization_id, email, status) is indexed.
     *
     * NOTE the org predicate has NO userId NULL-fallback, unlike every staff read. That fallback exists so
     * a staff member sees rows they created before org-scoping landed; a GUARDIAN has no such history, and
     * widening the predicate here would be widening an external principal's reach. Deliberately strict.
     */
    @Query("select a from GuardianPortalAccess a where a.organizationId = :orgId "
            + "and a.email = :email and a.status <> :excluded")
    Optional<GuardianPortalAccess> findLiveByEmail(@Param("email") String email,
                                                   @Param("excluded") PortalStatus excluded,
                                                   @Param("orgId") Long orgId);

    /** The school's admin list — who has been granted access, including revoked rows (kept, never deleted). */
    @Query("select a from GuardianPortalAccess a where a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId) order by a.guardianName")
    List<GuardianPortalAccess> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Is this guardian already invited? Prevents a duplicate before the UNIQUE key has to. */
    @Query("select a from GuardianPortalAccess a where a.organizationId = :orgId "
            + "and a.guardianId = :guardianId")
    Optional<GuardianPortalAccess> findByGuardianScoped(@Param("guardianId") Long guardianId,
                                                        @Param("orgId") Long orgId);

    /**
     * Slice 3.3 — the same authentication lookup, narrowed to ONE audience.
     *
     * <p>Separate from {@link #findLiveByEmail} rather than replacing it, because the subject type must be
     * part of the question. D6 refuses to provision a student on an address that already belongs to a
     * guardian, so in a correct database one email has one live row — but a lookup that would return the
     * WRONG audience's row if that rule were ever bypassed is a lookup that turns a provisioning bug into
     * a data-disclosure bug. Asking for the type costs nothing and cannot be got wrong later.
     *
     * <p>Same deliberate strictness as its sibling: no {@code userId} NULL-fallback for an external
     * principal.
     */
    @Query("select a from GuardianPortalAccess a where a.organizationId = :orgId "
            + "and a.email = :email and a.subjectType = :subjectType and a.status <> :excluded")
    Optional<GuardianPortalAccess> findLiveByEmailAndType(@Param("email") String email,
                                                          @Param("subjectType") PortalSubjectType subjectType,
                                                          @Param("excluded") PortalStatus excluded,
                                                          @Param("orgId") Long orgId);

    /** Is this subject already invited? Prevents a duplicate before the UNIQUE key has to. */
    @Query("select a from GuardianPortalAccess a where a.organizationId = :orgId "
            + "and a.subjectType = :subjectType and a.subjectId = :subjectId")
    Optional<GuardianPortalAccess> findBySubjectScoped(@Param("subjectType") PortalSubjectType subjectType,
                                                       @Param("subjectId") Long subjectId,
                                                       @Param("orgId") Long orgId);

    /** Anti-IDOR: resolve ONE access row by a client-supplied id within the caller's tenant. */
    @Query("select a from GuardianPortalAccess a where a.id = :id and (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId))")
    Optional<GuardianPortalAccess> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                                  @Param("userId") Long userId);
}
