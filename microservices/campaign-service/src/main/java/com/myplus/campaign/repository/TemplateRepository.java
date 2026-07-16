package com.myplus.campaign.repository;

import com.myplus.campaign.entity.Template;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findByTypeAndIsActiveTrue(Template.Type type);
    Page<Template> findByIsActiveTrue(Pageable pageable);

    // Tenant scope: active-org rows + the caller's org-NULL legacy rows (see CampaignRepository.SCOPE).
    String SCOPE = "(t.organizationId = :orgId OR (t.organizationId IS NULL AND t.createdBy = :userId))";

    @Query("select t from Template t where " + SCOPE)
    Page<Template> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("select t from Template t where t.isActive = true and " + SCOPE)
    Page<Template> findActiveScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("select t from Template t where t.type = :type and t.isActive = true and " + SCOPE)
    List<Template> findActiveByTypeScoped(@Param("type") Template.Type type,
                                          @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("select t from Template t where t.id = :id and " + SCOPE)
    Optional<Template> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
