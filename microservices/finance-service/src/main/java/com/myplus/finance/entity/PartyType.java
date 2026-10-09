package com.myplus.finance.entity;

/**
 * Who a payment is with. Phase 1 uses CUSTOMER (AR); VENDOR (AP) and others plug in later without schema change.
 * EX-7a — EMPLOYEE: a member of the business paid back for an approved expense claim (Dr 2300, not 2000 — F6).
 */
public enum PartyType {
    CUSTOMER, VENDOR, STUDENT, DONOR, OTHER, EMPLOYEE
}
