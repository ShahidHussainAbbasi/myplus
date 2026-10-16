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

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * MKT-5d (R20.5 custodian counts, R12.2) — the warehouse counted one owner's product (V43). Units found missing stay
 * the owner's, set aside in its books, but are no longer sold; each is resolved once, by the owner's write-off or by an
 * approved claim.
 */
@Entity
@Table(name = "mkt_consignment_count")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceConsignmentCount {

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

    @Column(name = "expected", nullable = false)
    private Integer expected;

    @Column(name = "counted", nullable = false)
    private Integer counted;

    @Column(name = "missing", nullable = false)
    private Integer missing;

    @Column(name = "tolerated", nullable = false)
    private Integer tolerated;

    @Column(name = "written_off", nullable = false)
    private Integer writtenOff = 0;

    @Column(name = "approved", nullable = false)
    private Integer approved = 0;

    @Column(name = "write_offs", nullable = false)
    private Integer writeOffs = 0;

    /** Units being written off right now: kept out of reach of a claim until the owner's books answer. */
    @Column(name = "pending", nullable = false)
    private Integer pending = 0;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "counted_by_user_id")
    private Long countedByUserId;

    @Column(name = "counted_at", nullable = false)
    private LocalDateTime countedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /** Missing and not yet resolved: still in the owner's books, never sold. */
    public int open() {
        return missing - writtenOff - approved;
    }

    public String getCountNo() {
        return id == null ? null : String.format(Locale.ROOT, "CC-%06d", id);
    }

    @PrePersist
    void onCreate() {
        if (countedAt == null) countedAt = LocalDateTime.now();
    }
}
