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
import java.util.Set;

/**
 * MKT-5e (R20.5 unsold-stock return, R4.6) — consigned units going back from the warehouse to their owner (V44). From
 * the moment it is asked for, the units are no longer sold (the ledger's {@code balance} subtracts every return not yet
 * RECEIVED); they stay the owner's, set aside in its books, until it confirms it has them back.
 */
@Entity
@Table(name = "mkt_consigned_return")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceConsignedReturn {

    /** Asked for; still on the warehouse's shelf, no longer sold. */
    public static final String REQUESTED = "REQUESTED";
    /** Handed over to the carrier by the warehouse. */
    public static final String DISPATCHED = "DISPATCHED";
    /** The owner's books are being put right (its hold let go); a retry carries on. */
    public static final String RECEIVING = "RECEIVING";
    public static final String RECEIVED = "RECEIVED";
    public static final String CANCELLED = "CANCELLED";

    /** Not yet back with the owner: what the ledger's balance keeps off the shelf. */
    public static final Set<String> UNDER_WAY = Set.of(REQUESTED, DISPATCHED, RECEIVING);

    /** The owner asked for its stock back. */
    public static final String RECALL = "RECALL";
    /** Held longer than the agreement's unsold-return term. */
    public static final String UNSOLD = "UNSOLD";

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

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "units", nullable = false)
    private Integer units;

    @Column(name = "reason", nullable = false, length = 16)
    private String reason;

    @Column(name = "bearer", nullable = false, length = 16)
    private String bearer;

    @Column(name = "cost", precision = 19, scale = 2)
    private BigDecimal cost;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "settlement_ref", length = 30)
    private String settlementRef;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "requested_by_org_id", nullable = false)
    private Long requestedByOrgId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "dispatched_by_user_id")
    private Long dispatchedByUserId;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "received_by_user_id")
    private Long receivedByUserId;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    public String getReturnNo() {
        return id == null ? null : String.format(Locale.ROOT, "RT-%06d", id);
    }

    @PrePersist
    void onCreate() {
        if (requestedAt == null) requestedAt = LocalDateTime.now();
    }
}
