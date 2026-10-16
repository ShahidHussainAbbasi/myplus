package com.myplus.marketplace.multiseller.entity;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MKT-5b (R20.5) — one movement in the consigned stock ledger (V41). What the warehouse holds for a consignor, product
 * by product, is the sum of {@link #getQuantity()}: + into the warehouse, - out of it. Rows are never edited; a
 * correction is another movement.
 */
@Entity
@Table(name = "mkt_consigned_stock")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceConsignedStock {

    /** Received at the warehouse from the consignor (5b). */
    public static final String RECEIVED = "RECEIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    @Column(name = "consignor_org_id", nullable = false)
    private Long consignorOrgId;

    @Column(name = "consignee_org_id", nullable = false)
    private Long consigneeOrgId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "consignor_product_id", nullable = false)
    private Long consignorProductId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "movement", nullable = false, length = 16)
    private String movement;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitValue;

    @Column(name = "serials", length = 2000)
    private String serials;

    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "hold_key", length = 64)
    private String holdKey;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
