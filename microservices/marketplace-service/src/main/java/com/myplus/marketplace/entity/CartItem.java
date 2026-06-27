package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A line of a {@link Cart} (slice 68). product_name + unit_price are a snapshot resolved authoritatively from catalog
 * at add-time (never trusted from the client) so the displayed cart can't be tampered with.
 */
@Entity
@Table(name = "cart_item", indexes = @Index(name = "idx_cart_item_cart", columnList = "cart_ref"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    private Integer quantity;
}
