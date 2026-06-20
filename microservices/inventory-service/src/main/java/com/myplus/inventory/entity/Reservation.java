package com.myplus.inventory.entity;

import com.myplus.commerce.contracts.dto.ReservationStatus;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A stock hold in the sell↔stock saga (slice 33, Phase 6a). RESERVED holds stock (per-batch FEFO picks)
 * without decrementing it; CONFIRMED decrements; RELEASED returns the hold. {@code reservationId} is the
 * external handle used by confirm/release; {@code idempotencyKey} dedupes a caller's retried reserve.
 */
@Entity
@Table(name = "reservations",
        uniqueConstraints = @UniqueConstraint(name = "uq_resv_org_idem", columnNames = {"organization_id", "idempotency_key"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Reservation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", unique = true, nullable = false)
    private String reservationId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    private Long organizationId;
    private Long userId;
    private String userType;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<ReservationPick> picks = new ArrayList<>();

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }

    public void addPick(ReservationPick p) { p.setReservation(this); this.picks.add(p); }
}
