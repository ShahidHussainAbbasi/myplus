package com.myplus.market.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One VERSION of a marketplace policy. Owned by the platform org (the operator's own document).
 *
 * <p>{@code publishedSlot} equals {@code policyType} while PUBLISHED and is NULL otherwise; with the UNIQUE key
 * (organization_id, published_slot) the database itself guarantees one published version per type.
 */
@Entity
@Table(name = "market_policy")
@Getter @Setter
public class MarketPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "policy_type", nullable = false, length = 32)
    private String policyType;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "summary", nullable = false, length = 2000)
    private String summary;

    @Column(name = "document_url", length = 500)
    private String documentUrl;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "published_slot", length = 32)
    private String publishedSlot;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
