package com.myplus.catalog.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** PR-1 — one change to a product's selling price. Columns mirror V21__product_price_history.sql. Append-only. */
@Entity
@Table(name = "product_price_history")
@Getter @Setter
public class ProductPriceHistory {

    public static final String MANUAL = "MANUAL";
    public static final String PURCHASE = "PURCHASE";
    public static final String IMPORT = "IMPORT";
    /** PR-2 — a purchase set the price through the business's markup rule (Auto). */
    public static final String MARKUP = "MARKUP";
    /** PR-4 — the owner approved a price a purchase proposed (ref = that purchase's bill). */
    public static final String APPROVAL = "APPROVAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "old_price", precision = 19, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", precision = 19, scale = 2)
    private BigDecimal newPrice;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "ref", length = 80)
    private String ref;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
