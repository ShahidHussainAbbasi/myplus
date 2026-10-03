package com.myplus.marketplace.multiseller.domain;

/** The customer's choice of order for a product's offers (source §7, §18). */
public enum OfferSort {
    LOWEST_PRICE, NEAREST, FASTEST, PROMOTION, QUALITY, WARRANTY, RETURN_POLICY;

    /** Unknown or blank → null, so the caller falls back to the operator's default instead of failing a browse. */
    public static OfferSort parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
