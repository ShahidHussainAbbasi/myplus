package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1c — the published read model of one offer (V26): the ONLY thing a cross-tenant read ever touches.
 *
 * <p>Holds exactly the source §9.2 fields. Cost, margin, purchase rates and stock history (§9.3) have no column
 * here, so no query can leak them. Written only by {@code OfferProjectionService}; customers read {@code LIVE} rows.
 */
@Entity
@Table(name = "mkt_offer_projection")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceOfferProjection {

    public static final String LIVE = "LIVE";
    public static final String HIDDEN = "HIDDEN";

    @Id
    @Column(name = "offer_id")
    private Long offerId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "seller_display_name", nullable = false, length = 120)
    private String sellerDisplayName;

    @Column(name = "stock_source_type", nullable = false, length = 16)
    private String stockSourceType;

    @Column(name = "regulated_status", nullable = false, length = 16)
    private String regulatedStatus;

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "available_qty", precision = 19, scale = 4)
    private BigDecimal availableQty;

    @Column(name = "delivery_areas", nullable = false, length = 500)
    private String deliveryAreas;

    @Column(name = "promise_hours", nullable = false)
    private Integer promiseHours;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    @Column(name = "warranty_provider", length = 120)
    private String warrantyProvider;

    @Column(name = "warranty_starts", length = 16)
    private String warrantyStarts;

    @Column(name = "warranty_covers", length = 300)
    private String warrantyCovers;

    @Column(name = "warranty_excludes", length = 300)
    private String warrantyExcludes;

    @Column(name = "return_days")
    private Integer returnDays;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** When availability was last confirmed with inventory. Stale rows are not ranked (OfferEligibility). */
    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
