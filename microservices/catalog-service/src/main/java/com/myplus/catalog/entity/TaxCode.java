package com.myplus.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Multi-rate tax: a per-org tax-code (tax-class) master row, e.g. {@code Standard 18%} / {@code Reduced 5%} /
 * {@code Zero 0%} / {@code Exempt}. A {@link Product} references a code by id; the code supplies the rate, so a
 * statutory rate change updates this one row and propagates to every product on next read. At most one code per org
 * is {@code isDefault} (the fallback rate for products with no code — enforced in the service on upsert).
 */
@Entity
@Table(name = "tax_code", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tax_code_org_name", columnNames = {"organization_id", "name"})})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaxCode {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** The tax rate (%) this code applies. Null treated as 0. */
    @Column(name = "rate", precision = 19, scale = 2)
    private BigDecimal rate;

    @Builder.Default
    @Column(name = "is_default")
    private Boolean isDefault = false;

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
