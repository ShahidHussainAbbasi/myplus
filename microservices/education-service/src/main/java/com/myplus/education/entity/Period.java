package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One slot in the school day — "Period 1, 08:00–08:45".
 *
 * Slice 2.1 (docs/slices/edu-2.1-timetable.md), design D1. Periods are an ENTITY the school defines, not
 * a setting: a period list is list-shaped, and common-settings stores scalars. Same conclusion as 1.1's
 * terms and 1.4's grade bands — <b>the entity IS the configuration</b>.
 *
 * <p>Why fixed periods rather than free start/end times on each timetable entry: with periods, "two
 * things in the same slot" is an equality test. That is what makes clash detection simple AND what makes
 * it enforceable by a UNIQUE key (D4). Free times would turn it into interval-overlap arithmetic that no
 * database constraint can express — and real schools ring a bell anyway.
 *
 * <p>A non-teaching period (break, assembly) is just a row with {@link #teaching} false. It shows on the
 * grid as a labelled band and needs no special case anywhere in the code.
 */
@Entity
@Table(name = "period")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Period {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "period_id", unique = true, nullable = false)
    private Long id;

    /** As the school names it: "Period 1", "Break", "Zero Period". */
    @Column(name = "name", nullable = false)
    private String name;

    /** Display order down the grid. Not derived from startTime — a school may order them its own way. */
    @Column(name = "sequence")
    private Integer sequence;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** False for break/assembly/lunch: the band renders, but nothing is scheduled into it. */
    @Column(name = "teaching", nullable = false)
    private boolean teaching;

    /** Audit: which user created this row. Not used for data scoping. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Tenant scope: which organization this row belongs to. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    @PrePersist
    void prePersist() {
        if (dated == null) dated = LocalDateTime.now();
    }
}
