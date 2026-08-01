package com.myplus.business_service.entity.enums;

/**
 * How the shop classifies a customer — and, derived from that, which commercial channel applies.
 *
 * <p><b>Why the channel is derived rather than stored.</b> B2B/B2C is not an extra thing a shopkeeper
 * should have to set: a wholesaler <em>is</em> a trade account, a walk-in <em>is</em> a retail sale. A
 * second column would let the two disagree ("WHOLESALE but B2C") with nothing to say which wins. The
 * channel therefore rides on the constant, so there is exactly one field to set and one source of truth.
 *
 * <p><b>What the channel drives</b> (progressively, per the B2B rollout — none of it in this slice):
 * price source (catalog vs contract/tier), credit limit + terms, PO reference, and whether the document
 * is a receipt or an invoice. Everything degrades to today's behaviour for a B2C customer, which is why
 * every existing row back-fills to {@link #WALK_IN}.
 *
 * <p>Adding a value later (GOVT, NGO, EXPORT…) is a code change only — the column is VARCHAR, not a MySQL
 * native enum, precisely so it never needs an {@code ALTER … MODIFY}.
 *
 * <p>See {@code microservices/docs/b2b-b2c-rollout-plan.md} §3b for why this lives on the customer and
 * not on the JWT or the user account.
 */
public enum CustomerType {

    /** Counter customer, pays now. The default for every pre-existing customer. */
    WALK_IN(Channel.B2C),

    /** Buys to resell — a trade account. */
    RETAILER(Channel.B2B),

    /** Buys in bulk to resell — a trade account. */
    WHOLESALE(Channel.B2B),

    /**
     * A retail customer on preferential terms — a loyalty tier, not a trade account.
     * Deliberately B2C: a VIP walk-in gets a better price, not a credit account and an invoice.
     * A trade customer who is also a VIP is modelled as RETAILER/WHOLESALE with a contract price.
     */
    VIP(Channel.B2C);

    /** The commercial model a customer is served under. */
    public enum Channel { B2C, B2B }

    private final Channel channel;

    CustomerType(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    /** True for trade accounts — the customers that get contract pricing, credit terms and invoices. */
    public boolean isB2B() {
        return channel == Channel.B2B;
    }

    /**
     * Null-safe: a customer whose type was never set reads as {@link #WALK_IN} — today's behaviour —
     * never as "unknown". Covers pre-migration rows and any older client that omits the field.
     */
    public static CustomerType orDefault(CustomerType type) {
        return type == null ? WALK_IN : type;
    }

    /** Null-safe channel for the same reason. */
    public static Channel channelOf(CustomerType type) {
        return orDefault(type).channel();
    }
}
