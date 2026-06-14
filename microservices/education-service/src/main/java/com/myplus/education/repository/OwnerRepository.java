package com.myplus.education.repository;

import com.myplus.education.entity.Owner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OwnerRepository extends JpaRepository<Owner, Long> {
    Page<Owner> findByUserId(Long userId, Pageable pageable);
    List<Owner> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select o from Owner o where o.organizationId = :orgId "
            + "or (o.organizationId is null and o.userId = :userId)")
    List<Owner> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
