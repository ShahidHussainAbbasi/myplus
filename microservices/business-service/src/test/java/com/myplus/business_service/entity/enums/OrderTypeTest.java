package com.myplus.business_service.entity.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RST-R2a — the resolution rules for an order type, as pure JUnit so they run on every {@code mvn test}.
 *
 * <p>Design: {@code microservices/docs/slices/rst-r2a-order-types.md}.
 *
 * <p>Every case here pins a decision that would be invisible if it regressed. The enum has no behaviour to
 * speak of; what it has is a set of deliberate answers to "what happens when the value is missing, unknown,
 * or nearly right", and each of those has a wrong answer that looks reasonable.
 */
class OrderTypeTest {

    @Test
    @DisplayName("⭐⭐ an absent value stays ABSENT — it never becomes a default")
    void nullStaysNull() {
        /*
         * THE CASE THAT PROTECTS EVERY EXISTING INVOICE.
         *
         * Null means "this tenant does not work in service modes", which is true of every sale ever written
         * before V68 and every sale in a shop without the capability. The tempting convenience is to default
         * to TAKE_AWAY here, because "most counter sales are take-away" — and that would retro-label years of
         * retail and pharmacy invoices, then feed those invented numbers into the day's takings split.
         *
         * Blank and whitespace are the same answer as null: a form that posts an empty select has not chosen.
         */
        assertThat(OrderType.byCode(null)).isNull();
        assertThat(OrderType.byCode("")).isNull();
        assertThat(OrderType.byCode("   ")).isNull();
    }

    @Test
    @DisplayName("⭐⭐ an UNKNOWN value resolves to null, and never throws")
    void unknownResolvesToNullRatherThanThrowing() {
        /*
         * Direction matters, and it is the OPPOSITE of Shape.byCode.
         *
         * Shape falls back permissively (GENERAL, everything visible) because an unreadable shape must never
         * stop a shop trading. Plan falls back NARROWLY (FREE) because guessing generously about a licence
         * gives the product away to a typo. Here there is nothing to keep trading and nothing to give away:
         * an unrecognised service mode is simply unknown, and inventing DINE_IN would put a figure in a
         * report that nobody entered.
         *
         * Not throwing is the other half. This value arrives over a wire the monolith re-serialises, from a
         * client that may be older than the server. A value this version does not know must cost the sale
         * its order type, never the sale itself.
         */
        assertThat(OrderType.byCode("CURBSIDE")).isNull();
        assertThat(OrderType.byCode("dinein")).isNull();     // near-miss: no separator, genuinely not a value
        assertThat(OrderType.byCode("42")).isNull();
    }

    @Test
    @DisplayName("⭐ the three real values resolve, however the client spelled them")
    void realValuesResolve() {
        // A select posts DINE_IN; a hand-written integration posts "dine-in"; a careless one posts "Dine In".
        // All three mean the same thing to a person, so they mean the same thing here — the alternative is
        // silently dropping a type the caller clearly stated.
        assertThat(OrderType.byCode("DINE_IN")).isEqualTo(OrderType.DINE_IN);
        assertThat(OrderType.byCode("dine-in")).isEqualTo(OrderType.DINE_IN);
        assertThat(OrderType.byCode(" Dine In ")).isEqualTo(OrderType.DINE_IN);
        assertThat(OrderType.byCode("take_away")).isEqualTo(OrderType.TAKE_AWAY);
        assertThat(OrderType.byCode("DELIVERY")).isEqualTo(OrderType.DELIVERY);
    }

    @Test
    @DisplayName("⭐⭐ DELIVERY is the only type that needs a contact")
    void onlyDeliveryNeedsAContact() {
        /*
         * The control on the refusal. It would be easy to write the guard so that it demands a customer for
         * every typed order, which would break the counter's fast path — a walk-in buying a burger to eat in
         * has no name and needs none. Asserting the OTHER two are free is what stops that.
         */
        assertThat(OrderType.DELIVERY.requiresCustomerContact()).isTrue();
        assertThat(OrderType.DINE_IN.requiresCustomerContact()).isFalse();
        assertThat(OrderType.TAKE_AWAY.requiresCustomerContact()).isFalse();
    }

    @Test
    @DisplayName("every value has owner-facing words, because a receipt prints them")
    void everyValueHasALabel() {
        // Enum names reach a customer's receipt and an owner's report column. "TAKE_AWAY" on a printed slip
        // is a leaked identifier; the label is what a person reads. A new value added without one would
        // otherwise be discovered by a customer.
        for (OrderType t : OrderType.values()) {
            assertThat(t.label()).as("%s has a label", t.name()).isNotBlank();
            assertThat(t.label()).as("%s's label is not the enum name", t.name()).isNotEqualTo(t.name());
        }
    }
}
