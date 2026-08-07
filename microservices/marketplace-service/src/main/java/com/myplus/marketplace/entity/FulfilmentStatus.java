package com.myplus.marketplace.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order fulfilment lifecycle (e-commerce E1, slice 46), with the legal moves between states (OMS O2).
 *
 * <h3>Why a whitelist</h3>
 * {@link #ALLOWED} lists what MAY happen; everything else is refused. Before O2 {@code updateStatus} simply did
 * {@code valueOf(status)} and assigned it, so an order could go CANCELLED → SHIPPED — goods dispatched against an
 * order whose money and stock had already been reversed. The failure mode of a missed illegal transition is
 * shipping something that was cancelled, so the safe default must be "no".
 *
 * <h3>SHIPPED → CANCELLED is deliberately NOT legal</h3>
 * Cancelling triggers the O1 void, which returns stock to inventory. Goods already in transit are not back on
 * the shelf, so allowing it would inflate on-hand by whatever is on the van. A failed delivery goes
 * SHIPPED → DELIVERED → RETURNED, which puts the stock back only when it physically arrives.
 */
public enum FulfilmentStatus {
    NEW,
    PACKED,
    /**
     * OMS O5c: accepted, but part of it cannot be filled yet. Nothing has shipped and something is owed.
     * DERIVED from quantities. Still cancellable — nothing has left the building.
     */
    BACKORDERED,
    /** OMS O5b: some lines have gone out, not all. DERIVED from quantities — never set by hand. */
    PARTIALLY_SHIPPED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,   // shopper asked to return a delivered order (slice 71)
    RETURNED;           // back-office processed: stock back + refund (slice 71)

    /**
     * The legal moves. A state absent from a value's set can never be reached from it.
     *
     * <p>OMS O5b split these into two kinds. {@code PARTIALLY_SHIPPED} and {@code SHIPPED} are <b>derived</b>
     * from line quantities when a shipment is recorded, so they are not offered as manual moves — see
     * {@link #isDerived}. Everything else is a decision someone makes, and this map still guards it.
     *
     * <p>{@code PARTIALLY_SHIPPED} has no path to {@code CANCELLED}, for exactly the reason {@code SHIPPED} has
     * none: cancelling triggers the O1 void, which returns stock to inventory, and goods already on a van are
     * not back on the shelf. A part-shipped order that fails goes DELIVERED → RETURNED, which puts stock back
     * when it physically arrives.
     */
    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> ALLOWED = Map.of(
            NEW,               EnumSet.of(PACKED, CANCELLED),
            PACKED,            EnumSet.of(CANCELLED),
            // O5c: still cancellable. The O5b prohibition is about goods on a van; a backordered order has
            // shipped nothing, so there is nothing in transit to be inconsistent about.
            BACKORDERED,       EnumSet.of(CANCELLED),
            PARTIALLY_SHIPPED, EnumSet.of(DELIVERED),
            SHIPPED,           EnumSet.of(DELIVERED),
            DELIVERED,         EnumSet.of(RETURN_REQUESTED, RETURNED),
            RETURN_REQUESTED,  EnumSet.of(RETURNED),
            CANCELLED,         EnumSet.noneOf(FulfilmentStatus.class),
            RETURNED,          EnumSet.noneOf(FulfilmentStatus.class));

    /**
     * Is this state reached by recording a SHIPMENT rather than by deciding?
     *
     * <p>You cannot mark an order shipped any more — you ship a parcel and the status follows from what went
     * out. A status settable independently of its shipments is a status that can lie about them, which is the
     * duplication O4 removed from the browser and this keeps out of the API.
     */
    public boolean isDerived() {
        return this == PARTIALLY_SHIPPED || this == SHIPPED || this == BACKORDERED;
    }

    /**
     * The header state implied by an order's line quantities (OMS O5b).
     *
     * @param totalOrdered every line's ordered quantity, summed
     * @param totalShipped every line's shipped quantity, summed
     * @return {@code SHIPPED} when everything has gone, {@code PARTIALLY_SHIPPED} when some has,
     *         {@code null} when nothing has — meaning "leave the current status alone", because an order that
     *         has shipped nothing may legitimately be NEW or PACKED and this cannot tell which.
     */
    public static FulfilmentStatus project(int totalOrdered, int totalShipped) {
        return project(totalOrdered, totalShipped, 0);
    }

    /**
     * The header state implied by the line quantities, including what is owed but not yet invoiced (OMS O5c).
     *
     * <p>The rule that matters: <b>an order is not SHIPPED while anything is still backordered</b>, however much
     * of the invoiced part has gone. Otherwise a shop would mark complete an order it still owes goods on, and
     * the customer waiting for the rest would drop off every report.
     *
     * @param totalBackordered accepted but not yet invoiced; {@code totalOrdered = invoiced + backordered}
     */
    public static FulfilmentStatus project(int totalOrdered, int totalShipped, int totalBackordered) {
        if (totalShipped <= 0) {
            // Nothing has gone out. Owed something ⇒ BACKORDERED; owed nothing ⇒ null, because NEW and PACKED
            // are indistinguishable from quantities alone and are the caller's business.
            return totalBackordered > 0 ? BACKORDERED : null;
        }
        int invoiced = Math.max(0, totalOrdered - totalBackordered);
        if (totalBackordered > 0) return PARTIALLY_SHIPPED;   // some shipped, but the order is not complete
        return totalShipped >= invoiced ? SHIPPED : PARTIALLY_SHIPPED;
    }

    /** May an order in THIS state move to {@code target}? */
    public boolean canMoveTo(FulfilmentStatus target) {
        return target != null && ALLOWED.getOrDefault(this, EnumSet.noneOf(FulfilmentStatus.class)).contains(target);
    }

    /**
     * Where this order may go next — the whitelist, published (OMS O4).
     *
     * <p>The back office used to keep its OWN copy of these rules
     * ({@code NEXT = {NEW:'PACKED', PACKED:'SHIPPED', SHIPPED:'DELIVERED'}} in {@code ecommerce.js}), and the two
     * had already drifted: the screen offered <b>Cancel on a SHIPPED order</b>, which this map forbids, so the
     * operator got a 409 from a button that should never have been drawn. It also had no entry for
     * {@code RETURN_REQUESTED}, leaving a customer's return request in a state the back office could not act on.
     *
     * <p>Publishing the map is what removes the duplication: the client renders one button per entry and holds no
     * opinion of its own. This does not weaken the guard — {@link #canMoveTo} still refuses a hand-crafted
     * request. It stops the UI OFFERING what the server will refuse, the same way O3 put {@code codEnabled} on
     * the checkout quote.
     *
     * <p>Returns an unmodifiable, stable-ordered view; terminal states yield an empty set rather than null, so
     * callers can render without a null check.
     */
    public Set<FulfilmentStatus> allowedTransitions() {
        // EnumSet iterates in declaration order, so the buttons come out in lifecycle order (PACKED before
        // CANCELLED) without the client sorting anything. copyOf on an EnumSet is safe when empty.
        EnumSet<FulfilmentStatus> next =
                EnumSet.copyOf(ALLOWED.getOrDefault(this, EnumSet.noneOf(FulfilmentStatus.class)));
        return java.util.Collections.unmodifiableSet(next);
    }

    /** The same, as strings — what the DTO carries to the browser. */
    public java.util.List<String> allowedTransitionNames() {
        return allowedTransitions().stream().map(Enum::name).toList();
    }

    /** Terminal: the order is finished, one way or the other. */
    public boolean isTerminal() {
        return this == CANCELLED || this == RETURNED;
    }

    /**
     * Does moving here reverse money and stock? Those two are gated harder than forward fulfilment work — a
     * packer may ship, but reversing a sale is the same class of action as {@code /refund} and {@code /return}.
     */
    public boolean isReversal() {
        return this == CANCELLED || this == RETURNED;
    }
}
