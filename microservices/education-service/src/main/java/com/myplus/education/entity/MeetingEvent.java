package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A parents' evening — slice edu-3.4 (delivered on the SCHED-1 core).
 *
 * <h3>What this entity is, and what it deliberately is not</h3>
 *
 * It is the school's DECISION to run an evening, and to open or close booking on it. It is <b>not</b> the
 * slots and <b>not</b> the bookings: those live in the shared scheduling core and are reached through
 * {@code SchedulingClient}, keyed by {@link #schedulingRef()}.
 *
 * <p>That division is decision D-9. Education owning slots itself would have meant a second scheduler on a
 * platform that already had one — and re-solving double-booking in a second place, when the core now
 * enforces it with a UNIQUE key for every consumer at once.
 */
@Entity
@Table(name = "meeting_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MeetingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "meeting_event_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private MeetingEventStatus status = MeetingEventStatus.OPEN;

    /** Guidance shown to families on the booking screen. */
    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    /**
     * The opaque key this event's slots carry in the scheduling core.
     *
     * <p>Derived from the id rather than stored: a second column would be a second source of truth for a
     * value that can never differ from the id it is built from. The core treats it as a string and never
     * parses it — the prefix exists for a human reading the core's data, not for the core.
     */
    public String schedulingRef() {
        return id == null ? null : "EDU-EVT-" + id;
    }
}
