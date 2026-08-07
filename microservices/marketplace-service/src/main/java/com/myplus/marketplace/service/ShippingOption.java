package com.myplus.marketplace.service;

import java.math.BigDecimal;

/**
 * Server-priced shipping methods (slice 69, E5). Fees are decided server-side so the client can't set them.
 *
 * <h3>OMS O3 — the fee moved out of here</h3>
 * This enum used to carry literal fees ({@code STANDARD 5.00}, {@code EXPRESS 15.00}), which meant every store
 * on a multi-tenant platform charged the same delivery and a shop that delivers free had no way to say so.
 * What a method COSTS is per-tenant policy and now lives in {@link ShippingPolicy}, read from
 * {@code order.shipping.*}.
 *
 * <p>What stays is what is structurally true of the method regardless of tenant: collection needs no delivery
 * address. That is not configuration — it is what the word means.
 *
 * <p>Boundary unchanged: carrier configuration, rates by weight/zone and tracking are O5.
 */
public enum ShippingOption {
    PICKUP(false),     // collect in store — no delivery address required
    STANDARD(true),
    EXPRESS(true);

    private final boolean requiresAddress;

    ShippingOption(boolean requiresAddress) {
        this.requiresAddress = requiresAddress;
    }

    public boolean requiresAddress() { return requiresAddress; }

    /** Parse a client-supplied method name; null/blank/unknown defaults to STANDARD. */
    public static ShippingOption from(String name) {
        if (name == null || name.isBlank()) return STANDARD;
        try { return ShippingOption.valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return STANDARD; }
    }
}
