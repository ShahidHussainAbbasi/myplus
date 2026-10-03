package com.myplus.marketplace.multiseller.domain;

/**
 * Who owns, holds and ships the stock behind an offer (source §1, §4).
 *
 * <p>These are fulfilment and ownership STRATEGIES inside one marketplace, not four marketplaces. Each carries the
 * programme phase that enables it, so the Phase 1 guard reads the fact from the value instead of a list kept
 * somewhere else.
 */
public enum StockSourceType {
    /** Owner = custodian = fulfiller = the merchant. */
    MERCHANT(1),
    /** Owner = MaxTheService; custodian and fulfiller = the platform warehouse. */
    PLATFORM(3),
    /** Owner = custodian = the supplier; ships itself or through its carrier. */
    SUPPLIER(4),
    /** Owner = the consignor; custodian = the consignee, which sells on the owner's behalf. */
    CONSIGNMENT(5);

    private final int launchPhase;

    StockSourceType(int launchPhase) {
        this.launchPhase = launchPhase;
    }

    /** The MKT phase in which this source becomes sellable. */
    public int launchPhase() {
        return launchPhase;
    }
}
