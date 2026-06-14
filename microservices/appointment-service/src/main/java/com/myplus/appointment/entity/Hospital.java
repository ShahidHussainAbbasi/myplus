package com.myplus.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "hospital")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Hospital {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;
    private String email;
    private String phone;
    private String logoUrl;

    // Flat location (geo lookups are static client-side lists — Decision G).
    private String country;
    private String state;
    private String city;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
