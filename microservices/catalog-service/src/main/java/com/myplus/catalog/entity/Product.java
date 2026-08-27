package com.myplus.catalog.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Product master (slice 33, Phase 5) — descriptive attributes only. Quantity/threshold/valuation state
 * (currentStock, min/max level, reorderPoint, costPrice) lives in inventory-service's StockLevel.
 */
@Entity
@Table(name = "products", indexes = {@Index(columnList = "sku")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Internal stock code. OPTIONAL — many retailers don't code every line, and a shop may enter
     *  products by name alone. Nullable rather than blank so that "no code" cannot collide with
     *  another product that also has no code (see ProductService.normalize). */
    @Column(nullable = true)
    private String sku;

    /** Scannable code (manufacturer EAN/UPC) — distinct from the internal {@code sku}. Barcode-first sell resolves a
     *  scan by barcode OR sku. Nullable; a product with no barcode is still found by its sku. */
    @Column(name = "barcode")
    private String barcode;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** What a PRICE refers to — "pack", "box", "pcs". Free text, and unchanged in meaning by U1. */
    private String unit;

    // ── U1: selling by the piece ────────────────────────────────────────────────────────────────────────
    // docs/pack-and-loose-selling-design.md. `unit` above is a LABEL; these make it computable.

    /**
     * How many sellable pieces one priced unit contains — 10 tablets in a pack, 24 bottles in a crate.
     *
     * <p>{@code null} or {@code 1} means not divisible, which is every product today. One level only: a
     * conversion graph would buy generality nothing in these verticals needs and charge for it on every read.
     */
    @Column(name = "pack_size")
    private Integer packSize;

    /** What ONE piece is called — "tablet". */
    @Column(name = "loose_unit", length = 32)
    private String looseUnit;

    /**
     * The plural — "tablets".
     *
     * <p>⚠ A second column rather than an appended "s", because "5 tablet" is wrong in every language this
     * platform ships in and no rule covers Urdu, Arabic and Hindi alike. Tenant data, not an i18n key: a shop
     * names its own units.
     */
    @Column(name = "loose_unit_plural", length = 32)
    private String looseUnitPlural;

    /**
     * May this be broken open at all?
     *
     * <p>⚠ Deliberately separate from {@link #packSize}. A pharmacy knows an antibiotic course holds 10
     * tablets — worth recording for stock counts — and must still refuse to split it. Collapsing the two would
     * force a shop to misstate the pack in order to enforce the rule.
     */
    // ⚠ @Builder.Default, or the initialiser is IGNORED when Lombok's builder constructs the object and a
    // NOT NULL column takes a null. Caught by ProductRepoScopingTest — the same shape as INST-1's defect #2,
    // where an absent value reached a NOT NULL column and the row died AFTER the caller had committed.
    @Builder.Default
    @Column(name = "allow_loose", nullable = false)
    private Boolean allowLoose = Boolean.FALSE;

    /**
     * Which unit a sale line starts in: {@code PACK} or {@code LOOSE}.
     *
     * <p>The biggest time saving in the design — a shop selling loose nine times in ten should not press the
     * loose key nine times in ten. Per product, because the same shop sells strips loose and bottles whole.
     *
     * <p>⚠ When this is not {@code PACK} the till must show the unit INSIDE the quantity box: a default that
     * silently changes what a familiar keystroke means is worse than no default.
     */
    @Builder.Default
    @Column(name = "default_sell_unit", nullable = false, length = 8)
    private String defaultSellUnit = "PACK";

    /**
     * WHO last changed a pack rule, and WHEN.
     *
     * <p>{@code packSize} and {@code allowLoose} decide what a customer is charged and whether a sealed course
     * may be split. The standards require pricing controls to be auditable, and this table records only
     * {@code createdBy} today — so "who allowed this to be split?" had no answer at all.
     *
     * <p>Stamped on the product rather than sent to audit-service: catalog holds no audit client, and adding a
     * cross-service dependency for two fields is a larger change than the thing being audited. If catalog ever
     * gains one, these become the fallback rather than the record.
     */
    @Column(name = "pack_changed_by")
    private Long packChangedBy;

    @Column(name = "pack_changed_at")
    private LocalDateTime packChangedAt;

    /** True when this product can actually be sold loose — both a pack size AND permission. */
    public boolean isLooseSellable() {
        return Boolean.TRUE.equals(allowLoose) && packSize != null && packSize > 1;
    }

    /** Brand/manufacturer (slice 33, U1 — parity with business Item.company for the item→product migration). */
    private String manufacturer;

    private BigDecimal sellingPrice;
    /** Legacy/custom per-product rate — the fallback when {@code taxCodeId} is null (single-rate orgs unaffected). */
    private BigDecimal taxRate;
    /** Multi-rate tax: the assigned tax-code (local id → {@code tax_code.id}); its rate wins over {@code taxRate}. */
    @Column(name = "tax_code_id")
    private Long taxCodeId;

    /**
     * Last rates STAMPED BY THE PURCHASE FLOW (Option B, extended): whenever a purchase of this product is
     * recorded or edited, business-service writes what it was bought at and what it is to be sold at, together
     * with when. The Product screen then reads them straight off the product row — the rates are never derived
     * from the purchase/sell history at read time.
     *
     * <p>{@code lastSaleRate} is a RECORD of what the last purchase set the price to; {@code sellingPrice} above
     * is the LIVE master price. They start equal on every purchase and diverge only when someone edits the price
     * directly on the Product form — which is precisely the difference worth seeing on the list.
     *
     * <p>Null until this product's first purchase (nothing has been stamped yet) — the screen shows a dash rather
     * than a misleading zero.
     */
    @Column(name = "last_purchase_rate", precision = 19, scale = 2)
    private BigDecimal lastPurchaseRate;

    @Column(name = "last_sale_rate", precision = 19, scale = 2)
    private BigDecimal lastSaleRate;

    /** When the two rates above were last stamped — i.e. the date of the purchase that set them. */
    @Column(name = "last_rate_at")
    private LocalDateTime lastRateAt;

    @Builder.Default
    private Boolean isActive = true;

    /**
     * Pharmacy clinical flags (review B1). They live HERE, on the product master, because the sell saga already
     * fetches a {@link com.myplus.commerce.contracts.dto.ProductRef} per line — so the rx guard costs no extra
     * call at checkout. pharma-service owns the richer clinical layer but no longer these two.
     * Non-null by contract (schema is NOT NULL DEFAULT FALSE): the sell guard never reasons about null.
     */
    @Builder.Default
    @Column(name = "rx_required", nullable = false)
    private Boolean rxRequired = false;

    @Builder.Default
    @Column(name = "controlled_substance", nullable = false)
    private Boolean controlledSubstance = false;

    private String imageUrl;
    private Long createdBy;

    // Tenant scope (carried from inventory's Phase 4.5 scoping) — nullable; ddl-auto creates them.
    private Long organizationId;
    private Long userId;
    private String userType;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
