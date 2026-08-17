package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A line of an {@link Order} (slice 51) — the product + quantity sold. Persisted so a cancellation can return the
 * exact quantities to inventory via the G2 inverse saga (no need to ask the storefront again). Child of Order.
 */
@Entity
@Table(name = "order_items", indexes = @Index(name = "idx_order_item_order", columnList = "order_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    /**
     * OMS O4 — what this line was SOLD AS, snapshotted at write (V14).
     *
     * <p>Not a read-through to the catalog: renaming or deleting a product must not change what an already
     * invoiced order says it sold, and opening the back office must not depend on catalog-service being up.
     * Null for rows written before V14 — the UI shows the product id rather than inventing a current name.
     */
    @Column(name = "product_name")
    private String productName;

    private Integer quantity;

    /**
     * OMS O5b — how much of this line has physically gone out (0..quantity).
     *
     * <p>The single source of truth for fulfilment: the header status is DERIVED from these, so a partly shipped
     * order cannot disagree with its own lines. Backfilled by V15 to {@code quantity} for orders already past
     * dispatch, because a return reverses what SHIPPED and reversing 0 would silently return nothing to stock.
     */
    @Builder.Default
    @Column(name = "quantity_shipped", nullable = false)
    private Integer quantityShipped = 0;

    /**
     * OMS O5c — accepted but not yet invoiced, because it could not be filled when the order was placed.
     *
     * <p>Invariant: {@code quantity = invoiced + quantityBackordered}, and {@code quantityShipped ≤ invoiced}.
     * These units exist only on the order — inventory is told nothing about them, so stock never goes negative
     * and no phantom reservation is created.
     */
    @Builder.Default
    @Column(name = "quantity_backordered", nullable = false)
    private Integer quantityBackordered = 0;

    @Column(precision = 19, scale = 2)
    private BigDecimal price;

    /**
     * The concession the rep gave on THIS line, as an amount off the line total (V23).
     *
     * <p>A distribution rep negotiates per product, not per document — a shop takes the saline at list and
     * argues over the Ringer. The whole-document {@code tradeDiscount} could not express that, and without a
     * per-line field the rep's only option was to overwrite the PRICE, which loses the fact a discount was
     * given at all: the invoice then shows a lower trade price rather than list-less-discount, and "what did
     * we give away this month" becomes unanswerable.
     *
     * <p>An AMOUNT, not a percentage, because that is what {@code Sell.discount} on the invoice side takes —
     * carrying a percentage here would mean two places deciding what it is a percentage OF. The screen lets
     * the rep type either and converts before it leaves the browser.
     *
     * <p>Null on every line nobody discounted, which leaves the order total exactly what it was.
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal discount;
}
