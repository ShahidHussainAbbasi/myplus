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
    /**
     * U1 — selling by the piece. See {@code docs/pack-and-loose-selling-design.md}.
     *
     * <p>These travel on the ref the sale ALREADY fetches, so the loose rate is derived where the price
     * already comes from — server-side in {@code buildLines} — and never in the browser. A rate computed in
     * JavaScript and posted back would arrive looking like a cashier discounting below catalog, tripping the
     * margin guard on the shop's most ordinary transaction.
     *
     * <p>All optional. A caller that ignores them behaves exactly as before, which is what lets the order and
     * storefront pipelines carry on untouched.
     */
    private Integer packSize;
    private String looseUnit;
    private String looseUnitPlural;
    private Boolean allowLoose;
    /** {@code PACK} or {@code LOOSE} — which unit a line starts in. */
    private String defaultSellUnit;

    private Boolean rxRequired;
    private Boolean controlledSubstance;

    /**
     * C6 — per-product tracking policy, carried on the ref so a caller can enforce without a second lookup.
     *
     * <p>Same reason {@code allowLoose} and {@code rxRequired} are here: the sell path already holds a
     * ProductRef when it needs to decide, and asking catalog again mid-sale would put a remote call on the
     * hot path — the thing V44 refused for the serial check and the performance standard forbids generally.
     *
     * <p>Boxed Booleans, like their neighbours: an older catalog that does not populate them sends null, and
     * a caller can tell "not set" from "explicitly false". A primitive would silently read as false.
     */
    private Boolean requiresSerial;
    private Boolean tracksBatch;

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
