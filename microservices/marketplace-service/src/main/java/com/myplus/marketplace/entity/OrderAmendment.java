package com.myplus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D1 — one act of amendment on a booked order (V18).
 *
 * <h3>Why this exists</h3>
 * D-2 settled that <b>both</b> the order booker and the warehouse admin may revise a rejected order, and D-3
 * that the admin may change <b>prices</b>. Either alone would be fine; together they make an audit trail
 * mandatory rather than nice to have — with two people editing one document, <i>"who dropped the price on this
 * order?"</i> has no answer anywhere else, and that is precisely the question a distributor asks when the
 * margin report looks wrong.
 *
 * <h3>One row per amendment, not per field</h3>
 * An amendment is a single act of judgement — cut a line, drop the price, move the date — and splitting it
 * across a row per field would lose the fact that it was one decision, taken for one reason. The field-level
 * before/after lives in {@link #changes} as JSON.
 *
 * <h3>The name is stamped, not resolved</h3>
 * {@code userName} is written at the time of the amendment rather than looked up when the trail is read. An
 * audit record must still be readable after the person has left the company and their user row is gone — the
 * same rule {@code CustomerHistory.bookedByName} follows.
 */
@Entity
@Table(name = "order_amendment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * No FK to {@code orders}, deliberately. This is an audit record: it must outlive its order, and a
     * constraint that could block a write is the wrong trade for a trail whose entire purpose is to still be
     * there afterwards.
     */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name")
    private String userName;

    /** A human-readable one-liner for the order's timeline, e.g. "2 lines changed, price changed". */
    @Column(name = "summary", length = 500)
    private String summary;

    /** JSON: {@code [{"field":"…","from":"…","to":"…"}]}. Held as text — it is read by people, not queried. */
    @Column(name = "changes", columnDefinition = "TEXT")
    private String changes;

    /** Why the amendment was made. Free text; required by policy for a price change (D-3). */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;
}
