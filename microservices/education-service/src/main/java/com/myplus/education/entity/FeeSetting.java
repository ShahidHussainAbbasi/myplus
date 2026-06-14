package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Per-organization fee policy, editable at runtime. One row per tenant.
 * Governs how dues are registered at student registration and how guardians pay.
 */
@Entity
@Table(name = "fee_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_fee_setting_org", columnNames = "organization_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FeeSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;       // tenant scope (one setting per org)

    @Column(name = "user_id")
    private Long userId;               // audit: who last changed it

    @Builder.Default
    @Column(name = "fee_cycle")
    private String feeCycle = "MONTHLY";

    @Builder.Default
    @Column(name = "due_day")
    private Integer dueDay = 10;

    /** Multi-month aging: accumulate dues for unpaid months. */
    @Builder.Default
    @Column(name = "aging_enabled")
    private Boolean agingEnabled = true;

    /** On student registration, create the opening due record. */
    @Builder.Default
    @Column(name = "auto_register_dues")
    private Boolean autoRegisterDues = true;

    /** GUARDIAN_VOUCHER | INDEPENDENT | BOTH */
    @Builder.Default
    @Column(name = "payment_mode")
    private String paymentMode = "BOTH";

    private LocalDateTime updated;

    @PrePersist @PreUpdate
    void touch() { updated = LocalDateTime.now(); }
}
