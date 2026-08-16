package com.myplus.marketplace.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.marketplace.entity.DeliveryRecord;

/** OMS O7 D4/D5 — the delivery outcomes keyed against one order, and the cash they declared. */
@Repository
public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, Long> {

    /** Bounded by the order — a handful of rows by construction, and the caller already holds the order. */
    List<DeliveryRecord> findByOrderIdOrderByRecordedAtAsc(Long orderId);

    /** Has this parcel already been keyed? The idempotency guard the trade contract deliberately does not have. */
    List<DeliveryRecord> findByShipmentId(Long shipmentId);

    /** The collections a confirmed remittance swept up, for its detail read. */
    List<DeliveryRecord> findBySettlementIdOrderByRecordedAtAsc(Long settlementId);

    /**
     * O7 D5 — <b>the control surface</b>: cash a driver keyed as collected and has not yet handed over.
     *
     * <p>{@code settlementId IS NULL} is the whole definition of "open", and {@code amountCollected > 0} keeps
     * out the credit deliveries, which are the majority of this trade and have nothing to remit. Oldest first,
     * because the question an admin asks of this list is "what has been sitting the longest".
     *
     * <p>Served by {@code idx_delivery_open (organization_id, settlement_id, recorded_at)} (V22). D3b: the
     * V21 {@code idx_delivery_org} answers "this org's deliveries" and cannot serve this without a filesort
     * over every delivery the tenant has ever keyed.
     *
     * <p>Paged. A distributor keys dozens a day and this read is the day-end screen, so an unbounded version
     * would be OMS-7 in a new place.
     */
    @Query("""
            SELECT d FROM DeliveryRecord d
             WHERE (d.organizationId = :orgId OR (d.organizationId IS NULL AND d.recordedByUserId = :userId))
               AND d.settlementId IS NULL
               AND d.amountCollected IS NOT NULL AND d.amountCollected > 0
               AND (:driver IS NULL OR d.deliveredBy = :driver)
               AND (:from IS NULL OR d.recordedAt >= :from)
               AND (:to IS NULL OR d.recordedAt <= :to)
             ORDER BY d.recordedAt ASC, d.id ASC
            """)
    Page<DeliveryRecord> findOpenCollections(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                             @Param("driver") String driver,
                                             @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             Pageable pageable);

    /**
     * The rows a settlement is about to remit — re-read INSIDE the settling transaction, scoped, and still open.
     *
     * <p>Deliberately not {@code findAllById}: the ids arrive from a browser, so the tenant predicate has to be
     * in the query rather than applied afterwards, and a row another admin remitted thirty seconds ago must not
     * come back at all.
     */
    @Query("""
            SELECT d FROM DeliveryRecord d
             WHERE d.id IN :ids
               AND (d.organizationId = :orgId OR (d.organizationId IS NULL AND d.recordedByUserId = :userId))
               AND d.settlementId IS NULL
             ORDER BY d.recordedAt ASC, d.id ASC
            """)
    List<DeliveryRecord> findClaimable(@Param("ids") List<Long> ids,
                                       @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * <b>Claim these collections for a settlement — the once-only guarantee, enforced by the database.</b>
     *
     * <p>{@code AND d.settlementId IS NULL} in the UPDATE itself is what makes two admins settling the same
     * driver at the same moment safe: the loser's affected count comes back short and its transaction throws.
     * A read-then-write check in Java would let both through.
     *
     * <p><b>This runs BEFORE any receipt is posted.</b> Claim first, act second — if the claim loses the race,
     * nothing has been sent to business-service and there is nothing to unwind. The reverse ordering would
     * leave receipts committed remotely against a settlement that rolled back locally.
     */
    // clearAutomatically is deliberately OFF: the caller holds these rows and goes on to stamp each one's
    // receipt number. Detaching them here would make that a second round of reads, and a bulk update that
    // silently invalidates the caller's objects is a trap the next person would fall into.
    @Modifying(flushAutomatically = true)
    @Query("UPDATE DeliveryRecord d SET d.settlementId = :settlementId "
         + " WHERE d.id IN :ids AND d.settlementId IS NULL")
    int claim(@Param("ids") List<Long> ids, @Param("settlementId") Long settlementId);
}
