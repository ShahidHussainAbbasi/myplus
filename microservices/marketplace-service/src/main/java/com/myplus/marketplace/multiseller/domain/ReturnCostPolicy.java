package com.myplus.marketplace.multiseller.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * Who bears the cost of a return, by its cause (source §13 table). MaxTheService coordinates every return; it pays
 * only for its own routing errors.
 *
 * <p>The bearer is a ROLE, resolved to an organisation through the parties SNAPSHOTTED on the order line
 * (source §13 "order-time snapshots"), so a later change of custodian or warranty provider never moves an old
 * return's cost onto someone else.
 */
public final class ReturnCostPolicy {

    public enum Reason {
        WRONG_PRODUCT, DAMAGED_BEFORE_HANDOVER, DEFECTIVE, NOT_AS_DESCRIBED, EXPIRED_OR_UNSAFE, CHANGE_OF_MIND,
        DELIVERY_FAILURE, ROUTING_ERROR, DROPSHIP_FAILURE, COD_REFUSAL
    }

    public enum Party { FULFILLER, CUSTODIAN, STOCK_OWNER, SELLER, CUSTOMER, CARRIER, PLATFORM, SUPPLIER, BY_POLICY }

    /** The parties fixed on the order line when it was placed. */
    public record PartySnapshot(Long sellerOrg, Long stockOwnerOrg, Long custodianOrg, Long fulfillerOrg,
            Long carrierOrg, Long platformOrg) {
    }

    private static final Map<Reason, Party> DEFAULT_BEARER = new EnumMap<>(Map.of(
            Reason.WRONG_PRODUCT, Party.FULFILLER,
            Reason.DAMAGED_BEFORE_HANDOVER, Party.CUSTODIAN,
            Reason.DEFECTIVE, Party.STOCK_OWNER,
            Reason.NOT_AS_DESCRIBED, Party.SELLER,
            Reason.EXPIRED_OR_UNSAFE, Party.STOCK_OWNER,
            Reason.CHANGE_OF_MIND, Party.CUSTOMER,
            Reason.DELIVERY_FAILURE, Party.CARRIER,
            Reason.ROUTING_ERROR, Party.PLATFORM,
            Reason.DROPSHIP_FAILURE, Party.SUPPLIER,
            Reason.COD_REFUSAL, Party.BY_POLICY));

    private ReturnCostPolicy() {
    }

    public static Party bearerFor(Reason reason) {
        return DEFAULT_BEARER.get(reason);
    }

    /** Expired or unsafe goods are escalated at once, not queued (source §13). */
    public static boolean requiresUrgentEscalation(Reason reason) {
        return reason == Reason.EXPIRED_OR_UNSAFE;
    }

    /**
     * The organisation that bears the cost, or null when the customer bears it or a policy decides (COD refusal).
     * A supplier dropship failure resolves to the stock owner, which is the supplier for SUPPLIER-sourced lines.
     */
    public static Long bearerOrganization(Reason reason, PartySnapshot p) {
        return switch (bearerFor(reason)) {
            case FULFILLER -> p.fulfillerOrg();
            case CUSTODIAN -> p.custodianOrg();
            case STOCK_OWNER, SUPPLIER -> p.stockOwnerOrg();
            case SELLER -> p.sellerOrg();
            case CARRIER -> p.carrierOrg();
            case PLATFORM -> p.platformOrg();
            case CUSTOMER, BY_POLICY -> null;
        };
    }
}
