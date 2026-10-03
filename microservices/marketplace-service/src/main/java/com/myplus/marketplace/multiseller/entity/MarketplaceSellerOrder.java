package com.myplus.marketplace.multiseller.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1e — one seller's part of a marketplace order and its acceptance window (V28, source §10).
 * {@code acceptanceStatus} follows {@code MarketplaceStateMachines.SELLER_ORDER}.
 */
@Entity
@Table(name = "mkt_seller_order")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceSellerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mkt_order_id", nullable = false)
    private Long mktOrderId;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "acceptance_status", nullable = false, length = 16)
    private String acceptanceStatus;

    @Column(name = "accept_by")
    private LocalDateTime acceptBy;

    /** The trade stock hold's key, {@code MKT-SO-{id}-HOLD} once the id is known. Unique. */
    @Column(name = "hold_key", nullable = false, length = 64)
    private String holdKey;

    @Column(name = "held", nullable = false)
    private Boolean held;

    @Column(name = "store_order_id")
    private Long storeOrderId;

    @Column(name = "store_order_no", length = 40)
    private String storeOrderNo;

    @Column(name = "invoice_no", length = 40)
    private String invoiceNo;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
