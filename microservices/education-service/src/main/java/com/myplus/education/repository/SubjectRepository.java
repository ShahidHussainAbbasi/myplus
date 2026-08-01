package com.myplus.education.repository;

import com.myplus.education.entity.Subject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long> {
    Page<Subject> findByUserId(Long userId, Pageable pageable);
    List<Subject> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select s from Subject s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    List<Subject> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Anti-IDOR: resolve ONE row by an id the client supplied, under the same tenant rule as
     * {@link #findScoped}. An edit that fetched by bare id then stamped organizationId would move
     * another tenant's row into the caller's org — silently taking it from its owner.
     */
    @Query("select s from Subject s where s.id = :id and (s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId))")
    Optional<Subject> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // ── Finding D: the duplicate check as an indexed EXISTS, not a full-table load ───────────────
    // Case-insensitivity comes from the column COLLATION (utf8mb4 …_ci), not from lower(): wrapping
    // the column in a function would also defeat the index (slice doc D4, recorded in V16).
    @Query("select case when count(sub) > 0 then true else false end from Subject sub "
            + "where (sub.organizationId = :orgId or (sub.organizationId is null and sub.userId = :userId)) "
            + "and sub.name = :name")
    boolean existsByNameScoped(@Param("name") String name, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Values already duplicated in this tenant — enables the UNIQUE follow-up on clean data (D3). */
    @Query("select sub.name from Subject sub where (sub.organizationId = :orgId "
            + "or (sub.organizationId is null and sub.userId = :userId)) and sub.name is not null "
            + "group by sub.name having count(sub) > 1")
    List<String> findDuplicateNamesScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
