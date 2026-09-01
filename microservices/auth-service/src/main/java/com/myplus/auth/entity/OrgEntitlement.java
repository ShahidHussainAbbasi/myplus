package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * E1 — what the PLATFORM sold this tenant, as distinct from what the tenant's owner switched on.
 *
 * <h3>The two questions this keeps apart</h3>
 * <pre>
 *   org_setting      org.cap.installments = true    the OWNER switched it on   (tenant decides)
 *   org_entitlement  installments = ACTIVE          the PLATFORM sold it       (operator decides)
 *
 *   effective = entitled AND enabled
 * </pre>
 * Before E1 only the first existed, and it is written by the tenant's own owner — so an owner granted
 * themselves whatever they liked. One table answering both questions would put them straight back in charge of
 * their own ceiling.
 *
 * <h3>A deviation from the plan, not a copy of it</h3>
 * The plan's contents live in {@code Plan} (code, ruling D-3). A row here exists only where ONE customer
 * differs from their plan: a contract term, a trial extension, an operator grant, or the grandfathering that
 * makes the E1 deploy inert. A tenant with no rows is entitled to exactly its plan.
 *
 * <h3>Dates are evaluated at RESOLVE time</h3>
 * {@code status = ACTIVE} with {@link #endsAt} in the past does not entitle. Expiry that depended on a job
 * rewriting rows would mean a missed run is free licensing — so the read applies the window, and any job that
 * tidies statuses later is housekeeping rather than enforcement.
 */
@Entity
@Table(name = "org_entitlement", uniqueConstraints = {
        @UniqueConstraint(name = "uq_org_entitlement", columnNames = {"organization_id", "capability"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /**
     * {@code Capability.code()}, e.g. {@code installments}.
     *
     * <p>A {@code String} rather than a mapped enum: a code this build does not recognise must read as "no
     * entitlement", not throw on deserialization, and a new capability must not need a schema change on a
     * licensing table before it can be sold.
     */
    @Column(nullable = false, length = 60)
    private String capability;

    /** {@code ACTIVE} · {@code SUSPENDED} · {@code EXPIRED}. Anything but ACTIVE does not entitle. */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * Where this row came from: {@code PLAN} · {@code GRANDFATHERED} · {@code CONTRACT} ·
     * {@code ADMIN_OVERRIDE}.
     *
     * <p>Not decoration. It is what lets an operator tell "this tenant bought it" from "this tenant has had it
     * since before we had a ceiling" — and the second group is the one to talk to when a plan is next
     * repriced. It is also the audit question E4 will ask first.
     */
    @Column(nullable = false, length = 20)
    private String source;

    /** Null means "since always". */
    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    /** Null means "no end". A date in the past does not entitle — see the class javadoc. */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    /** Free text for the operator: the contract, the ticket, the conversation. */
    @Column(length = 255)
    private String reason;

    /** The platform operator who granted or revoked. Null for rows written by the grandfather seeder. */
    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
