package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "alert")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Alerts {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "consumers")
    private String c;

    @Column(name = "user_type")
    private Long ut;

    @Column(name = "alert_type")
    private String at;

    @Column(name = "delivery_type")
    private String deliveryType;

    @Column(name = "delivery_channel")
    private String dc;

    @Column(name = "delivery_period")
    private String dp;

    @Column(name = "alert_heading")
    private String ah;

    @Column(name = "alert_message")
    private String am;

    @Column(name = "alert_signature")
    private String alertSignature;

    @Column(name = "start_date")
    private LocalDate sd;

    @Column(name = "end_date")
    private LocalDate ed;

    @Column(name = "status")
    private String st;
}
