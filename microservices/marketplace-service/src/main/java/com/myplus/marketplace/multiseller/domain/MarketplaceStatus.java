package com.myplus.marketplace.multiseller.domain;

/**
 * The status vocabularies of the source design (§5, §6, §10, §11, §16, §19).
 *
 * <p>Each family is its own type and will be its own column. Source §19 is explicit that an order can be
 * FULFILLED with payment CAPTURED and settlement PENDING_RETURN_WINDOW at the same time — one status column
 * cannot say that, so none is offered.
 */
public final class MarketplaceStatus {

    private MarketplaceStatus() {
    }

    /**
     * MKT-0a — a seller's account with the marketplace operator (source §20 "Seller onboarding").
     *
     * <p>Separate from the {@code marketplaceSelling} capability on purpose: TRIAL/DEMO/PRO plans include every
     * capability, so an owner can switch it on alone. MaxTheService vetting its sellers is this status, decided
     * by the platform operator, never by the tenant.
     */
    public enum SellerAccount { PENDING_APPROVAL, APPROVED, REJECTED, SUSPENDED }

    /** Offer and canonical-product approval (§5). */
    public enum Approval { DRAFT, PENDING_REVIEW, APPROVED, REJECTED, SUSPENDED }

    /** Product match review (§6). */
    public enum Match { PENDING_REVIEW, MATCHED, REJECTED, NEEDS_CORRECTION }

    /** Regulated status of a canonical product (§5, Phase 6). */
    public enum Regulated { NONE, RESTRICTED, PRESCRIPTION }

    /** The marketplace (parent) order (§19). */
    public enum Order {
        DRAFT, SUBMITTED, PAYMENT_PENDING, PAYMENT_AUTHORIZED, CONFIRMED, PARTIALLY_FULFILLED, FULFILLED,
        CANCEL_REQUESTED, CANCELLED, RETURN_REQUESTED, RETURNED, COMPLETED
    }

    /**
     * The seller (child) order's ACCEPTANCE phase (§19 UNASSIGNED…EXPIRED, plus the hand-over).
     *
     * <p>Picking, packing, shipping and delivery are deliberately not repeated here: once accepted, the seller's
     * store order runs the existing {@code FulfilmentStatus} lifecycle (O2/O5b), and duplicating those states
     * would create a second copy of the rules that can drift — the defect O4 removed.
     */
    public enum SellerOrder { UNASSIGNED, OFFERED, ACCEPTED, REJECTED, EXPIRED, HANDED_OVER, FAILED, CANCELLED }

    /** Payment (§19). */
    public enum Payment { UNPAID, AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, FAILED, CHARGEBACK }

    /** Settlement of an order line / ledger entry (§16, §19). */
    public enum Settlement {
        NOT_ELIGIBLE, PENDING, PENDING_RETURN_WINDOW, ELIGIBLE, ON_HOLD, APPROVED, PROCESSING, PAID, REVERSED,
        DISPUTED
    }

    /** When an amount becomes payable (§15). */
    public enum SettlementTrigger { DELIVERED, DELIVERED_PLUS_RETURN_WINDOW, PICKUP_COMPLETED, MANUAL_APPROVAL }

    /** A marketplace stock hold (§10). */
    public enum Reservation { HELD, ALLOCATED, CONSUMED, RELEASED, EXPIRED }

    /** Outcome of a shortage (§11). */
    public enum ShortageResult {
        REASSIGNED, PARTIALLY_FULFILLED, SUBSTITUTION_REQUESTED, LINE_CANCELLED, ORDER_CANCELLED, REFUND_PENDING
    }

    /** Settlement ledger entry types (§16). */
    public enum LedgerEntryType {
        SALE, COMMISSION, DELIVERY_FEE, PROCESSING_FEE, TAX, RESERVE, RESERVE_RELEASE, REFUND, ADJUSTMENT, PAYOUT,
        REVERSAL,
        /** MKT-1g — cash on delivery: the seller's rider already holds the customer's money (R-MKT-2). */
        COLLECTED_BY_SELLER
    }
}
