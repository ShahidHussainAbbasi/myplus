package com.myplus.business_service.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Data;

/**
 * INST-3a — one recorded chase: "installment 3 of 6 is overdue, we saw it on Tuesday, we rang on Wednesday and
 * they promised Friday".
 *
 * <h3>This is a record, not an outbox</h3>
 * There are deliberately no {@code status}, {@code attempts} or {@code lastError} fields. An outbox is a queue
 * of things to <b>send</b>, and the customer chose "worklist first, no sending yet" — so those columns would
 * describe nothing today and would lie to INST-4 tomorrow.
 *
 * <p>What the shop lacks is not a queue. The Installments screen can already compute who is overdue right now;
 * what it cannot do is remember a phone call, so it makes the shop ring the same customer three times and
 * never ring another. {@link #actedAt}/{@link #outcome} are the half that turns a list into collections.
 *
 * <h3>{@code dedupeKey} is the design, not a detail</h3>
 * The scanner is a timer: it runs again in five minutes, after a restart, and twice at once during a rolling
 * deploy. The UNIQUE constraint is what makes all three harmless — idempotency enforced by the database rather
 * than by the scanner remembering what it did.
 *
 * <p>The key contains the <b>stage</b>, never the date the scan ran, so an installment that goes part-paid and
 * falls behind again does not produce a second {@code OVERDUE} row and the shop is not told to ring twice.
 *
 * <p>Its {@code VARCHAR(120)} matches {@code notification_broadcast.dedupe_key} in shape and length on purpose:
 * INST-4 passes this string straight through to {@code NotificationClient.sendEmail(..., dedupeKey)}, where a
 * UNIQUE constraint of its own already enforces it. A transport plugs in; none of this is redesigned.
 *
 * <h3>⚠ {@code organizationId} is copied from the PLAN</h3>
 * The scanner runs on a {@code @Scheduled} thread where there is no authenticated user, so every
 * {@code findScoped} helper in this service is meaningless there. Read-back is a different matter and stays
 * scoped — see {@code InstallmentReminderService}.
 */
@Data
@Entity
@Table(name = "installment_reminder",
       uniqueConstraints = @UniqueConstraint(name = "uq_installment_reminder_dedupe",
                                             columnNames = { "dedupe_key" }),
       indexes = { @Index(name = "idx_reminder_org_stage", columnList = "organization_id,stage,due_date"),
                   @Index(name = "idx_reminder_plan", columnList = "plan_id") })
public class InstallmentReminder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** A courtesy call before the date. */
    public static final String STAGE_DUE_SOON = "DUE_SOON";
    /** A collection call. */
    public static final String STAGE_OVERDUE = "OVERDUE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "installment_id", nullable = false)
    private Long installmentId;

    @Column(name = "customer_id")
    private Long customerId;

    /** {@link #STAGE_DUE_SOON} or {@link #STAGE_OVERDUE}. */
    @Column(name = "stage", nullable = false, length = 16)
    private String stage;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "dedupe_key", nullable = false, length = 120)
    private String dedupeKey;

    /** When the scanner first saw it — not when it fell due, and not when it was rung. */
    @Column(name = "noticed_at", nullable = false)
    private LocalDateTime noticedAt;

    @Column(name = "acted_at")
    private LocalDateTime actedAt;

    @Column(name = "outcome", length = 32)
    private String outcome;

    @Column(name = "note", length = 255)
    private String note;

    /** Has anyone actually rung this customer? The only question the worklist sorts on. */
    public boolean isActioned() {
        return actedAt != null;
    }

    /**
     * The idempotency key for one installment at one stage.
     *
     * <p>Built from the plan NUMBER rather than the row id so it stays meaningful in the notification
     * service's records, where a business-service primary key means nothing to anyone reading them.
     */
    public static String keyFor(String planNo, Integer seqNo, String stage) {
        return "INST/" + planNo + "/" + seqNo + "/" + stage;
    }
}
