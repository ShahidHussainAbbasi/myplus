package com.myplus.inventory.entity;

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
    private Float quantity;
    private LocalDate expiryDate;

    /** G2 inverse saga (slice 34): how much of this pick has already been returned to stock. Caps repeated
     *  partial returns so a batch is never restored beyond what was originally picked from it. Default 0. */
    @Builder.Default
    private Float returnedQuantity = 0f;
}
