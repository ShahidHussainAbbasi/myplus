package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1e — an order line and its order-time SNAPSHOT (V28, source §13.3): price, the four parties, warranty,
 * returns and commission as they were when the shopper ordered. Written once; only {@code settlementStatus}
 * changes later (MKT-1g). A policy deactivated or replaced afterwards never rewrites this row.
 */
@Entity
@Table(name = "mkt_order_line")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_order_id", nullable = false)
    private Long sellerOrderId;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "source_product_id", nullable = false)
    private Long sourceProductId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "stock_source_type", nullable = false, length = 16)
    private String stockSourceType;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "stock_owner_organization_id", nullable = false)
    private Long stockOwnerOrganizationId;

    @Column(name = "custodian_organization_id", nullable = false)
    private Long custodianOrganizationId;

    @Column(name = "fulfiller_organization_id", nullable = false)
    private Long fulfillerOrganizationId;

    @Column(name = "promise_hours")
    private Integer promiseHours;

    @Column(name = "warranty_policy_id")
    private Long warrantyPolicyId;

    @Column(name = "warranty_provider", length = 120)
    private String warrantyProvider;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    @Column(name = "warranty_starts", length = 16)
    private String warrantyStarts;

    @Column(name = "warranty_covers", length = 300)
    private String warrantyCovers;

    @Column(name = "warranty_excludes", length = 300)
    private String warrantyExcludes;

    @Column(name = "return_policy_id")
    private Long returnPolicyId;

    @Column(name = "return_days")
    private Integer returnDays;

    @Column(name = "commission_policy_id")
    private Long commissionPolicyId;

    @Column(name = "commission_basis", length = 24)
    private String commissionBasis;

    @Column(name = "commission_rate", precision = 9, scale = 6)
    private BigDecimal commissionRate;

    @Column(name = "commission_fixed", precision = 19, scale = 2)
    private BigDecimal commissionFixed;

    /** {@code MarketplaceStatus.Settlement}; NOT_ELIGIBLE until MKT-1g's rules move it. */
    @Column(name = "settlement_status", nullable = false, length = 24)
    private String settlementStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
