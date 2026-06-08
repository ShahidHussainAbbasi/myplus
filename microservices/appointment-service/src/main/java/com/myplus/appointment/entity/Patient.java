package com.myplus.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "patient")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Patients booked via the public flow have no org login; org is taken from the target hospital.
    private Long organizationId;

    @Column(nullable = false)
    private String name;
    private String phone;
    private String email;
    private String address;
    private String cnic;

    @Builder.Default
    private boolean blocked = false;
}
