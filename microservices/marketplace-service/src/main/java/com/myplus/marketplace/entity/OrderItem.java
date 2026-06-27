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

    private Integer quantity;

    @Column(precision = 19, scale = 2)
    private BigDecimal price;
}
