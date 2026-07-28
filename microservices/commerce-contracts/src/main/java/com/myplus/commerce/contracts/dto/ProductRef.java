package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Lightweight cross-service reference to a catalog product (slice 33). Domains that need to display, link, or
 * price a product (trade line items, pharmacy medicines) carry this instead of duplicating the catalog entity.
 * Carries {@code sellingPrice}/{@code taxRate} so the sell saga can price from catalog (D1).
 *
 * M4d (slice 93): also carries {@code description}/{@code category}/{@code manufacturer} so the POS read screens can
 * resolve line-item display fields from catalog (replacing the local business Item) — the path toward retiring Item.
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductRef {
    private Long id;
    private String sku;
    private String name;
    private String unit;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;
    private String description;    // M4d
    private String category;       // M4d — category name
    private String manufacturer;   // M4d

    /**
     * Pharmacy clinical flags (review B1). Carried here so the sell saga can enforce "prescription-only" from the
     * ref it ALREADY fetches per line — the same reason {@code taxRate} rides along. Null on refs built by older
     * callers/tests; treat null as false.
     */
    private Boolean rxRequired;
    private Boolean controlledSubstance;

    /** Back-compat constructor for price-focused callers (sell saga, tests) written before M4d added display fields. */
    public ProductRef(Long id, String sku, String name, String unit, BigDecimal sellingPrice, BigDecimal taxRate) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.unit = unit;
        this.sellingPrice = sellingPrice;
        this.taxRate = taxRate;
    }
}
