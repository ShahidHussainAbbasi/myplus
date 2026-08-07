package com.myplus.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "booking")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    /**
     * The slot this booking holds, or null for a clinic QUEUE booking (slice SCHED-1 B2).
     *
     * <p>Nullable on purpose, and it is the field that lets one table serve both booking modes: the
     * clinic's rows keep it null and carry a queue number instead, education's diary rows point at a
     * {@link Slot}. MySQL permits many NULLs in a unique index, so the queue rows simply do not
     * participate in {@code uk_booking_slot_attendee}.
     */
    @Column(name = "slot_id")
    private Long slotId;

    /**
     * Where it happens. NULLABLE since V5 (slice SCHED-1 B2).
     *
     * <p>It was NOT NULL from the clinic baseline, where every appointment is at a hospital. A neutral core
     * cannot require that: a parents' evening happens at the school, which is not a row anybody creates.
     */
    @Column
    private Long venueId;
    private Long providerId;
    private Long attendeeId;

    private String appointmentType;
    private String fee;
    private String dateTime;
    private String date;

    private Integer patientsToVisit;
    private Integer patientsAppointed;
    private Integer patientsVisited;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
