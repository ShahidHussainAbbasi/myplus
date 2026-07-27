package com.myplus.business_service.service;

/**
 * A user-facing business-rule rejection (e.g. "This bill is already voided", "Cannot return more than was purchased").
 * These are EXPECTED conditions, not system faults — controllers catch this to return a clean FAILED with the message
 * and log it at WARN (no stack trace), instead of an alarming ERROR. Distinct from {@link PeriodClosedException},
 * which is its own rule. Its message is safe to show the user verbatim.
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) { super(message); }
}
