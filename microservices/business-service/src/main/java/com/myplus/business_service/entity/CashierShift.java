package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A cashier till session (POS day-close, slice 39). Opened with a cash float, sales during it are stamped with its
 * id, and it is closed with a Z report (counted vs expected cash → variance). One OPEN shift per cashier at a time.
 * Org-scoped.
 */
@Entity
@Table(name = "cashier_shift", indexes = {
        @Index(name = "idx_shift_org_user_status", columnList = "organization_id,user_id,status")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CashierShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;                // the cashier

    @Column(name = "opening_float", precision = 19, scale = 2)
    private BigDecimal openingFloat;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private ShiftStatus status = ShiftStatus.OPEN;

    @Column(name = "counted_cash", precision = 19, scale = 2)
    private BigDecimal countedCash;    // what the cashier counted at close

    @Column(name = "expected_cash", precision = 19, scale = 2)
    private BigDecimal expectedCash;   // computed at close

    @Column(name = "variance", precision = 19, scale = 2)
    private BigDecimal variance;       // counted − expected

    @Column(name = "notes")
    private String notes;
}
