package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OMS O5b — one parcel that physically left, for part or all of an order.
 *
 * <h3>Why this exists</h3>
 * Before O5b, "shipped" was a word a packer typed on the order header. Nothing recorded what went out, when, by
 * whom, or with what tracking — so an order could not ship in parts, and a customer could not be told where
 * anything was.
 *
 * <h3>A shipment moves no stock</h3>
 * O1 decrements inventory when the sale is recorded. This records what left against stock that has already gone
 * from the books. Decrementing again here would silently halve the shop's inventory.
 */
@Entity
@Table(name = "shipment", uniqueConstraints = {
        @UniqueConstraint(name = "uq_shipment_org_seq", columnNames = {"organization_id", "shipment_seq"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Shipment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Per-org running number; UNIQUE(organization_id, shipment_seq) is what makes MAX+1 race-safe. */
    @Column(name = "shipment_seq", nullable = false)
    private Long shipmentSeq;

    /** The merchant- and customer-facing reference, e.g. {@code SHP-000045}. */
    @Column(name = "shipment_no", nullable = false, length = 32)
    private String shipmentNo;

    /** Free text — what a small merchant actually has. Carrier API integration is O5c. */
    @Column(length = 120)
    private String carrier;

    @Column(name = "tracking_number", length = 120)
    private String trackingNumber;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "DISPATCHED";

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(length = 500)
    private String note;

    /** Two packers dispatching the same order at once. */
    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Bidirectional so the line's own INSERT carries the FK — see {@link ShipmentLine#getShipment()}. */
    @Builder.Default
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ShipmentLine> lines = new ArrayList<>();

    /** Add a line and set both sides — the only safe way to build the graph with an owning child. */
    public void addLine(ShipmentLine line) {
        line.setShipment(this);
        this.lines.add(line);
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (shippedAt == null) shippedAt = createdAt;
    }
}
