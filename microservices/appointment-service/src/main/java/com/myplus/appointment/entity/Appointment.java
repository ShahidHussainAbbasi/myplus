package com.myplus.appointment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointment")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Long hospitalId;
    private Long doctorId;
    private Long patientId;

    private String appointmentType;
    private String fee;
    private String dateTime;
    private String date;

    private Integer patientsToVisit;
    private Integer patientsAppointed;
    private Integer patientsVisited;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
