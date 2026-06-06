package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "student_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    // Audit: which user created this row. Not used for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to.
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "year_start")
    private LocalDate ys;

    @Column(name = "year_end")
    private LocalDate ye;

    @Column(name = "enroll_no")
    private String enrollNo;

    @Column(name = "enroll_date")
    private LocalDate enrollDate;

    @Column(name = "fee_mode")
    private String feeMode;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String email;

    private String mobile;

    private String address;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String gender;

    @Column(name = "blood_group")
    private String bloodGroup;

    private String status;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "guardian_id")
    private Long guardianId;

    @Column(name = "grade_id")
    private Long gradeId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "discount_id")
    private Long discountId;

    @Column(name = "new_discount")
    private Integer nd;

    @Column(name = "discount_in")
    private String di;

    private Float fee;

    private Integer dueDay;

    @Column(name = "vehicle_fare")
    private Integer vf;

    @Column(name = "place_0f_birth")
    private String pob;

    @Column(name = "mother_name")
    private String mn;

    @Column(name = "watts_app")
    private String wa;

    @Column(name = "religion")
    private String religion;

    @PrePersist
    void prePersist() {
        dated = LocalDateTime.now();
        updated = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updated = LocalDateTime.now();
    }
}
