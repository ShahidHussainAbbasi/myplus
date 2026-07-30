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
}
