package com.myplus.marketplace.entity;

/** Order fulfilment lifecycle (e-commerce E1, slice 46). */
public enum FulfilmentStatus {
    NEW,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
