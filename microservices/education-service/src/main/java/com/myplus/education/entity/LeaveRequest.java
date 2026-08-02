package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A teacher asks for time off.
 *
 * Slice 2.3, design D4: the request is a <b>range</b> — asked for once, Monday to Wednesday — while the
 * absences it produces are <b>per day</b>, because that is what a substitution needs. Approval expands the
 * range; this row stays as the single thing the teacher submitted and the head decided on.
 *
 * <p>D6 — a REJECTED request is kept and audited, never deleted: "I asked and was refused" is precisely
 * what gets disputed later.
 *
 * <p>D5 — a request exceeding the balance is still RECORDED. Over-quota warns; it does not block. A teacher
 * with two days left asking for five is a conversation, not an error, and the system's job is to make the
 * overage impossible to miss rather than to refuse it.
 */
@Entity
@Table(name = "leave_request")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_request_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    /** Snapshotted so a past request stays readable after a staff member leaves. */
    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    /**
     * Snapshotted for the same reason 1.5 D1 snapshots a report card's subject names: renaming "Casual" to
     * "Casual (paid)" next year must not retitle a decision already taken.
     */
    @Column(name = "leave_type_name")
    private String leaveTypeName;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /**
     * The number of days this request actually consumed — the count after non-session days were skipped
     * (D4). Stored because it is what the balance arithmetic counts, and re-deriving it later would give a
     * different answer once the term calendar changes. Not a cached balance: a record of what was granted.
     */
    @Column(name = "days_counted")
    private Integer daysCounted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LeaveRequestStatus status;

    @Column(name = "reason", length = 500)
    private String reason;

    /** Who approved or rejected it. Null while PENDING. */
    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_on")
    private LocalDateTime decidedOn;

    /** Audit: which user submitted the request. Not used for data scoping. */
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
        if (status == null) status = LeaveRequestStatus.PENDING;
        if (dated == null) dated = LocalDateTime.now();
    }
}
