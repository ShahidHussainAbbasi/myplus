package com.myplus.marketplace.dto;

import lombok.Data;

/**
 * OMS O7 D1 — one entry in an order's amendment trail, as the back office sees it.
 *
 * <h3>Why this exists rather than returning the entity</h3>
 * §1.5 of the build standards: DTOs at the boundary, never entities. That is not ceremony here — the entity
 * carries {@code organizationId} and the raw row id, which are this service's business and not the browser's,
 * and a JPA entity on a controller means any future lazy relation is serialised outside its transaction.
 *
 * <h3>{@code changes} stays a JSON STRING</h3>
 * Deliberate. It is an audit blob written once and read by a person, and {@link #summary} already carries the
 * readable one-liner. Parsing it into typed objects here would buy a nicer shape for a screen that does not
 * exist yet (D2/D4), at the cost of a parse that can throw while rendering an audit trail — and an audit trail
 * that fails to display is worse than one that displays as text.
 */
@Data
public class OrderAmendmentDTO {

    /** Who made the change — stamped at write, so the trail survives the person leaving. */
    private String userName;

    /** One-liner for the timeline, e.g. {@code "2 changes: quantity, price"}. */
    private String summary;

    /** JSON: {@code [{"field":"…","from":"…","to":"…"}]}. */
    private String changes;

    /** Why it was changed. */
    private String reason;

    private java.time.LocalDateTime at;
}
