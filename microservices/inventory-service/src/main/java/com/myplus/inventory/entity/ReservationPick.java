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
}
