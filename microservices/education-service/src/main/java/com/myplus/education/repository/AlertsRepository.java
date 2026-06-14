package com.myplus.education.repository;

import com.myplus.education.entity.Alerts;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertsRepository extends JpaRepository<Alerts, Long> {
    Page<Alerts> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select a from Alerts a where a.organizationId = :orgId "
            + "or (a.organizationId is null and a.userId = :userId)")
    List<Alerts> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}

