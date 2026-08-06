package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * OMS O2: the merchant-facing order reference, e.g. {@code SO-000123}. Per-org series, allocated MAX+1
     * inside the creating transaction and made safe by UNIQUE(organization_id, order_seq) — the same allocation
     * invoice_seq, credit_note_seq and quote_seq use.
     *
     * <p>Public tracking resolves by THIS, not by the primary key: an auto-increment id is guessable, so the old
     * {@code ?ref=123} let anyone walk the id space, and it was useless to quote to a customer on the phone.
     */
    @Column(name = "order_seq")
    private Long orderSeq;

    @Column(name = "order_no", length = 32)
    private String orderNo;

    /**
     * OMS O2: same-key-same-order. O1 made the SALE idempotent on the cart token; without this the ORDER row was
     * not, so a double-submit replayed one invoice but inserted TWO orders — picked and shipped twice. The
     * UNIQUE(organization_id, idempotency_key) index is what makes it race-safe: a read-then-write check loses
     * to a concurrent submit.
     */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /**
     * OMS O2: optimistic lock. Several people touch one order through a working day — a packer and someone
     * cancelling can collide, and last-write-wins silently discarded one of them. {@code SalesQuote} got this in
     * 4b for the same reason; orders have the higher exposure.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "invoice_no")
    private String invoiceNo;          // the trade sale this order is

    /**
     * OMS O1 — did this order reach the books? {@code POSTED} once business-service returned an invoice;
     * {@code LEGACY_UNPOSTED} for orders placed before O1, which produced no sale and are not back-posted
     * (that would write revenue into closed periods). Makes the pre-O1 backlog findable instead of
     * indistinguishable from a fresh order.
     */
    @Builder.Default
    @Column(name = "books_status", nullable = false, length = 20)
    private String booksStatus = "LEGACY_UNPOSTED";

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_contact")
    private String customerContact;

    @Column(name = "customer_account_id")
    private Long customerAccountId;     // storefront shopper account, when logged in (slice 61)

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
    private String paymentRef;

    // Refunds (slice 70): paymentStatus also takes REFUNDED | PARTIALLY_REFUNDED.
    @Column(name = "refund_ref")
    private String refundRef;

    @Column(name = "refunded_amount", precision = 19, scale = 2)
    private BigDecimal refundedAmount;              // charge id (sandbox now; PSP later)

    @Column(name = "reservation_id")
    private String reservationId;           // the inventory saga hold this order drew down (slice 49)

    @Column(name = "reservation_status")
    private String reservationStatus;       // PENDING (held, not yet confirmed) | CONFIRMED — recovery relay (slice 52)

    @Column(precision = 19, scale = 2)
    private BigDecimal total;

    // Checkout breakdown (slice 69): total = subTotal + taxTotal + shippingFee.
    @Column(name = "sub_total", precision = 19, scale = 2)
    private BigDecimal subTotal;

    @Column(name = "tax_total", precision = 19, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "shipping_fee", precision = 19, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "shipping_method")
    private String shippingMethod;

    @Column(name = "coupon_code")
    private String couponCode;       // applied promo code (slice 72)

    @Column(name = "discount_amount", precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_status")
    private FulfilmentStatus fulfilmentStatus = FulfilmentStatus.NEW;

    @Column(name = "shipping_address")
    private String shippingAddress;

    @Column(name = "return_reason")
    private String returnReason;     // why the shopper returned it (slice 71)

    // Order lines — persisted so a cancellation returns the exact quantities to inventory (slice 51).
    @Builder.Default
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
