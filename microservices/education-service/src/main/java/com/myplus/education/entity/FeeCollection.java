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

    // Audit: which user created this row. Not used for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to.
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "enroll_no")
    private String enrollNo;

    @Column(name = "discount_type")
    private String discountType;

    @Column(name = "discount")
    private Integer discount;

    @Column(name = "due_day_of_month")
    private Integer dueDayOfMonth;

    @Column(name = "due_amount")
    private Integer dueAmount;

    @Column(name = "fee")
    private Integer fee;

    @Column(name = "fee_paid")
    private Integer feePaid;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "other_dues")
    private Integer otherDues;

    @Column(name = "other_dues_description")
    private String otherDuesDescription;

    @Column(name = "payee")
    private String payee;

    @Column(name = "recieved_by")
    private String receivedBy;

    @Column(name = "recieved_in")
    private String receivedIn;

    @Column(name = "check_no")
    private String checkNo;

    @Column(name = "vehicle_fee")
    private Integer vehicleFee;

    @Column(name = "due_balance")
    private Integer dueBalance;

    /**
     * Slice 1.1 (D4): which term this collection belongs to. NULLABLE and never backfilled — see
     * {@link Attendance#getTermId()}. Lets "Term 1 dues" be asked without re-deriving it from dates.
     */
    @Column(name = "term_id")
    private Long termId;
}
