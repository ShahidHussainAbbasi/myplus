package com.myplus.marketplace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.myplus.marketplace.entity.DeliveryRecord;

/** OMS O7 D4 — the delivery outcomes keyed against one order, oldest first (V21's idx_delivery_order). */
@Repository
public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, Long> {

    /** Bounded by the order — a handful of rows by construction, and the caller already holds the order. */
    List<DeliveryRecord> findByOrderIdOrderByRecordedAtAsc(Long orderId);

    /** Has this parcel already been keyed? The idempotency guard the trade contract deliberately does not have. */
    List<DeliveryRecord> findByShipmentId(Long shipmentId);
}
