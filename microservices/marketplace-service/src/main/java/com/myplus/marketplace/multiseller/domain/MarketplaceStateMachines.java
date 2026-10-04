package com.myplus.marketplace.multiseller.domain;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Match;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Payment;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Reservation;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Settlement;

/**
 * The lifecycles of source §19, one whitelist each.
 *
 * <p>Design decisions encoded here (design §5.4):
 * <ul>
 *   <li><b>No CANCELLED after dispatch.</b> The marketplace order may cancel from CONFIRMED only while the seller
 *       order has not been handed over; the service checks the child before asking this machine. Same reason as
 *       O2's {@code SHIPPED → CANCELLED}: goods on a van are not back on the shelf.</li>
 *   <li><b>A rejected or expired seller order is not terminal</b> — Phase 2 re-offers it to another seller
 *       (source §11 REASSIGNED). Phase 1 cancels it instead; both moves are legal so Phase 2 adds no edge.</li>
 *   <li><b>PAID settlement can only be REVERSED</b>, never edited back to ELIGIBLE: a paid amount is corrected by a
 *       new ledger entry (source §15 "immutable settlement ledger").</li>
 * </ul>
 */
public final class MarketplaceStateMachines {

    private MarketplaceStateMachines() {
    }

    public static final StateMachine<Order> ORDER = StateMachine.of(Order.class)
            .allow(Order.DRAFT, Order.SUBMITTED, Order.CANCELLED)
            .allow(Order.SUBMITTED, Order.PAYMENT_PENDING, Order.CONFIRMED, Order.CANCELLED)
            .allow(Order.PAYMENT_PENDING, Order.PAYMENT_AUTHORIZED, Order.CONFIRMED, Order.CANCELLED)
            .allow(Order.PAYMENT_AUTHORIZED, Order.CONFIRMED, Order.CANCELLED)
            .allow(Order.CONFIRMED, Order.PARTIALLY_FULFILLED, Order.FULFILLED, Order.CANCEL_REQUESTED,
                    Order.CANCELLED)
            .allow(Order.PARTIALLY_FULFILLED, Order.FULFILLED, Order.CANCEL_REQUESTED)
            .allow(Order.CANCEL_REQUESTED, Order.CANCELLED, Order.CONFIRMED)
            .allow(Order.FULFILLED, Order.RETURN_REQUESTED, Order.COMPLETED)
            .allow(Order.RETURN_REQUESTED, Order.RETURNED, Order.FULFILLED)
            .allow(Order.RETURNED, Order.COMPLETED)
            .build();

    public static final StateMachine<SellerOrder> SELLER_ORDER = StateMachine.of(SellerOrder.class)
            .allow(SellerOrder.UNASSIGNED, SellerOrder.OFFERED, SellerOrder.CANCELLED)
            .allow(SellerOrder.OFFERED, SellerOrder.ACCEPTED, SellerOrder.REJECTED, SellerOrder.EXPIRED,
                    SellerOrder.CANCELLED)
            .allow(SellerOrder.ACCEPTED, SellerOrder.HANDED_OVER, SellerOrder.FAILED, SellerOrder.CANCELLED)
            .allow(SellerOrder.HANDED_OVER, SellerOrder.FAILED)
            .allow(SellerOrder.REJECTED, SellerOrder.UNASSIGNED, SellerOrder.CANCELLED)
            .allow(SellerOrder.EXPIRED, SellerOrder.UNASSIGNED, SellerOrder.CANCELLED)
            .allow(SellerOrder.FAILED, SellerOrder.UNASSIGNED, SellerOrder.CANCELLED)
            .build();

    public static final StateMachine<Payment> PAYMENT = StateMachine.of(Payment.class)
            .allow(Payment.UNPAID, Payment.AUTHORIZED, Payment.CAPTURED, Payment.FAILED)
            .allow(Payment.AUTHORIZED, Payment.CAPTURED, Payment.FAILED, Payment.REFUNDED)
            .allow(Payment.CAPTURED, Payment.PARTIALLY_REFUNDED, Payment.REFUNDED, Payment.CHARGEBACK)
            .allow(Payment.PARTIALLY_REFUNDED, Payment.PARTIALLY_REFUNDED, Payment.REFUNDED, Payment.CHARGEBACK)
            .allow(Payment.FAILED, Payment.AUTHORIZED, Payment.CAPTURED)
            .build();

