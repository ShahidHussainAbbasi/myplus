package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A kind of leave this school grants — "Casual", "Sick", "Earned", "Unpaid".
 *
 * Slice 2.3, design D2. An ENTITY rather than a setting, for the reason 1.1 gave for terms and 1.4 for
 * grade bands: <b>the entity IS the configuration</b> for list-shaped things, and {@code common-settings}
 * stores scalars. There is deliberately no {@code edu.leave.typeCount} and no hard-coded type list.
 *
 * <p>{@link #annualQuota} feeds a <b>derived</b> balance ({@code quota − approved days taken this year}).
 * No balance is stored anywhere: a stored balance is a cache of a sum, and the moment a request is
 * cancelled, back-dated or corrected it is wrong with nothing saying so. This is the number a teacher will
 * argue about, so it is exactly the number that must not be able to drift (1.4 D4's rule, applied).
 */
@Entity
@Table(name = "leave_type", uniqueConstraints = {
        // One "Casual" per school. Case-insensitivity comes from the column collation — see standard D3c.
        @UniqueConstraint(name = "uk_leave_type_name", columnNames = { "organization_id", "name" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_type_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    /** Days per year. Null means "no quota" — unpaid leave is usually uncapped. */
    @Column(name = "annual_quota")
    private Integer annualQuota;

    /**
     * Whether these days are paid. Nothing in this slice acts on it — it is recorded now so Phase 4's
     * payroll has the fact when it needs to deduct, rather than having to backfill a judgement.
     */
    @Column(name = "paid", nullable = false)
    private boolean paid;

    @Column(name = "sequence")
    private Integer sequence;

    /** Audit: which user created this row. Not used for data scoping. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Tenant scope: which organization this row belongs to. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    @PrePersist
    void prePersist() {
        if (dated == null) dated = LocalDateTime.now();
    }
}
