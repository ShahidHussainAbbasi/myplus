package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to reserve stock for a pending sale (step 1 of the sell↔stock saga, slice 33).
 *
 * <p>{@code idempotencyKey} is caller-generated and makes retries safe: inventory-service returns the same
 * reservation for a repeated key instead of double-holding. FEFO (first-expiry-first-out) picking is the
 * default for batch/expiry-tracked items.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockReservationRequest {
    private String idempotencyKey;
    private List<StockReservationLine> lines;

    /**
     * O7 D1c — WHAT KIND of promise this hold is, which decides how long it lives.
     *
     * <h3>Why one duration cannot serve both</h3>
     * The existing TTL is documented as <i>"long enough for a slow checkout, short enough that a leak
     * self-heals within the hour"</i> — 30 minutes. That is right for a till. It is wrong for a confirmed
     * distribution order, where the admin confirms this afternoon and the van goes out tomorrow: the hold
     * would be swept overnight, silently and exactly as designed, and the order would reach dispatch with
     * nothing reserved. The feature would look implemented and do nothing on any order that waited.
     *
     * <p>Stretching the single TTL to cover orders would leave an abandoned checkout holding stock for days —
     * the same defect from the other end. So the CALLER says which kind of promise it is making, because the
     * caller is the only party that knows.
     *
     * <p>Null means {@link HoldKind#CHECKOUT}: every existing caller keeps its current behaviour untouched.
     */
    private HoldKind holdKind;

    /** The two shapes of promise a hold can be. */
    public enum HoldKind {
        /** A sale in flight. Minutes — the operator is at the screen. */
        CHECKOUT,
        /** A confirmed order awaiting dispatch. Days — the goods are promised to a named customer. */
        ORDER
    }

    /** Back-compatible: an unqualified hold is a CHECKOUT hold, which is what every pre-D1c caller means. */
    public StockReservationRequest(String idempotencyKey, List<StockReservationLine> lines) {
        this(idempotencyKey, lines, HoldKind.CHECKOUT);
    }
}
