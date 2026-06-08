package com.myplus.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "doctor")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Long hospitalId;

    @Column(nullable = false)
    private String name;
    private String speciality;
    private String fee;
}
