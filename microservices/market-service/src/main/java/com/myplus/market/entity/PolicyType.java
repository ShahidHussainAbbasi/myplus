package com.myplus.market.entity;

/**
 * The Phase 0 documents from the source design (§20), each versioned so "who agreed to what, when" is answerable.
 *
 * <p>The TEXT of each is the operator's legal work; the system versions it and records acceptance. Stored as
 * VARCHAR(32) by name — never a MySQL ENUM (STANDARDS §0: two services crash-looped on ENUM under validate).
 */
public enum PolicyType {
    SELLER_AGREEMENT(true),
    DATA_SHARING(true),
    COMMISSION(true),
    RETURNS_REFUNDS(true),
    CUSTOMER_TERMS(false),
    COD(false),
    WARRANTY(false),
    COMPLAINTS(false),
    PRODUCT_APPROVAL(false);

    private final boolean sellerMustAccept;

    PolicyType(boolean sellerMustAccept) { this.sellerMustAccept = sellerMustAccept; }

    /** True for the documents a seller accepts when applying: they bind the seller, not the customer. */
    public boolean sellerMustAccept() { return sellerMustAccept; }

    public static PolicyType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
