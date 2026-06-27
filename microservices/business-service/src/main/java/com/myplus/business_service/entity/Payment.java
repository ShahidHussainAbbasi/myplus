package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One tender against a sale invoice (G5 payments, slice 37). A sale can have several (split payment: part cash +
 * part card); a sale return records a {@code REFUND} tender (negative amount). Org-scoped; linked to the invoice
 * by {@code customerHistoryId}.
 */
@Entity
@Table(name = "payment", indexes = @Index(name = "idx_payment_ch", columnList = "customer_history_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_history_id")
    private Long customerHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method")
    private PaymentMethod method;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    /** Optional external reference — card auth code, transaction id, cheque no, etc. */
    @Column(name = "reference")
    private String reference;

    @Column(name = "organization_id")
    private Long organizationId;       // tenant scope

    @Column(name = "user_id")
    private Long userId;               // audit: who took the payment

    @Column(name = "dated")
    private LocalDateTime dated;

    @PrePersist
    void onCreate() { if (dated == null) dated = LocalDateTime.now(); }
}
