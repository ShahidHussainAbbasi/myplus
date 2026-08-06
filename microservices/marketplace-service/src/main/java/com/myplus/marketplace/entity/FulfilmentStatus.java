package com.myplus.marketplace.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order fulfilment lifecycle (e-commerce E1, slice 46), with the legal moves between states (OMS O2).
 *
 * <h3>Why a whitelist</h3>
 * {@link #ALLOWED} lists what MAY happen; everything else is refused. Before O2 {@code updateStatus} simply did
 * {@code valueOf(status)} and assigned it, so an order could go CANCELLED → SHIPPED — goods dispatched against an
 * order whose money and stock had already been reversed. The failure mode of a missed illegal transition is
 * shipping something that was cancelled, so the safe default must be "no".
 *
 * <h3>SHIPPED → CANCELLED is deliberately NOT legal</h3>
 * Cancelling triggers the O1 void, which returns stock to inventory. Goods already in transit are not back on
 * the shelf, so allowing it would inflate on-hand by whatever is on the van. A failed delivery goes
 * SHIPPED → DELIVERED → RETURNED, which puts the stock back only when it physically arrives.
 */
public enum FulfilmentStatus {
    NEW,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,   // shopper asked to return a delivered order (slice 71)
    RETURNED;           // back-office processed: stock back + refund (slice 71)

    /** The legal moves. A state absent from a value's set can never be reached from it. */
    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> ALLOWED = Map.of(
            NEW,              EnumSet.of(PACKED, CANCELLED),
            PACKED,           EnumSet.of(SHIPPED, CANCELLED),
            SHIPPED,          EnumSet.of(DELIVERED),
            DELIVERED,        EnumSet.of(RETURN_REQUESTED, RETURNED),
            RETURN_REQUESTED, EnumSet.of(RETURNED),
            CANCELLED,        EnumSet.noneOf(FulfilmentStatus.class),
            RETURNED,         EnumSet.noneOf(FulfilmentStatus.class));

    /** May an order in THIS state move to {@code target}? */
    public boolean canMoveTo(FulfilmentStatus target) {
        return target != null && ALLOWED.getOrDefault(this, EnumSet.noneOf(FulfilmentStatus.class)).contains(target);
    }

    /** Terminal: the order is finished, one way or the other. */
    public boolean isTerminal() {
        return this == CANCELLED || this == RETURNED;
    }

    /**
     * Does moving here reverse money and stock? Those two are gated harder than forward fulfilment work — a
     * packer may ship, but reversing a sale is the same class of action as {@code /refund} and {@code /return}.
     */
    public boolean isReversal() {
        return this == CANCELLED || this == RETURNED;
    }
}
