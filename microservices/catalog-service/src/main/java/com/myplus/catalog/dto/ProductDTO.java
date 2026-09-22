package com.myplus.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductDTO {
    private Long id;
    private String sku;
    private String barcode;     // barcode-first sell: scannable EAN/UPC (distinct from sku)

    // Slice 106: the product master had NO validation — POST /products with name:"" returned success and
    // persisted a nameless product, which then shows as a blank row in every picker, report and receipt.
    // Name is the ONE field with no sensible default and no way to recover after the fact (sku/barcode are
    // legitimately optional, price defaults). Bean Validation is the project standard (slice 26); the
    // starter was already on the classpath here and simply never used.
    @NotBlank(message = "Product name is required")
    private String name;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String unit;

    // U1 — selling by the piece. All optional; a form that omits them leaves the product as it is.
    private Integer packSize;
    private String looseUnit;
    private String looseUnitPlural;
    private Boolean allowLoose;
    private String defaultSellUnit;
    /** U15-C — the shop's own word for the multiple it buys in ("peti", "carton"); null = it does not. */
    private String purchaseUnitName;
    /** How many shelf units that multiple usually holds. ⚠ A HINT for the purchase form — never a default. */
    private Integer purchasePackCount;

    private String manufacturer;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;
    private Long taxCodeId;         // multi-rate tax: assigned tax-code (null = use taxRate / org default)
    private String taxCodeName;     // read-only, for display in the product list/form
    private Boolean isActive;
    /** Last rates stamped by the purchase flow — read-only here; set via PUT /products/{id}/price on add/edit of a
     *  purchase. Carried on the DTO so the Product list renders "last bought / last sold at" from the row it
     *  already loads, with no second round trip. Null until the product's first purchase. */
    private BigDecimal lastPurchaseRate;
    private BigDecimal lastSaleRate;
    private LocalDateTime lastRateAt;
    /** Pharmacy clinical flags (B1) — read-only here; set via PUT /products/{id}/clinical-flags. Carried so the
     *  product list can mark a medicine "Rx" without a second round trip. */
    private Boolean rxRequired;
    private Boolean controlledSubstance;
    /** C6 tracking flags — read-only here, exactly as the clinical flags above are; set via
     *  PUT /products/{id}/tracking-flags, which is ADMIN-gated AND capability-checked.
     *
     *  <p>Deliberately NOT mapped in {@code fromDto}. Reading them costs nothing and saves the product list a
     *  round trip; WRITING them from an ordinary product save would route a gated flag around its own gate,
     *  and a form that simply omitted the field would silently clear it. Same rule, same reason, as
     *  {@code rxRequired}: one writer, and it is the endpoint that checks the capability. */
    private Boolean requiresSerial;
    private Boolean tracksBatch;
    private String imageUrl;
    private Long createdBy;
    /** U1 — who is making this change, for the pack-rule audit stamp. */
    private Long updatedBy;
    /**
     * DUP-1 — one key per form-fill, so a repeated submit replays instead of inserting again.
     *
     * <p>⚠ THIS FIELD IS LOAD-BEARING AND EASY TO LEAVE OUT. {@code POST /products} binds a typed DTO and Spring
     * Boot drops unknown JSON properties silently — so without it the client's key is discarded without a word,
     * every request looks unique, and the guard reports success while protecting nothing.
     *
     * <p>Echoed back by {@code toDto} on purpose: it is the caller's own key, and returning it is what lets a
     * caller (and the gate) tell a replay from a fresh insert.
     */
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * BLK-4 — the row version the caller LOADED, sent back on an update so a stale copy is refused.
     *
     * <p>⚠ Same load-bearing trap as {@link #idempotencyKey}: {@code PUT /products/{id}} binds this typed DTO
     * and unknown JSON properties are dropped silently. Without the field the form's version never reaches
     * the service, every save falls back to last-write-wins, and the lock protects nothing while looking done.
     *
     * <p>Optional on purpose. A caller that sends none — an older cached tab, an integration written before
     * BLK-4 — keeps today's last-write-wins rather than being refused, the rule V62 set for Customer.
     * Ignored on create: there is nothing to be stale against.
     */
    private Long version;
}
