package com.myplus.clinical.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.clinical.entity.RxTemplate;

/** HMS S3b-2 — the clinic's prescription templates. Every read is org-scoped. Their lines: {@link RxTemplateItemRepo}. */
public interface RxTemplateRepo extends JpaRepository<RxTemplate, Long> {

    List<RxTemplate> findByOrganizationIdAndStatusOrderByNameAsc(Long organizationId, String status);

    Optional<RxTemplate> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndNameKey(Long organizationId, String nameKey);

    long countByOrganizationIdAndStatus(Long organizationId, String status);
}
