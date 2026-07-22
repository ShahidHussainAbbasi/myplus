package com.myplus.business_service.service;

/** Thrown when a sale/purchase/payment/edit/void falls in a closed (locked) period. Surfaced to the user as-is. */
public class PeriodClosedException extends RuntimeException {
    public PeriodClosedException(String message) { super(message); }
}
