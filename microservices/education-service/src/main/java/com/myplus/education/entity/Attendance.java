package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "user_id", nullable = false)
    private Long userId;

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

    @Column(name = "remarks")
    private String rem;

    @PrePersist
    void prePersist() {
        if (status == null) status = "Active";
        if (dt == null) dt = LocalDateTime.now();
    }
}
