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
    private String email;
    private String mobile;
    private String address;
    private String availabe;

    // Schedule + appointment-capacity (drives the booking calc).
    private String dayFrom;
    private String dayTo;
    private String timeIn;
    private String timeOut;
    /** "count" -> fixed slots = offerValue; else time-based = (hours*60)/offerValue. */
    private String appointmentOfferType;
    private Integer appointmentOfferValue;
}
