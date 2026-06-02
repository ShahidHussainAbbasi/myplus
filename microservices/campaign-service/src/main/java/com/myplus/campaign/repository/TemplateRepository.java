package com.myplus.campaign.repository;

import com.myplus.campaign.entity.Template;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findByTypeAndIsActiveTrue(Template.Type type);
    Page<Template> findByIsActiveTrue(Pageable pageable);
}
