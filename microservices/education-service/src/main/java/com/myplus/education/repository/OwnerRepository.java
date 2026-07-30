package com.myplus.education.repository;

import com.myplus.education.entity.Owner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OwnerRepository extends JpaRepository<Owner, Long> {
    Page<Owner> findByUserId(Long userId, Pageable pageable);
    List<Owner> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select o from Owner o where o.organizationId = :orgId "
            + "or (o.organizationId is null and o.userId = :userId)")
    List<Owner> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Anti-IDOR: resolve ONE row by an id the client supplied, under the same tenant rule as
     * {@link #findScoped}. An edit that fetched by bare id then stamped organizationId would move
     * another tenant's row into the caller's org — silently taking it from its owner.
     */
    @Query("select o from Owner o where o.id = :id and (o.organizationId = :orgId "
            + "or (o.organizationId is null and o.userId = :userId))")
    Optional<Owner> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
