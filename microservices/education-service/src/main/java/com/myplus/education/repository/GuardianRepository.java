package com.myplus.education.repository;

import com.myplus.education.entity.Guardian;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuardianRepository extends JpaRepository<Guardian, Long> {
    Page<Guardian> findByUserId(Long userId, Pageable pageable);
    List<Guardian> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select g from Guardian g where g.organizationId = :orgId "
            + "or (g.organizationId is null and g.userId = :userId)")
    List<Guardian> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Anti-IDOR: resolve ONE row by an id the client supplied, under the same tenant rule as
     * {@link #findScoped}. An edit that fetched by bare id then stamped organizationId would move
     * another tenant's row into the caller's org — silently taking it from its owner.
     */
    @Query("select g from Guardian g where g.id = :id and (g.organizationId = :orgId "
            + "or (g.organizationId is null and g.userId = :userId))")
    Optional<Guardian> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
