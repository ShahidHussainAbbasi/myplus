package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-organization tax policy (G3 tax engine, slice 35). One row per tenant. Governs whether prices are
 * tax-exclusive or -inclusive, the org default rate (fallback when a product has no rate), and the label /
 * registration number printed on the receipt. Mirrors education {@code FeeSetting}.
 */
@Entity
@Table(name = "tax_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tax_setting_org", columnNames = "organization_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;       // tenant scope (one setting per org)

    @Column(name = "user_id")
    private Long userId;               // audit: who last changed it

    /** Whether catalog prices are pre-tax (add on top) or tax-inclusive (back out). */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_mode")
    private TaxMode taxMode = TaxMode.EXCLUSIVE;

    /** Org default rate (%), used when a product has no taxRate. */
    @Builder.Default
    @Column(name = "default_rate", precision = 19, scale = 2)
    private BigDecimal defaultRate = BigDecimal.ZERO;

    /** Printed label, e.g. "VAT" / "GST" / "Sales Tax". */
    @Builder.Default
    @Column(name = "tax_label")
    private String taxLabel = "Tax";

    /** Tax registration number printed on the receipt (optional). */
    @Column(name = "tax_reg_no")
    private String taxRegNo;

    /** Master switch — when off, no tax is applied to sales. */
    @Builder.Default
    @Column(name = "enabled")
    private Boolean enabled = false;

    private LocalDateTime updated;

    @PrePersist @PreUpdate
    void touch() { updated = LocalDateTime.now(); }
}
