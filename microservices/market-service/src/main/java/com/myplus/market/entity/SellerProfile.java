package com.myplus.market.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A tenant's application to sell on the marketplace, and then its standing there.
 *
 * <p>{@code sellerOrganizationId} is always the caller's own org from the token (source design §22: the browser
 * cannot choose the stock owner). One row per org — UNIQUE — so applying is an upsert.
 *
 * <p>Phase 1 has ONE pickup point per seller ({@code pickupAddress} + {@code serviceRadiusKm}): inventory has no
 * store dimension yet (INV-L), so per-branch offers wait for it (design §2d.2).
 */
@Entity
@Table(name = "seller_profile")
@Getter @Setter
public class SellerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "contact_phone", nullable = false, length = 32)
    private String contactPhone;

    @Column(name = "contact_email", length = 160)
    private String contactEmail;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "pickup_address", nullable = false, length = 300)
    private String pickupAddress;

    @Column(name = "service_radius_km", nullable = false)
    private Integer serviceRadiusKm;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "status_reason", length = 255)
    private String statusReason;

    @Column(name = "applied_by")
    private Long appliedBy;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    public SellerStatus statusEnum() { return status == null ? null : SellerStatus.valueOf(status); }
}
