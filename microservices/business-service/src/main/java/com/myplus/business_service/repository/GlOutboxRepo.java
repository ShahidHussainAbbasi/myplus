package com.myplus.business_service.repository;

import com.myplus.business_service.entity.GlOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Audit #4: the GL posting outbox. Relay drains PENDING rows; the debug read is tenant-scoped. */
@Repository
public interface GlOutboxRepo extends JpaRepository<GlOutbox, Long> {

    /** A batch of undelivered events for the relay (oldest first). */
    List<GlOutbox> findTop100ByStatusOrderByIdAsc(String status);

    /** Recent outbox rows for the caller's tenant (debug/verification read). */
    @Query("select o from GlOutbox o where (o.organizationId = :orgId or (o.organizationId is null and o.userId = :userId)) "
            + "order by o.id desc")
    List<GlOutbox> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
