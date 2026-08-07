package com.myplus.marketplace.repository;

import com.myplus.marketplace.entity.Shipment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Shipments for an order (OMS O5b). Always reached through an order that was itself scoped. */
@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByOrderIdOrderByShipmentSeqAsc(Long orderId);

    /**
     * Next per-org shipment number. MAX+1 inside the creating transaction, made safe by
     * UNIQUE(organization_id, shipment_seq) — a read-then-write check alone loses to a concurrent dispatch.
     * COALESCE so the first parcel in a new org starts at 1.
     */
    @Query("SELECT COALESCE(MAX(s.shipmentSeq), 0) FROM Shipment s WHERE s.organizationId = :orgId")
    long maxShipmentSeqForOrg(@Param("orgId") Long orgId);
}
