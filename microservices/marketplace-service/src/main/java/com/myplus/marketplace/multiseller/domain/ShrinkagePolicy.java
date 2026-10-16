package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MKT-5d — shrinkage: its kinds (R12.1), who answers for it by default (R12.3, source §12 table) and, for consigned
 * stock, by the agreement's terms (R12.2). Slice doc {@code docs/slices/mkt-5d-counts-shrinkage.md}.
 *
 * <p>Nothing here moves money: a shrinkage claim is paid only once the operator approves it (R12.4).
 */
public final class ShrinkagePolicy {

    /** R12.1 — every kind of shrinkage the source names. */
    public enum Kind {
        THEFT, DAMAGE, BREAKAGE, SPOILAGE, EXPIRY, MISCOUNT, CONSUMPTION, SHORT_SHIPMENT, DELIVERY_LOSS
    }

    /** The situations of the source's default table (R12.3). */
    public enum Situation {
        MERCHANT_STOCK_AT_MERCHANT, PLATFORM_STOCK_AT_PLATFORM, SUPPLIER_STOCK_AT_SUPPLIER, CONSIGNMENT_AT_CUSTODIAN,
        SUPPLIER_SHORT_SHIPMENT, CARRIER_LOSS_AFTER_HANDOVER, EXPIRY_POOR_ROTATION, EXPIRY_SHORT_SHELF_LIFE
    }

    /** Who answers for it. AGREEMENT: the agreement decides (the source's "or agreed split"). */
    public enum Party { MERCHANT, PLATFORM, SUPPLIER, CUSTODIAN, CARRIER, AGREEMENT }

    /** The two sides of a consignment agreement, as its terms name them. */
    public static final String CONSIGNOR = "CONSIGNOR";
    public static final String CONSIGNEE = "CONSIGNEE";

    private ShrinkagePolicy() {
    }

    /** R12.3 — the source's default responsibility, situation by situation. */
    public static Party byDefault(Situation s) {
        return switch (s) {
            case MERCHANT_STOCK_AT_MERCHANT -> Party.MERCHANT;
            case PLATFORM_STOCK_AT_PLATFORM -> Party.PLATFORM;
            case SUPPLIER_STOCK_AT_SUPPLIER, SUPPLIER_SHORT_SHIPMENT -> Party.SUPPLIER;
            case CONSIGNMENT_AT_CUSTODIAN, EXPIRY_POOR_ROTATION -> Party.CUSTODIAN;
            case CARRIER_LOSS_AFTER_HANDOVER -> Party.CARRIER;
            case EXPIRY_SHORT_SHELF_LIFE -> Party.AGREEMENT;
        };
    }

    /**
     * Consigned stock at the MaxTheService warehouse: which side of the agreement answers for this kind. The custodian
     * (CONSIGNEE) by default (R12.3 "custodian, unless agreement says otherwise"); damage and breakage follow the
     * agreement's damage term, expiry and spoilage its expiry term; a short shipment is the shipper's, the owner's.
     * Delivery loss is the contracted party's, and MaxTheService contracts the carrier.
     */
    public static String forConsignment(Kind k, String damageBearer, String expiryBearer) {
        return switch (k) {
            case DAMAGE, BREAKAGE -> side(damageBearer);
            case EXPIRY, SPOILAGE -> side(expiryBearer);
            case SHORT_SHIPMENT -> CONSIGNOR;
            case THEFT, MISCOUNT, CONSUMPTION, DELIVERY_LOSS -> CONSIGNEE;
        };
    }

    private static String side(String term) {
        return CONSIGNOR.equals(term) ? CONSIGNOR : CONSIGNEE;
    }

    /**
     * R12.2 — how many of the {@code expected} units may go missing at a count before the custodian answers for them:
     * the agreement's tolerance, rounded down to whole units (1% of 5 phones is none).
     */
    public static int tolerated(int expected, BigDecimal tolerancePct) {
        if (expected <= 0 || tolerancePct == null || tolerancePct.signum() <= 0) return 0;
        return BigDecimal.valueOf(expected).multiply(tolerancePct)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).intValue();
    }

    /** A kind from the screen's value, or null. */
    public static Kind kind(String v) {
        if (v == null) return null;
        try {
            return Kind.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
