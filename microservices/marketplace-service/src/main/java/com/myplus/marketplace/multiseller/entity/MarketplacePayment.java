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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1e2 — one payment fact of a marketplace order (V29): a CHARGE or a REFUND, recorded once (idempotency key).
 * MKT-1g's settlement ledger reads these; nothing is paid out to a seller before it.
 */
@Entity
@Table(name = "mkt_payment")
@Getter
@Setter
@NoArgsConstructor
public class MarketplacePayment {

    public static final String CHARGE = "CHARGE";
    public static final String REFUND = "REFUND";
    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mkt_order_id", nullable = false)
    private Long mktOrderId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_ref", length = 80)
    private String providerRef;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
