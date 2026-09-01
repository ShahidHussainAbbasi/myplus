package com.myplus.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A bonus / free-goods scheme (task #17 P1) — "buy 10, get 1 free", in all three directions.
 *
 * <p>Design: {@code microservices/docs/slices/bonus-schemes.md}.
 *
 * <p>ONE entity for a supplier's offer to us, an offer to a named customer, and an offer to a customer tier,
 * because they are the same shape with different keys — the same decision {@link PriceRuleEntity} already
 * makes for pricing. It lives in catalog-service for the same reason: an offer is a property of the CATALOG,
 * not of one sales channel, so purchasing, POS and storefront get identical answers by construction.
 *
 * <p>{@code vendorId} and {@code customerId} are OPAQUE identifiers here — both masters live in
 * business-service. That is the precedent {@code PriceRuleEntity.customerId} already sets, and it is why this
 * table is not a second source of truth for supplier data.
 */
@Entity
@Table(name = "bonus_scheme")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BonusSchemeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    /** The operator's own name for the offer, e.g. {@code SUP-UNILEVER-OIL-10-1}. Unique per org. */
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    /** VENDOR (a supplier's offer to us) | CUSTOMER (a named account) | CUSTOMER_TYPE (a tier). */
    @Column(name = "scope", length = 16, nullable = false)
    private String scope;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_type", length = 16)
    private String customerType;

    /** PRODUCT or CATEGORY. */
    @Column(name = "trigger_target", length = 16)
    private String triggerTarget;

    @Column(name = "trigger_product_id")
    private Long triggerProductId;

    @Column(name = "trigger_category_id")
    private Long triggerCategoryId;

    /**
     * The product given free. NULL means "the same product as the trigger", which is the common case.
     *
     * <p>This field is why a bare bonus QUANTITY was not enough: "buy a machine, get a coffee pack" needs a
     * reward ITEM, and the free unit then leaves stock as its own line at zero selling price.
     */
    @Column(name = "reward_product_id")
    private Long rewardProductId;

    /** The qualifying threshold — buy this many. */
    @Column(name = "paid_quantity", precision = 19, scale = 3, nullable = false)
    private BigDecimal paidQuantity;

    /** What is issued free once the threshold is met. */
    @Column(name = "bonus_quantity", precision = 19, scale = 3, nullable = false)
    private BigDecimal bonusQuantity;

    /**
     * INCLUSIVE — 10 delivered, 9 billed ("one of these ten is free").
     * EXCLUSIVE — 11 delivered, 10 billed ("buy ten, get one extra").
     *
     * <p>MANDATORY. Without it "10+1" cannot be interpreted for stock, invoice, cost or tax, and the two
     * readings differ in every one of those.
     */
    @Column(name = "bonus_type", length = 16, nullable = false)
    private String bonusType;

    /**
     * ONE_TIME — the threshold is a gate: "buy 10 get 1" gives 1 whether 10 or 15 are bought.
     * REPEATING — the threshold is a block: "every 10 get 1" gives 1 on 15, and "every 5 get 1" gives 3.
     *
     * <p>MANDATORY, because the partial-return clawback recomputes entitlement from the RETAINED paid
     * quantity — and that sum has no single answer without this field.
     */
    @Column(name = "qualification_mode", length = 16, nullable = false)
    private String qualificationMode;

    /** Owner's tie-break between two equally specific schemes. Higher wins. */
    @Column(name = "priority", nullable = false)
    private int priority;

    /** Whether two matching schemes may combine. Default false: one winner, as pricing already behaves. */
    @Column(name = "stackable")
    private Boolean stackable;

    /** DRAFT | ACTIVE | EXPIRED | DISABLED. A scheme gives away goods, so it has a governance state. */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** Both bounds INCLUSIVE. NULL/NULL = always live. */
    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── behaviour ────────────────────────────────────────────────────────────────────────────────────

    /** Live = ACTIVE and within its date window. Both bounds inclusive; absent bounds mean unbounded. */
    public boolean isLive(LocalDate on) {
        if (!"ACTIVE".equalsIgnoreCase(status)) return false;
        if (startsOn != null && on.isBefore(startsOn)) return false;
        return endsOn == null || !on.isAfter(endsOn);
    }

    /**
     * How many free units a given PAID quantity earns under this scheme.
     *
     * <p>The whole reason {@link #qualificationMode} exists, expressed once so purchasing, the till and the
     * return clawback cannot each answer it differently:
     *
     * <pre>
     *   ONE_TIME  "buy 10 get 1"   on 15 paid -> 1     (a gate: met or not)
     *   REPEATING "every 10 get 1" on 15 paid -> 1     (blocks: floor(15/10) = 1)
     *   REPEATING "every 5  get 1" on 15 paid -> 3     (blocks: floor(15/5)  = 3)
     * </pre>
     *
     * <p>Integer division on purpose — a partial block earns nothing. Returns ZERO rather than null for a
     * quantity below the threshold, so callers can add it without a null check.
     */
    public BigDecimal bonusFor(BigDecimal paid) {
        if (paid == null || paidQuantity == null || bonusQuantity == null) return BigDecimal.ZERO;
        if (paidQuantity.signum() <= 0) return BigDecimal.ZERO;
        if (paid.compareTo(paidQuantity) < 0) return BigDecimal.ZERO;

        if ("REPEATING".equalsIgnoreCase(qualificationMode)) {
            BigDecimal blocks = paid.divide(paidQuantity, 0, RoundingMode.DOWN);
            return bonusQuantity.multiply(blocks);
        }
        return bonusQuantity;   // ONE_TIME: the threshold is a gate, not a multiplier
    }

    /** The product actually given away — the reward when set, otherwise the trigger itself. */
    public Long effectiveRewardProductId() {
        return rewardProductId != null ? rewardProductId : triggerProductId;
    }
}
