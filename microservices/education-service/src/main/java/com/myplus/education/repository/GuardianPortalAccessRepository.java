package com.myplus.education.repository;

import com.myplus.education.entity.GuardianPortalAccess;
import com.myplus.education.entity.PortalStatus;
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
     * a staff member sees rows they created before org-scoping landed; a PARENT has no such history, and
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

    /** Anti-IDOR: resolve ONE access row by a client-supplied id within the caller's tenant. */
    @Query("select a from GuardianPortalAccess a where a.id = :id and (a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId))")
    Optional<GuardianPortalAccess> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                                  @Param("userId") Long userId);
}
