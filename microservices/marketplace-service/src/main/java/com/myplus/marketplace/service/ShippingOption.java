package com.myplus.marketplace.service;

import java.math.BigDecimal;

/**
 * Server-priced shipping methods (slice 69, E5). Fees are decided server-side so the client can't set them. Boundary:
 * per-store carrier configuration, rates by weight/zone, and tracking are E9 — this is a fixed-fee starter set.
 */
public enum ShippingOption {
    PICKUP(new BigDecimal("0.00"), false),     // collect in store — no delivery address required
    STANDARD(new BigDecimal("5.00"), true),
    EXPRESS(new BigDecimal("15.00"), true);

    private final BigDecimal fee;
    private final boolean requiresAddress;

    ShippingOption(BigDecimal fee, boolean requiresAddress) {
        this.fee = fee;
        this.requiresAddress = requiresAddress;
    }

    public BigDecimal fee() { return fee; }
    public boolean requiresAddress() { return requiresAddress; }

    /** Parse a client-supplied method name; null/blank/unknown defaults to STANDARD. */
    public static ShippingOption from(String name) {
        if (name == null || name.isBlank()) return STANDARD;
        try { return ShippingOption.valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return STANDARD; }
    }
}
