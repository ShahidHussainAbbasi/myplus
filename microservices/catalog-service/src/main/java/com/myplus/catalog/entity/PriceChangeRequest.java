package com.myplus.catalog.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * PR-4 — a price a purchase would set, waiting for the owner's approval. Columns mirror V24__price_change_requests.sql.
 *
 * <p>Never the price itself: approving writes it through {@code ProductService}'s purchase path, so the product's version,
 * its cache and its price history move exactly as for any purchase.
 */
@Entity
@Table(name = "price_change_requests")
@Getter @Setter
public class PriceChangeRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    /** A newer proposal for the same product replaced this one before anyone decided it. */
    public static final String SUPERSEDED = "SUPERSEDED";

    /** Why it is waiting: the business chose Approval, or an Auto guard held the change back. */
    public static final String REASON_APPROVAL = "APPROVAL";
    public static final String REASON_NEVER_LOWER = "NEVER_LOWER";
    public static final String REASON_MAX_RISE = "MAX_RISE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "current_price", precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "proposed_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal proposedPrice;

    @Column(name = "cost", precision = 19, scale = 2)
    private BigDecimal cost;

    /** MARKUP (the rule's price) or PURCHASE (the bill's S/U rate). */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "reason", nullable = false, length = 20)
    private String reason;

    @Column(name = "detail", length = 160)
    private String detail;

    @Column(name = "ref", length = 80)
    private String ref;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "proposed_by")
    private Long proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;
}
