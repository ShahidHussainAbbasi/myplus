package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "fee_collection", uniqueConstraints = {@UniqueConstraint(columnNames = "fc_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FeeCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fc_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "enroll_no")
    private String en;

    @Column(name = "discount_type")
    private String dt;

    @Column(name = "discount")
    private Integer d;

    @Column(name = "due_day_of_month")
    private Integer dd;

    @Column(name = "due_amount")
    private Integer da;

    @Column(name = "fee")
    private Integer f;

    @Column(name = "fee_paid")
    private Integer fp;

    @Column(name = "payment_date")
    private LocalDate pd;

    @Column(name = "other_dues")
    private Integer od;

    @Column(name = "other_dues_description")
    private String odd;

    @Column(name = "payee")
    private String p;

    @Column(name = "recieved_by")
    private String rb;

    @Column(name = "recieved_in")
    private String ri;

    @Column(name = "check_no")
    private String cn;

    @Column(name = "vehicle_fee")
    private Integer vf;

    @Column(name = "due_balance")
    private Integer db;
}
