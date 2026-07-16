package com.myplus.campaign.repository;

import com.myplus.campaign.entity.Segment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, Long> {

    // Tenant scope: active-org rows + the caller's org-NULL legacy rows (see CampaignRepository.SCOPE).
    String SCOPE = "(s.organizationId = :orgId OR (s.organizationId IS NULL AND s.createdBy = :userId))";

    @Query("select s from Segment s where " + SCOPE)
    Page<Segment> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("select s from Segment s where s.id = :id and " + SCOPE)
    Optional<Segment> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
