package com.myplus.clinical.service;

/**
 * HC-1d — a prescription refused for a reason the screen must tell apart, carried as a stable code (never parsed from
 * the sentence). ⚠ {@link #NO_LINKED_PHARMACY} is a CONTRACT with pharma-service's {@code LinkedRxService.NO_LINK} and
 * with the monolith's message key {@code ui.js.clinic.rxNoLink}: rename one, rename all three.
 */
public class RxRefusedException extends RuntimeException {

    /** A Hospital / Clinic with no ACTIVE link to a pharmacy: its doctors cannot send prescriptions. */
    public static final String NO_LINKED_PHARMACY = "RX_NO_LINKED_PHARMACY";

    private final String code;

    public RxRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
