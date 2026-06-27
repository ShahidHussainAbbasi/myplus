package com.myplus.business_service.entity;

/**
 * A cash-drawer movement during a shift (POS day-close, slice 39). PAY_IN adds cash (e.g. float top-up);
 * PAY_OUT removes it (e.g. petty expense); DROP removes cash banked to the safe.
 */
public enum MovementType {
    PAY_IN,
    PAY_OUT,
    DROP
}
