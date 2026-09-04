package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * ONB-3 — a memento of what a business-type change destroyed.
 *
 * <h3>Not an audit record</h3>
 * An audit answers <i>what happened</i>. This answers <i>what to put back</i>: {@link #previousOverrides} is
 * the snapshot an undo would restore. The two overlap and are not the same thing, which is why this exists
 * before the audit slice rather than waiting for it.
 *
 * <h3>The one irreversible part of a shape change, made reversible</h3>
 * Changing a business type re-applies the new shape's preset, clearing every {@code org.cap.*} override the
 * owner had set. Switching back restores capabilities but applies the OTHER preset — not the switches the
 * owner personally chose. Without this row those are gone silently, and nobody can say what was lost.
 */
@Entity
@Table(name = "org_shape_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgShapeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    /** The operator or owner who made the change. Null only for a change made by seeding. */
    @Column(name = "changed_by")
    private Long changedBy;

    /** NULL when the tenant had never chosen a type — the state 37 organizations are in. */
    @Column(name = "previous_shape", length = 40)
    private String previousShape;

    @Column(name = "new_shape", nullable = false, length = 40)
    private String newShape;

    /**
     * JSON snapshot of the {@code org.cap.*} rows cleared by this change: {@code {"org.cap.x":"true", …}}.
     *
     * <p>A snapshot is the one shape JSON is genuinely right for — never queried by key, never joined, never
     * aggregated. Empty object when the tenant had no overrides, rather than null, so a reader can tell
     * "nothing was cleared" from "we did not record".
     *
     * <p><b>The column type is pinned, not inferred.</b> {@code @Lob} maps a String to CLOB, which Hibernate
     * validates against MySQL as {@code tinytext} — so the service refused to boot against the {@code TEXT}
     * column V9 declares ("found [text], but expecting [tinytext]"). A snapshot cannot take the bounded
     * VARCHAR that education-service standardised on, because truncating it destroys the very thing this row
     * exists to preserve; so it names its type, as {@code parked_sale.cart_json} does.
     */
    @Column(name = "previous_overrides", columnDefinition = "TEXT")
    private String previousOverrides;

    @Column(length = 255)
    private String reason;
}
