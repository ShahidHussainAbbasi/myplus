package com.myplus.campaign.repository;

import com.myplus.campaign.entity.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    Page<Campaign> findByStatus(Campaign.Status status, Pageable pageable);
    Page<Campaign> findByCreatedBy(Long createdBy, Pageable pageable);
    List<Campaign> findByScheduledAtBetween(LocalDateTime start, LocalDateTime end);

    // Tenant scope: active-org rows + the caller's not-yet-migrated (org-NULL) rows. Standard org-scope
    // predicate shared with every other service. Reads and by-id lookups both go through it.
    String SCOPE = "(c.organizationId = :orgId OR (c.organizationId IS NULL AND c.createdBy = :userId))";

    @Query("select c from Campaign c where " + SCOPE)
    Page<Campaign> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("select c from Campaign c where c.id = :id and " + SCOPE)
    Optional<Campaign> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
