package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "attendance", uniqueConstraints = {@UniqueConstraint(columnNames = "attendance_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attendance_id", unique = true, nullable = false)
    private Long id;

    // Audit: which user created this row. Not used for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to.
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "enroll_no")
    private String en;

    @Column(name = "student_name")
    private String sn;

    @Column(name = "grade_Id")
    private Long grid;

    @Column(name = "grade_name")
    private String gn;

    @Column(name = "time_in")
    private LocalTime in;

    @Column(name = "time_out")
    private LocalTime out;

    @Column(name = "status")
    private String status;

    @Column(name = "dated_time")
    private LocalDateTime dt;

    // The day the attendance is for (marking day). Upsert key with (organization_id, enroll_no).
    @Column(name = "att_date")
    private LocalDate attDate;

    @Column(name = "remarks")
    private String rem;

    /**
     * Slice 1.1 (D4): which term this attendance belongs to. NULLABLE and never backfilled — rows
     * written before terms existed keep NULL, and reports treat NULL as "before terms existed" rather
     * than hiding the row. A school that has not set terms up keeps working exactly as before.
     */
    @Column(name = "term_id")
    private Long termId;

    @PrePersist
    void prePersist() {
        if (status == null) status = "Active";
        if (dt == null) dt = LocalDateTime.now();
    }
}
