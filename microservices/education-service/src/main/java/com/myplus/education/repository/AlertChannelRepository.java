package com.myplus.education.repository;

import com.myplus.education.entity.AlertChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertChannelRepository extends JpaRepository<AlertChannel, Long> {
    Page<AlertChannel> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select c from AlertChannel c where c.organizationId = :orgId "
            + "or (c.organizationId is null and c.userId = :userId)")
    List<AlertChannel> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
