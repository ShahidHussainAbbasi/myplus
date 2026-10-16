package com.myplus.marketplace.multiseller.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MKT-5a — the terms on which a consignor's stock is held and sold by a consignee (V40, R4.6, R12.2).
 *
 * <p>{@link #OFFERED} by the operator → {@link #ACTIVE} when the consignor accepts, {@link #DECLINED} by the consignor,
 * or {@link #WITHDRAWN} by the operator first. An ACTIVE agreement is {@link #ENDED} by either side, or
 * {@link #REPLACED} when the consignor accepts newer terms for the same pair. The terms are never edited.
 */
@Entity
@Table(name = "mkt_consignment_agreement")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceConsignmentAgreement {

    public static final String OFFERED = "OFFERED";
    public static final String ACTIVE = "ACTIVE";
    public static final String DECLINED = "DECLINED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String ENDED = "ENDED";
    public static final String REPLACED = "REPLACED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consignor_org_id", nullable = false)
    private Long consignorOrgId;

    @Column(name = "consignee_org_id", nullable = false)
    private Long consigneeOrgId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "ownership_transfer", nullable = false, length = 16)
    private String ownershipTransfer;

    @Column(name = "price_set_by", nullable = false, length = 16)
    private String priceSetBy;

    @Column(name = "commission_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionPct;

    @Column(name = "settle_every_days", nullable = false)
    private Integer settleEveryDays;

    @Column(name = "reconcile_every_days", nullable = false)
    private Integer reconcileEveryDays;

    @Column(name = "count_every_days", nullable = false)
    private Integer countEveryDays;

    @Column(name = "shrinkage_tolerance_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal shrinkageTolerancePct;

    @Column(name = "damage_bearer", nullable = false, length = 16)
    private String damageBearer;

    @Column(name = "expiry_bearer", nullable = false, length = 16)
    private String expiryBearer;

    @Column(name = "insured_by", nullable = false, length = 16)
    private String insuredBy;

    @Column(name = "claim_window_days", nullable = false)
    private Integer claimWindowDays;

    @Column(name = "proof_required", nullable = false)
    private Boolean proofRequired = Boolean.TRUE;

    @Column(name = "unsold_return_days", nullable = false)
    private Integer unsoldReturnDays;

    @Column(name = "unsold_return_bearer", nullable = false, length = 16)
    private String unsoldReturnBearer;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "answer_reason", length = 500)
    private String answerReason;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "offered_by_user_id")
    private Long offeredByUserId;

    @Column(name = "answered_by_user_id")
    private Long answeredByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    public String getAgreementNo() {
        return id == null ? null : String.format("CA-%06d", id);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
