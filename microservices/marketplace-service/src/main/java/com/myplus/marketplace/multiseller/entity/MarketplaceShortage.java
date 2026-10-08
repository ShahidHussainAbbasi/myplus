package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

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
 * MKT-2b — one seller's part that was not fulfilled (rejected, or not accepted in time), its cause and responsible
 * party (R11.4), and what became of it (R11.2). V32.
 *
 * <p>The cause is a RECORD, never a charge: nothing is debited from it (R12.4). The seller may dispute it and an
 * operator decides ({@link #RECORDED} → {@link #DISPUTED} → {@link #UPHELD} or {@link #OVERTURNED}).
 */
@Entity
@Table(name = "mkt_shortage")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceShortage {

    /** Result while the alternatives are being looked for; never shown as an outcome. */
    public static final String PENDING = "PENDING";

    public static final String RECORDED = "RECORDED";
    public static final String DISPUTED = "DISPUTED";
    public static final String UPHELD = "UPHELD";
    public static final String OVERTURNED = "OVERTURNED";

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    /** Source §11.4: who caused a shortage. NO_RESPONSE is the clock's finding (the seller did not answer). */
    public enum Cause {
        MERCHANT_STALE_STOCK("MERCHANT"), SUPPLIER_STALE_STOCK("SUPPLIER"), PLATFORM_SYNC_DEFECT("PLATFORM"),
        CUSTODIAN_COUNT("CUSTODIAN"), CARRIER_LOSS("CARRIER"), CUSTOMER_INVALID_COMBINATION("CUSTOMER"),
        NO_RESPONSE("MERCHANT");

        public final String party;

        Cause(String party) {
            this.party = party;
        }
    }

    /** What a seller can name when it rejects; the rest are an operator's finding. */
    public static final Set<Cause> SELLER_CAUSES = Set.of(Cause.MERCHANT_STALE_STOCK, Cause.SUPPLIER_STALE_STOCK,
            Cause.PLATFORM_SYNC_DEFECT);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mkt_order_id", nullable = false)
    private Long mktOrderId;

    @Column(name = "seller_order_id", nullable = false)
    private Long sellerOrderId;

    @Column(name = "seller_organization_id", nullable = false)
    private Long sellerOrganizationId;

    @Column(name = "cause", nullable = false, length = 32)
    private String cause;

    @Column(name = "responsible_role", nullable = false, length = 16)
    private String responsibleRole;

    @Column(name = "responsible_org_id")
    private Long responsibleOrgId;

    @Column(name = "evidence", length = 500)
    private String evidence;

    @Column(name = "result", nullable = false, length = 32)
    private String result;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "dispute_note", length = 500)
    private String disputeNote;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "replacement_seller_order_id")
    private Long replacementSellerOrderId;

    @Column(name = "proposal_seller_org_id")
    private Long proposalSellerOrgId;

    @Column(name = "proposal_hold_key", length = 64)
    private String proposalHoldKey;

    @Column(name = "proposal_held", nullable = false)
    private Boolean proposalHeld = Boolean.FALSE;

    /** {@code offerId:qty:unitPrice;...} — what the customer is asked to approve, priced when it was offered. */
    @Column(name = "proposal_lines", length = 2000)
    private String proposalLines;

    @Column(name = "proposal_total", precision = 19, scale = 2)
    private BigDecimal proposalTotal;

    @Column(name = "proposal_promise_hours")
    private Integer proposalPromiseHours;

    @Column(name = "proposal_expires_at")
    private LocalDateTime proposalExpiresAt;

    @Column(name = "customer_decision", length = 16)
    private String customerDecision;

    @Column(name = "customer_decided_at")
    private LocalDateTime customerDecidedAt;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

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
