package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A persistent storefront cart (slice 68, E3). Store-scoped, owned server-side. A guest is identified by an opaque
 * {@code cartToken} (UUID, kept in the browser); once the shopper logs in the cart links to their
 * {@link StorefrontCustomer} ({@code customerAccountId}) and a prior guest cart is merged in. One ACTIVE cart per
 * (org, cartToken); CONVERTED once its order is placed.
 */
@Entity
@Table(name = "cart",
        uniqueConstraints = @UniqueConstraint(name = "uq_cart_org_token", columnNames = {"organization_id", "cart_token"}),
        indexes = @Index(name = "idx_cart_account", columnList = "customer_account_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cart {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "cart_token")
    private String cartToken;

    @Column(name = "customer_account_id")
    private Long customerAccountId;     // set when the shopper is logged in (slice 61)

    private String status;              // ACTIVE | CONVERTED

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "cart_ref")
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
