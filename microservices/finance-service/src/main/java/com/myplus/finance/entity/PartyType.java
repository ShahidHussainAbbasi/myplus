package com.myplus.finance.entity;

/** Who a payment is with. Phase 1 uses CUSTOMER (AR); VENDOR (AP) and others plug in later without schema change. */
public enum PartyType {
    CUSTOMER, VENDOR, STUDENT, DONOR, OTHER
}
