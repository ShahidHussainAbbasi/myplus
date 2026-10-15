package com.myplus.inventory.entity;

import java.math.BigDecimal;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDate;

/** One FEFO batch allocation of a {@link Reservation} (slice 33, Phase 6a). */
@Entity
@Table(name = "reservation_picks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReservationPick {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_ref")
    private Reservation reservation;

    private Long stockEntryId;
    private Long productId;
    private String batchNo;
    @Column(precision = 19, scale = 4)   // D14 — NUM-1: at (38,2) a 0.075-box pick became 0.08
    private BigDecimal quantity;
    private LocalDate expiryDate;

    /** #17 P3: unit cost of the batch this pick took, stamped at reserve time. */
    @Column(name = "unit_cost", precision = 19, scale = 6)
    private BigDecimal unitCost;

    /** PR-3a — the caller's order line this pick was taken for (V13). Null on older picks and line-less callers. */
    @Column(name = "line_ref")
    private Integer lineRef;

    /** G2 inverse saga (slice 34): how much of this pick has already been returned to stock. Caps repeated
     *  partial returns so a batch is never restored beyond what was originally picked from it. Default 0. */
    @Builder.Default
    @Column(precision = 19, scale = 4)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;
}
