package com.myplus.marketplace.entity;

/** Order fulfilment lifecycle (e-commerce E1, slice 46). */
public enum FulfilmentStatus {
    NEW,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,   // shopper asked to return a delivered order (slice 71)
    RETURNED            // back-office processed: stock back + refund (slice 71)
}
