package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "vehicle_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "vehicle_name", nullable = false)
    private String name;

    @Column(name = "vehicle_number", nullable = false)
    private String number;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "driver_mobile")
    private String driverMobile;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_mobile")
    private String ownerMobile;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String status;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    @Column(name = "school_id")
    private Long schoolId;

    @PrePersist
    void prePersist() {
        dated = LocalDateTime.now();
        updated = LocalDateTime.now();
        if (status == null) status = "Active";
    }

    @PreUpdate
    void preUpdate() {
        updated = LocalDateTime.now();
    }
}
