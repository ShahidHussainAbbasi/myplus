package com.myplus.campaign.repository;

import com.myplus.campaign.entity.Audience;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AudienceRepository extends JpaRepository<Audience, Long> {

    // Tenant scope: active-org rows + the caller's org-NULL legacy rows (see CampaignRepository.SCOPE).
    String SCOPE = "(a.organizationId = :orgId OR (a.organizationId IS NULL AND a.createdBy = :userId))";

    @Query("select a from Audience a where " + SCOPE)
    Page<Audience> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("select a from Audience a where a.id = :id and " + SCOPE)
    Optional<Audience> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
