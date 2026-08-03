package com.myplus.business_service.repository;

import com.myplus.business_service.entity.SellBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Batch traceability rows for sale lines (slice b2b-P3b-2). */
@Repository
public interface SellBatchRepo extends JpaRepository<SellBatch, Long> {

    /** Every batch consumed by the given sale lines — one query for a whole invoice, never one per line. */
    @Query("SELECT b FROM SellBatch b WHERE b.sellId IN :sellIds ORDER BY b.id ASC")
    List<SellBatch> findBySellIds(@Param("sellIds") List<Long> sellIds);

    /**
     * A RECALL, executed the way a regulator expects: every sale line that shipped this batch, within the
     * tenant. Org-scoped like every read here — one shop's recall must never surface another's sales.
     */
    @Query("SELECT b FROM SellBatch b WHERE b.organizationId = :orgId AND b.batchNo = :batchNo ORDER BY b.id ASC")
    List<SellBatch> findByBatchScoped(@Param("orgId") Long orgId, @Param("batchNo") String batchNo);
}
