package com.myplus.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * E5 — one bounded, explained period during which a platform operator may reach one customer's data.
 *
 * <h3>Open-ness is derived, never stored</h3>
 * There is no {@code status} column and {@link #isOpen()} computes the answer from the clock. A stored status
 * would need a job to expire it, and a job that does not run is a session that never ends — precisely the
 * failure this table exists to make impossible. The same reasoning as {@code audit_event} keeping before and
 * after rather than a "changed" flag.
 *
 * <h3>Why writes are a separate permission</h3>
 * Reading a customer's figures to answer their question and changing their records are different asks, and
 * only the second is irreversible from the customer's point of view. {@code writeApproved} starts false, so
 * consent is a decision rather than a formality.
 */
@Entity
@Table(name = "support_session")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupportSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    /** Stamped at open — the record must outlive the staff member's user row. */
    @Column(name = "operator_email", length = 160)
    private String operatorEmail;

    /** The customer this session is over. Never the operator's own organization. */
    @Column(name = "subject_org_id", nullable = false)
    private Long subjectOrgId;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "write_approved", nullable = false)
    private boolean writeApproved;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    /** Open means not closed AND not expired. Both halves, evaluated now. */
    @Transient
    public boolean isOpen() {
        return closedAt == null && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
