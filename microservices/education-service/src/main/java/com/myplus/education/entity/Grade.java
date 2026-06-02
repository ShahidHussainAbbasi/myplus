package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "grade")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grade_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String code;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String section;

    @Column(name = "time_from")
    private LocalTime timeFrom;

    @Column(name = "time_to")
    private LocalTime timeTo;

    private String status;

    @Column(name = "school_id")
    private Long schoolId;

    private Float fee;

    private Long room;

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
