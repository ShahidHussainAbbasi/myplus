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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
