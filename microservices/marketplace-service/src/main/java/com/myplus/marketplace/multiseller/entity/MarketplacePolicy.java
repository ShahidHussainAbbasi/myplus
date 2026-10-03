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
 * MKT-1c — a warranty, return or commission policy (V26). APPEND-ONLY: never edited, only deactivated and
 * replaced, so every offer (and later every order snapshot) points at the exact terms it was sold under. There is
 * no setter path in the service that changes terms on an existing row.
 */
@Entity
@Table(name = "mkt_policy")
@Getter
@Setter
@NoArgsConstructor
public class MarketplacePolicy {

    public static final String WARRANTY = "WARRANTY";
    public static final String RETURN = "RETURN";
    public static final String COMMISSION = "COMMISSION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_type", nullable = false, length = 16)
    private String policyType;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = Boolean.FALSE;

    @Column(name = "warranty_provider", length = 120)
    private String warrantyProvider;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    /** When the warranty clock starts; Phase 1 offers DELIVERY only. */
    @Column(name = "warranty_starts", length = 16)
    private String warrantyStarts;

    @Column(name = "warranty_covers", length = 300)
    private String warrantyCovers;

    @Column(name = "warranty_excludes", length = 300)
    private String warrantyExcludes;

    @Column(name = "claim_process", length = 300)
    private String claimProcess;

    @Column(name = "return_days")
    private Integer returnDays;

    @Column(name = "commission_basis", length = 24)
    private String commissionBasis;

    @Column(name = "commission_rate", precision = 9, scale = 6)
    private BigDecimal commissionRate;

    @Column(name = "commission_fixed", precision = 19, scale = 2)
    private BigDecimal commissionFixed;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
