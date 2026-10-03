package com.myplus.marketplace.multiseller.domain;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Payment;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Reservation;
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
            .allow(Settlement.ON_HOLD, Settlement.ELIGIBLE, Settlement.DISPUTED, Settlement.REVERSED)
            .allow(Settlement.DISPUTED, Settlement.ELIGIBLE, Settlement.REVERSED)
            .allow(Settlement.APPROVED, Settlement.PROCESSING, Settlement.ON_HOLD)
            .allow(Settlement.PROCESSING, Settlement.PAID, Settlement.ON_HOLD)
            .allow(Settlement.PAID, Settlement.REVERSED)
            .build();

    public static final StateMachine<Reservation> RESERVATION = StateMachine.of(Reservation.class)
            .allow(Reservation.HELD, Reservation.ALLOCATED, Reservation.RELEASED, Reservation.EXPIRED)
            .allow(Reservation.ALLOCATED, Reservation.CONSUMED, Reservation.RELEASED)
            .build();
}
