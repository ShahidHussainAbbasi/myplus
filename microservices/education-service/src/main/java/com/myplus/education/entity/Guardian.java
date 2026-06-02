package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "guardian")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Guardian {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "guardian_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String email;

    private String mobile;

    private String phone;

    @Column(name = "temp_address")
    private String tempAddress;

    @Column(name = "perm_address")
    private String permAddress;

    private String gender;

    private String relation;

    private String occupation;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String status;

    private String cnic;

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
