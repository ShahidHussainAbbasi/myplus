package com.myplus.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A bookable window of a provider's time — slice SCHED-1 (B2).
 *
 * <h3>Why this exists beside the clinic's queue</h3>
 *
 * The clinic books a QUEUE: "you are number 7 of 20 for Dr X today". A parents' evening books a DIARY:
 * "your ten minutes with Miss Khan at 18:20". Those are genuinely different shapes, and forcing either into
 * the other is how a shared core starts lying about one of its consumers.
 *
 * <p>So the scheduling core supports both modes. A clinic {@link Booking} has {@code slotId == null} and
 * carries a queue number; a diary booking points at one of these. This is a refinement of decision D-9: the
 * clinic proves queue-mode is a real mode rather than a workaround, and education proves slot-mode is not
 * speculative.
 *
 * <h3>Real DATETIMEs, unlike the clinic's legacy strings</h3>
 *
 * {@code booking.date} and {@code booking.date_time} are VARCHARs from the original schema and are left
 * alone. This table uses real {@code DATETIME}s, because a UNIQUE key over a formatted string enforces
 * nothing useful — which is half of why the double-booking defect existed at all.
 */
@Entity
@Table(name = "slot", uniqueConstraints = {
        // THE guarantee: one slot per provider per start time. The constraint is what holds under
        // concurrency; SlotConflictDetector only produces a friendlier message first.
        @UniqueConstraint(name = "uk_slot_provider_time",
                columnNames = { "organization_id", "provider_id", "starts_at" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "slot_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Where it happens. Optional — a meeting need not have a venue the way a clinic session does. */
    @Column(name = "venue_id")
    private Long venueId;

    /** Whose time this is: a doctor, a teacher, whoever the consumer's domain calls a provider. */
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    /** How many bookings this slot accepts. 1 for a parents' evening; more for a group session. */
    @Column(name = "capacity", nullable = false)
    @Builder.Default
    private Integer capacity = 1;

    /**
     * What this slot belongs to, in the CONSUMER's own words — a parents' evening id, a clinic session.
     *
     * <p>Deliberately opaque to this service: the core schedules time, and the domain knows why. Giving it
     * a typed foreign key would drag education's (or the clinic's) concepts into a service that must not
     * know them — the mistake that made `appointment-service` a clinic in the first place.
     */
    @Column(name = "external_ref", length = 120)
    private String externalRef;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
