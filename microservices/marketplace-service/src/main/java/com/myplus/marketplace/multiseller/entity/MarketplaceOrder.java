package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** MKT-1e — the marketplace (parent) order (V28). Platform-owned: each seller's part is a {@link MarketplaceSellerOrder}. */
@Entity
@Table(name = "mkt_order")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    /** {@code MarketplaceStatus.Order}. */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "payment_mode", nullable = false, length = 16)
    private String paymentMode;

    /** {@code MarketplaceStatus.Payment}. */
    @Column(name = "payment_status", nullable = false, length = 24)
    private String paymentStatus;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 32)
    private String customerPhone;

    /** MKT-1e2 (V29): the account that PROVED this order is theirs — placed signed in, or claimed. Null otherwise. */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "delivery_address", nullable = false, length = 300)
    private String deliveryAddress;

    @Column(name = "city", nullable = false, length = 60)
    private String city;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal deliveryFee;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
