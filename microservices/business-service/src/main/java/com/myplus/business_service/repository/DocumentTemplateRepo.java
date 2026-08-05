package com.myplus.business_service.repository;

import com.myplus.business_service.entity.DocumentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * B2B Phase 3g — document layouts, org-scoped (V35).
 *
 * <p>Follows the tenancy standard used by every other repo here: {@code findScoped} carries the NULL-org
 * fallback for pre-migration rows, and {@code findByIdScoped} is the anti-IDOR read for anything that takes
 * an id from the client. The anti-IDOR one matters more than usual here — without it a tenant could open,
 * clone or bind another tenant's layout, and a layout can carry their business name and licence number.
 */
public interface DocumentTemplateRepo extends JpaRepository<DocumentTemplate, Long> {

    @Query("select t from DocumentTemplate t where (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId)) order by t.docType, t.channel, t.name")
    List<DocumentTemplate> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR by-id read: an id from the client only resolves inside the caller's own tenant. */
    @Query("select t from DocumentTemplate t where t.id = :id and (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId))")
    Optional<DocumentTemplate> findByIdScoped(@Param("id") Long id,
                                              @Param("orgId") Long orgId,
                                              @Param("userId") Long userId);

    /**
     * The resolver's access path: this org's default layout for a document type and channel.
     *
     * <p>A channel-agnostic template ({@code channel is null}) is accepted as a fallback so an owner can
     * design ONE layout for both channels without having to save it twice.
     */
    @Query("select t from DocumentTemplate t where t.organizationId = :orgId and t.docType = :docType "
            + "and (t.channel = :channel or t.channel is null) and t.isDefault = true "
            + "order by case when t.channel is null then 1 else 0 end, t.id")
    List<DocumentTemplate> findDefaultFor(@Param("orgId") Long orgId,
                                          @Param("docType") String docType,
                                          @Param("channel") String channel);
}
