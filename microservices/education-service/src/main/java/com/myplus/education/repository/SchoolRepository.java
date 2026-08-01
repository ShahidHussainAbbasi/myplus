package com.myplus.education.repository;

import com.myplus.education.entity.School;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchoolRepository extends JpaRepository<School, Long> {
    Page<School> findByUserId(Long userId, Pageable pageable);
    List<School> findByUserId(Long userId);
    long countByUserId(Long userId);

    /**
     * Tenant-scoped read. Returns rows belonging to the active organization, plus the caller's own
     * not-yet-migrated rows (organization_id IS NULL) so single-owner data keeps working during the
     * userId-&gt;org migration. New writes always stamp organization_id, so the NULL set drains over time.
     */
    @Query("select s from School s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    List<School> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Anti-IDOR: resolve ONE branch by a client-supplied id under the same tenant rule as
     * {@link #findScoped}. An edit that fetched by bare id then stamped organizationId would move
     * another tenant's branch into the caller's org — taking the branch, and every student filed
     * under it, from its owner.
     */
    @Query("select s from School s where s.id = :id and (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId))")
    java.util.Optional<School> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                              @Param("userId") Long userId);

    // ── Finding D: the duplicate check as an indexed EXISTS, not a full-table load ───────────────
    // Case-insensitivity comes from the column COLLATION (utf8mb4 …_ci), not from lower(): wrapping
    // the column in a function would also defeat the index (slice doc D4, recorded in V16).
    @Query("select case when count(sc) > 0 then true else false end from School sc "
            + "where (sc.organizationId = :orgId or (sc.organizationId is null and sc.userId = :userId)) "
            + "and sc.branchName = :branchName")
    boolean existsByBranchNameScoped(@Param("branchName") String branchName, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Values already duplicated in this tenant — enables the UNIQUE follow-up on clean data (D3). */
    @Query("select sc.branchName from School sc where (sc.organizationId = :orgId "
            + "or (sc.organizationId is null and sc.userId = :userId)) and sc.branchName is not null "
            + "group by sc.branchName having count(sc) > 1")
    List<String> findDuplicateBranchNamesScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
