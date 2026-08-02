package com.myplus.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A contract/tier pricing rule (slice b2b-P2 = OMS B1 = requirement #10) — the persistent side of
 * {@code commerce-pricing}'s {@code PriceRule}.
 *
 * <p>Two types on purpose: this one is JPA and lives in catalog-service; the library's is a plain carrier with
 * no persistence. That is what lets POS, storefront and pharmacy share one set of pricing RULES without
 * sharing a table — the same split {@code common-credit} uses for credit.
 *
 * <p>A price is a property of the CATALOG, not of one sales channel, which is why the rules live here rather
 * than in business-service: every channel then gets identical answers by construction.
 */
@Entity
@Table(name = "price_rule")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceRuleEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    /** CUSTOMER (a named account) or TYPE (a tier — Phase 0's Customer.customerType). */
    @Column(name = "scope", length = 16, nullable = false)
    private String scope;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_type", length = 16)
    private String customerType;

    /** PRODUCT or CATEGORY. */
    @Column(name = "target", length = 16, nullable = false)
    private String target;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "category_id")
    private Long categoryId;

    /** FIXED (an absolute unit price — 0 is a real price) or PERCENT (off the catalog price). */
    @Column(name = "mode", length = 16, nullable = false)
    private String mode;

    @Column(name = "value", precision = 19, scale = 2, nullable = false)
    private BigDecimal value;

    /** Owner's explicit tie-break between two equally specific rules; higher wins. */
    @Builder.Default
    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true;

    /** Inclusive. Null/null = always live. */
    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
