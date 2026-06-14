package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Entity
@Table(name = "staff")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "staff_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    // Audit: which user created this row. Not used for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to.
    @Column(name = "organization_id")
    private Long organizationId;

    private String email;

    private String mobile;

    private String phone;

    private String address;

    private String designation;

    @Column(name = "date_of_birth")
    private LocalDate staffDOB;

    private String gender;

    @Column(name = "time_in")
    private LocalTime timeIn;

    @Column(name = "time_out")
    private LocalTime timeOut;

    private String qualification;

    @Column(name = "martial_status")
    private String martialStatus;

    private String status;

    private LocalDateTime dated;

    @Column(updatable = false)
    private LocalDateTime updated;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "staff_grades",
        joinColumns = @JoinColumn(name = "staff_id"),
        inverseJoinColumns = @JoinColumn(name = "grade_id")
    )
    private List<Grade> grades;

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