    public static final StateMachine<Settlement> SETTLEMENT = StateMachine.of(Settlement.class)
            .allow(Settlement.NOT_ELIGIBLE, Settlement.PENDING, Settlement.PENDING_RETURN_WINDOW, Settlement.REVERSED)
            .allow(Settlement.PENDING, Settlement.PENDING_RETURN_WINDOW, Settlement.ELIGIBLE, Settlement.ON_HOLD,
                    Settlement.REVERSED)
            .allow(Settlement.PENDING_RETURN_WINDOW, Settlement.ELIGIBLE, Settlement.ON_HOLD, Settlement.REVERSED)
            .allow(Settlement.ELIGIBLE, Settlement.APPROVED, Settlement.ON_HOLD, Settlement.DISPUTED)
            // MKT-1g: a hold lifted while the return window is still open goes back to waiting for the window
            .allow(Settlement.ON_HOLD, Settlement.ELIGIBLE, Settlement.PENDING_RETURN_WINDOW, Settlement.DISPUTED,
                    Settlement.REVERSED)
            .allow(Settlement.DISPUTED, Settlement.ELIGIBLE, Settlement.REVERSED)
            .allow(Settlement.APPROVED, Settlement.PROCESSING, Settlement.ON_HOLD)
            .allow(Settlement.PROCESSING, Settlement.PAID, Settlement.ON_HOLD)
            .allow(Settlement.PAID, Settlement.REVERSED)
            .build();

    /**
     * MKT-0a — the operator's decision on a seller. REJECTED is not terminal: a seller who fixes what was wrong
     * re-accepts the agreements, which re-applies (PENDING_APPROVAL). A SUSPENDED seller is reinstated by the
     * operator, never by re-applying, so a suspension cannot be lifted by the tenant it was imposed on.
     */
    public static final StateMachine<SellerAccount> SELLER_ACCOUNT = StateMachine.of(SellerAccount.class)
            .allow(SellerAccount.PENDING_APPROVAL, SellerAccount.APPROVED, SellerAccount.REJECTED)
            .allow(SellerAccount.APPROVED, SellerAccount.SUSPENDED)
            .allow(SellerAccount.SUSPENDED, SellerAccount.APPROVED)
            .allow(SellerAccount.REJECTED, SellerAccount.PENDING_APPROVAL)
            .build();

    /**
     * MKT-1b — a seller's product proposal under review (source §6). An operator can correct a bad match after
     * the fact (MATCHED → NEEDS_CORRECTION | REJECTED, MKT-R6.5); a seller re-proposes from NEEDS_CORRECTION or
     * REJECTED, which puts it back in the queue. Nothing reaches MATCHED except through a person.
     */
    public static final StateMachine<Match> MATCH = StateMachine.of(Match.class)
            .allow(Match.PENDING_REVIEW, Match.MATCHED, Match.REJECTED, Match.NEEDS_CORRECTION)
            .allow(Match.MATCHED, Match.NEEDS_CORRECTION, Match.REJECTED)
            .allow(Match.NEEDS_CORRECTION, Match.PENDING_REVIEW)
            .allow(Match.REJECTED, Match.PENDING_REVIEW)
            .build();

    /**
     * MKT-1c — an offer's approval (source §5). The seller drafts and submits; only the operator approves, rejects
     * or suspends. A rejected offer is resubmitted after the seller changes it. Pausing is NOT here: it is the
     * seller's own on/off on an approved offer, a separate fact from MaxTheService's decision.
     */
    public static final StateMachine<Approval> OFFER = StateMachine.of(Approval.class)
            .allow(Approval.DRAFT, Approval.PENDING_REVIEW)
            .allow(Approval.PENDING_REVIEW, Approval.APPROVED, Approval.REJECTED)
            .allow(Approval.REJECTED, Approval.PENDING_REVIEW)
            .allow(Approval.APPROVED, Approval.SUSPENDED)
            .allow(Approval.SUSPENDED, Approval.APPROVED)
            .build();

    public static final StateMachine<Reservation> RESERVATION = StateMachine.of(Reservation.class)
            .allow(Reservation.HELD, Reservation.ALLOCATED, Reservation.RELEASED, Reservation.EXPIRED)
            .allow(Reservation.ALLOCATED, Reservation.CONSUMED, Reservation.RELEASED)
            .build();
}
