package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A cash-drawer movement within a shift (POS day-close, slice 39). Org-scoped, linked to a shift. */
@Entity
@Table(name = "cash_movement", indexes = @Index(name = "idx_cashmove_shift", columnList = "shift_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "shift_id")
    private Long shiftId;

    @Enumerated(EnumType.STRING)
    private MovementType type;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    private String reason;

    @Column(name = "dated")
    private LocalDateTime dated;

    @PrePersist
    void onCreate() { if (dated == null) dated = LocalDateTime.now(); }
}
