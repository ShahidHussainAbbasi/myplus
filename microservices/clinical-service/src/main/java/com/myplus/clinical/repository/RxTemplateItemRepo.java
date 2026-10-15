package com.myplus.clinical.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.clinical.entity.RxTemplateItem;

/**
 * HMS S3b-2 — the lines of the clinic's templates.
 *
 * <p>{@code itemsOf} lives HERE, on the repository of the entity it returns. Declared on RxTemplateRepo, Spring Data
 * read the foreign return type as a DTO projection and rewrote the query into {@code SELECT new RxTemplateItem(*)},
 * a syntax error at runtime — every template read was a 500 (S3b-2 gate, 2026-10-10), invisible to mocked unit tests.
 */
public interface RxTemplateItemRepo extends JpaRepository<RxTemplateItem, Long> {

    /** The lines of several templates in one read (the list shows them all). */
    @Query("SELECT i FROM RxTemplateItem i WHERE i.organizationId = :org AND i.templateId IN :ids ORDER BY i.templateId, i.lineNo")
    List<RxTemplateItem> itemsOf(@Param("org") Long org, @Param("ids") Collection<Long> ids);
}
