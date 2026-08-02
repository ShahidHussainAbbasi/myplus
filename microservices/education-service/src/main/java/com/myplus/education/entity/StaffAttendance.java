package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One staff member's day.
 *
 * Slice 2.3 (docs/slices/edu-2.3-staff-attendance-leave.md).
 *
 * <h3>The UNIQUE key student attendance never got</h3>
 *
 * {@code attendance} (students) has <b>no</b> unique key on {@code (organization_id, enroll_no, att_date)} —
 * it upserts through {@code findFirstBy…}, which is a check-then-act race: two concurrent saves of one
 * register create two rows. That is the same defect the review's finding D exposed in twelve duplicate
 * checks, and there is no reason to ship a thirteenth. This table carries the constraint from day one.
 *
 * <p>The student register's <i>good</i> decision is copied: one row per person per day, marked in a batch.
 */
@Entity
@Table(name = "staff_attendance", uniqueConstraints = {
        @UniqueConstraint(name = "uk_staff_attendance_day",
                columnNames = { "organization_id", "staff_id", "att_date" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StaffAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "staff_attendance_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    /** Snapshotted so a past register stays readable after a staff member leaves. */
    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "att_date", nullable = false)
    private LocalDate attDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StaffAttendanceStatus status;

    /** Actual arrival, when recorded. LATE is derived from this against the contracted start. */
    @Column(name = "time_in")
    private LocalTime timeIn;

    @Column(name = "time_out")
    private LocalTime timeOut;

    @Column(name = "remarks")
    private String remarks;

    /** Audit: which user marked the register. Not used for data scoping. */
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
        if (status == null) status = StaffAttendanceStatus.PRESENT;
        if (dated == null) dated = LocalDateTime.now();
    }
}
