package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An e-commerce order (E1, slice 46). The sale itself (stock/tax/payment/receipt) is the reused trade saga; this
 * adds the fulfilment lifecycle, referencing the trade sale by {@code invoiceNo}. Org-scoped.
 */
@Entity
@Table(name = "orders", indexes = @Index(name = "idx_order_org", columnList = "organization_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "invoice_no")
    private String invoiceNo;          // the trade sale this order is

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_contact")
    private String customerContact;

    @Builder.Default
    @Column(name = "source")
    private String source = "POS";          // POS | STOREFRONT (slice 47)

    @Builder.Default
    @Column(name = "payment_mode")
    private String paymentMode = "COD";     // COD | CARD | … (PSP later)

    @Builder.Default
    @Column(name = "payment_status")
    private String paymentStatus = "PENDING";  // PENDING | PAID | FAILED (slice 48)

    @Column(name = "payment_ref")
    private String paymentRef;              // charge id (sandbox now; PSP later)

    @Column(name = "reservation_id")
    private String reservationId;           // the inventory saga hold this order drew down (slice 49)

    @Column(precision = 19, scale = 2)
    private BigDecimal total;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_status")
    private FulfilmentStatus fulfilmentStatus = FulfilmentStatus.NEW;

    @Column(name = "shipping_address")
    private String shippingAddress;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
