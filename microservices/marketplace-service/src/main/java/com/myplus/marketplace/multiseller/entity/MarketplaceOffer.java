package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
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
 * MKT-1c — one seller's terms for one canonical product (V26; source §5 MarketplaceOffer).
 *
 * <p>The four party ids are stamped by the server from the JWT and the stock source; none is ever read from a
 * request (source §22 "browser cannot choose stock owner by changing JSON"). There is no stock column: availability
 * is published in {@link MarketplaceOfferProjection} and the reservation at checkout is the authority.
 */
@Entity
@Table(name = "mkt_offer")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The seller's org — owner of this row. */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "source_product_id", nullable = false)
    private Long sourceProductId;

    @Column(name = "stock_source_type", nullable = false, length = 16)
    private String stockSourceType;

    @Column(name = "stock_owner_organization_id", nullable = false)
    private Long stockOwnerOrganizationId;

    @Column(name = "custodian_organization_id", nullable = false)
    private Long custodianOrganizationId;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "fulfiller_organization_id", nullable = false)
    private Long fulfillerOrganizationId;

    @Column(name = "seller_sku", length = 64)
    private String sellerSku;

    @Column(name = "list_price", precision = 19, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "marketplace_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal marketplacePrice;

    /** Comma-separated city names (ruling R-MKT-7: a service-area list until stock has a location). */
    @Column(name = "delivery_areas", nullable = false, length = 500)
    private String deliveryAreas;

    @Column(name = "promise_hours", nullable = false)
    private Integer promiseHours;

    @Column(name = "warranty_policy_id")
    private Long warrantyPolicyId;

    @Column(name = "return_policy_id")
    private Long returnPolicyId;

    /** Stamped from the operator's default commission policy at APPROVAL — never chosen by the seller. */
    @Column(name = "commission_policy_id")
    private Long commissionPolicyId;

    /** {@code MarketplaceStatus.Approval} name; moves only through {@code MarketplaceStateMachines.OFFER}. */
    @Column(name = "approval_status", nullable = false, length = 16)
    private String approvalStatus;

    /** The seller's own on/off — distinct from approval, which only the operator moves. */
    @Column(name = "paused", nullable = false)
    private Boolean paused = Boolean.FALSE;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
