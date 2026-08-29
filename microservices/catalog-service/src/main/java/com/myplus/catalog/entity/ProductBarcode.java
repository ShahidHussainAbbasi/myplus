package com.myplus.catalog.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * U7 — a code the SHOP prints, which means "this many of this, in this unit".
 *
 * <p>A pharmacy sticks {@code LP-4471} on a strip holder and scanning it sells <b>one tablet</b>. The
 * manufacturer's barcode on the pack cannot do that: it is printed by whoever made the pack and can only ever
 * mean the pack.
 *
 * <h3>Why this is a separate table and not more columns on Product</h3>
 * A product has one identity and, in this design, one pack size. It can have <b>many</b> codes — the
 * manufacturer's, a single-tablet sticker, a twelve-pack shelf label — and each carries its own quantity.
 * That is a one-to-many, and the established systems (SAP, Odoo) model it exactly this way: multiple barcodes
 * per product, one per packaging level, each with a quantity.
 *
 * <h3>⚠ The rule this table cannot enforce itself</h3>
 * An alias must never shadow a real product barcode. If a manufacturer GTIN were registered here as
 * "1 tablet", every scan of that pack would sell one tablet — the commonest transaction in the shop,
 * mis-priced, silently, until the takings looked wrong. The constraint spans two tables, so it lives in
 * {@code ProductBarcodeService} and is checked in BOTH directions.
 */
@Entity
@Table(name = "product_barcode",
       uniqueConstraints = @UniqueConstraint(name = "uk_product_barcode_org_code",
                                             columnNames = {"organization_id", "barcode"}),
       indexes = {@Index(name = "idx_product_barcode_product", columnList = "product_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductBarcode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** The label the shop prints. Unique per tenant — one code, one meaning. */
    @Column(nullable = false, length = 64)
    private String barcode;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * {@code PACK} or {@code LOOSE}.
     *
     * <p>A String rather than {@code @Enumerated} on a MySQL enum column: adding a value to a MySQL enum needs
     * an {@code ALTER … MODIFY} that {@code ddl-auto} will not generate, and this codebase has already lost
     * time to that ("Data truncated for column").
     */
    @Builder.Default
    @Column(name = "sold_unit", nullable = false, length = 8)
    private String soldUnit = "LOOSE";

    /** How many of {@link #soldUnit} this code means. A whole number; the service refuses anything else. */
    @Builder.Default
    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
