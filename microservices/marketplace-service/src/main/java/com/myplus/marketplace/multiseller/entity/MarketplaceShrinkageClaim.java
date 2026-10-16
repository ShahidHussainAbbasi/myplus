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
import java.util.Locale;

/**
 * MKT-5d (R12.1-R12.4) — the owner claims units a count found missing from the custodian that answers for them (V43).
 * Only the operator's approval pays it; a rejection gives the reason.
 */
@Entity
@Table(name = "mkt_shrinkage_claim")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceShrinkageClaim {

    public static final String OPEN = "OPEN";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "count_id", nullable = false)
    private Long countId;

    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    @Column(name = "consignor_org_id", nullable = false)
    private Long consignorOrgId;

    @Column(name = "consignee_org_id", nullable = false)
    private Long consigneeOrgId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "responsible", nullable = false, length = 16)
    private String responsible;

    @Column(name = "units", nullable = false)
    private Integer units;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "proof", length = 500)
    private String proof;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "decision_reason", length = 300)
    private String decisionReason;

    @Column(name = "settlement_ref", length = 30)
    private String settlementRef;

    @Column(name = "raised_by_user_id")
    private Long raisedByUserId;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    public String getClaimNo() {
        return id == null ? null : String.format(Locale.ROOT, "SC-%06d", id);
    }

    @PrePersist
    void onCreate() {
        if (raisedAt == null) raisedAt = LocalDateTime.now();
    }
}
