package com.myplus.audit.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.audit.entity.AuditEvent;

/** Append-only store: insert (inherited save) + idempotency check + tenant-scoped reads. No update/delete surface. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    boolean existsByOrganizationIdAndEventKey(Long organizationId, String eventKey);

    @Query("select a from AuditEvent a where a.organizationId = :orgId order by a.id desc")
    List<AuditEvent> findByOrg(@Param("orgId") Long orgId, Pageable pageable);

    @Query("select a from AuditEvent a where a.organizationId = :orgId and a.action = :action order by a.id desc")
    List<AuditEvent> findByOrgAndAction(@Param("orgId") Long orgId, @Param("action") String action, Pageable pageable);

    /** E5b — one business's trail by ONE kind of actor: the platform's own actions for an operator with no session. */
    @Query("select a from AuditEvent a where a.organizationId = :orgId and a.actorType = :actorType order by a.id desc")
    List<AuditEvent> findByOrgAndActorType(@Param("orgId") Long orgId, @Param("actorType") String actorType, Pageable pageable);

    @Query("select a from AuditEvent a where a.organizationId = :orgId and a.action = :action"
            + " and a.actorType = :actorType order by a.id desc")
    List<AuditEvent> findByOrgActionAndActorType(@Param("orgId") Long orgId, @Param("action") String action,
                                                 @Param("actorType") String actorType, Pageable pageable);
}
