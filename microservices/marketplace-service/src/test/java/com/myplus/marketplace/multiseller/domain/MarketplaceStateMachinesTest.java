package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Payment;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Reservation;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketplaceStateMachinesTest {

    @Test
    @DisplayName("[MKT-R19.1] the happy path of each machine is legal end to end")
    void happyPaths() {
        walk(MarketplaceStateMachines.ORDER, Order.DRAFT, Order.SUBMITTED, Order.PAYMENT_PENDING,
                Order.PAYMENT_AUTHORIZED, Order.CONFIRMED, Order.FULFILLED, Order.COMPLETED);
        walk(MarketplaceStateMachines.SELLER_ORDER, SellerOrder.UNASSIGNED, SellerOrder.OFFERED,
                SellerOrder.ACCEPTED, SellerOrder.HANDED_OVER);
        walk(MarketplaceStateMachines.PAYMENT, Payment.UNPAID, Payment.AUTHORIZED, Payment.CAPTURED,
                Payment.PARTIALLY_REFUNDED, Payment.REFUNDED);
        walk(MarketplaceStateMachines.SETTLEMENT, Settlement.NOT_ELIGIBLE, Settlement.PENDING_RETURN_WINDOW,
                Settlement.ELIGIBLE, Settlement.APPROVED, Settlement.PROCESSING, Settlement.PAID);
        walk(MarketplaceStateMachines.RESERVATION, Reservation.HELD, Reservation.ALLOCATED, Reservation.CONSUMED);
    }

    @Test
    @DisplayName("[MKT-R19.1] the four machines are independent: FULFILLED + CAPTURED + PENDING_RETURN_WINDOW coexist")
    void independentMachines() {
        // the source's own example — representable only because each family is its own type
        Order o = Order.FULFILLED;
        Payment p = Payment.CAPTURED;
        Settlement s = Settlement.PENDING_RETURN_WINDOW;
        assertThat(MarketplaceStateMachines.ORDER.canTransition(o, Order.COMPLETED)).isTrue();
        assertThat(MarketplaceStateMachines.PAYMENT.canTransition(p, Payment.PARTIALLY_REFUNDED)).isTrue();
        assertThat(MarketplaceStateMachines.SETTLEMENT.canTransition(s, Settlement.ELIGIBLE)).isTrue();
    }

    @Test
    @DisplayName("[MKT-R19.1] [MKT-R10.2] rejected/expired seller order releases and can be re-offered (Phase 2) or cancelled (Phase 1)")
    void rejectedIsNotTerminal() {
        assertThat(MarketplaceStateMachines.SELLER_ORDER.allowedFrom(SellerOrder.EXPIRED))
                .containsExactlyInAnyOrder(SellerOrder.UNASSIGNED, SellerOrder.CANCELLED);
        assertThat(MarketplaceStateMachines.SELLER_ORDER.allowedFrom(SellerOrder.REJECTED))
                .containsExactlyInAnyOrder(SellerOrder.UNASSIGNED, SellerOrder.CANCELLED);
    }

    @Test
    @DisplayName("[MKT-R19.1] an order is never confirmed after it was cancelled, nor completed before fulfilment")
    void illegalMovesRefused() {
        assertThat(MarketplaceStateMachines.ORDER.isTerminal(Order.CANCELLED)).isTrue();
        assertThat(MarketplaceStateMachines.ORDER.canTransition(Order.CONFIRMED, Order.COMPLETED)).isFalse();
        assertThat(MarketplaceStateMachines.ORDER.canTransition(Order.SUBMITTED, Order.FULFILLED)).isFalse();
        assertThatThrownBy(() -> MarketplaceStateMachines.ORDER.transition(Order.CANCELLED, Order.CONFIRMED))
                .isInstanceOf(MarketplaceRuleException.class)
                .hasMessage("This order cannot move from CANCELLED to CONFIRMED.")
                .extracting(e -> ((MarketplaceRuleException) e).code()).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    @DisplayName("[MKT-R15.2] [MKT-R15.6] a PAID settlement is corrected only by REVERSED, never edited back")
    void paidOnlyReverses() {
        assertThat(MarketplaceStateMachines.SETTLEMENT.allowedFrom(Settlement.PAID))
                .containsExactly(Settlement.REVERSED);
        assertThat(MarketplaceStateMachines.SETTLEMENT.canTransition(Settlement.NOT_ELIGIBLE, Settlement.ELIGIBLE))
                .as("nothing is payable at placement: NOT_ELIGIBLE must pass through PENDING first").isFalse();
        assertThat(MarketplaceStateMachines.SETTLEMENT.canTransition(Settlement.NOT_ELIGIBLE, Settlement.PAID))
                .isFalse();
    }

    @Test
    @DisplayName("[MKT-R10.2] a consumed or expired hold never comes back")
    void reservationTerminals() {
        assertThat(MarketplaceStateMachines.RESERVATION.isTerminal(Reservation.CONSUMED)).isTrue();
        assertThat(MarketplaceStateMachines.RESERVATION.isTerminal(Reservation.EXPIRED)).isTrue();
        assertThat(MarketplaceStateMachines.RESERVATION.isTerminal(Reservation.RELEASED)).isTrue();
    }

    @Test
    @DisplayName("[MKT-R19.1] every state of every machine is either reachable or the start state")
    void noOrphanStates() {
        assertReachable(MarketplaceStateMachines.ORDER, Order.class, Order.DRAFT);
        assertReachable(MarketplaceStateMachines.SELLER_ORDER, SellerOrder.class, SellerOrder.UNASSIGNED);
        assertReachable(MarketplaceStateMachines.PAYMENT, Payment.class, Payment.UNPAID);
        assertReachable(MarketplaceStateMachines.SETTLEMENT, Settlement.class, Settlement.NOT_ELIGIBLE);
        assertReachable(MarketplaceStateMachines.RESERVATION, Reservation.class, Reservation.HELD);
    }

    @Test
    @DisplayName("[MKT-R19.1] the published move set cannot be modified by a caller")
    void publishedSetIsReadOnly() {
        assertThatThrownBy(() -> MarketplaceStateMachines.ORDER.allowedFrom(Order.DRAFT).add(Order.COMPLETED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(MarketplaceStateMachines.ORDER.canTransition(Order.DRAFT, Order.COMPLETED)).isFalse();
    }

    @SafeVarargs
    private static <E extends Enum<E>> void walk(StateMachine<E> m, E... path) {
        for (int i = 0; i + 1 < path.length; i++) {
            assertThat(m.transition(path[i], path[i + 1])).isEqualTo(path[i + 1]);
        }
    }

    private static <E extends Enum<E>> void assertReachable(StateMachine<E> m, Class<E> type, E start) {
        java.util.Set<E> seen = java.util.EnumSet.of(start);
        java.util.Deque<E> todo = new java.util.ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            for (E next : m.allowedFrom(todo.pop())) {
                if (seen.add(next)) todo.push(next);
            }
        }
        assertThat(seen).as(type.getSimpleName() + " states reachable from " + start)
                .containsExactlyInAnyOrder(type.getEnumConstants());
    }
}
