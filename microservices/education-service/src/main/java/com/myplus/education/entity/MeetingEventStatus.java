package com.myplus.education.entity;

/**
 * Slice edu-3.4 — a parents' evening is either taking bookings or it is not.
 *
 * <p>Two states and no workflow, for the reason 2.5 and 3.5 both recorded: this domain does not want
 * approval chains, and a state nobody advances is a feature nobody uses.
 */
public enum MeetingEventStatus {
    /** Families can book. */
    OPEN,
    /** Booking is finished. Existing bookings stand — closing an evening is not cancelling it. */
    CLOSED
}
