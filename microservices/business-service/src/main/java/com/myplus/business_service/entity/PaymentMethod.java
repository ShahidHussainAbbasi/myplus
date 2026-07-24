package com.myplus.business_service.entity;

/**
 * How a sale tender was paid (G5 payments, slice 37). {@code CREDIT} = on account (adds to the customer's due,
 * not counted as paid). {@code REFUND} = money returned to the customer on a sale return (recorded as a negative
 * tender). E-commerce online payments later reuse {@code WALLET}/gateway tenders on the same model.
 */
public enum PaymentMethod {
    CASH,
    CARD,
    CREDIT,
    WALLET,
    BANK_TRANSFER,
    INSURANCE,   // P12 (slice 59): insurer-covered portion of a pharmacy dispense; counts as paid (insurer receivable)
    STORE_CREDIT, // SF-5 Model B: redeemed from the customer's credit balance; counts as paid (real value)
    REFUND
}
