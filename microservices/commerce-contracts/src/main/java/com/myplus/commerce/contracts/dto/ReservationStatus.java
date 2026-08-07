package com.myplus.commerce.contracts.dto;

/**
 * Outcome of a stock reservation in the sell↔stock saga (slice 33).
 */
public enum ReservationStatus {
    /** All lines reserved (FEFO picks returned); stock held but not yet decremented. */
    RESERVED,
    /** One or more lines could not be fully satisfied; nothing is held. */
    OUT_OF_STOCK,
    /** A previously-held reservation has been confirmed (stock decremented). */
    CONFIRMED,
    /** A reservation has been released/compensated (held stock returned). */
    RELEASED,
    /**
     * The hold outlived its deadline and the sweeper returned the stock (OMS O5a).
     *
     * <p>Deliberately distinct from {@link #RELEASED}: "the caller compensated" and "nobody ever came back for
     * this" are different facts, and only the second one says something went wrong upstream. Collapsing them
     * would hide the very leaks this status exists to surface.
     */
    EXPIRED
}
