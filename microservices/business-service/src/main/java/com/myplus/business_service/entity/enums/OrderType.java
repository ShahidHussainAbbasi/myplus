package com.myplus.business_service.entity.enums;

/**
 * RST-R2a — how a sale was served: eaten in, taken away, or delivered.
 *
 * <p>Design: {@code microservices/docs/slices/rst-r2a-order-types.md}.
 *
 * <h3>⚠ This is a PROPERTY, not a status — and the difference is load-bearing</h3>
 * There is no transition map here, deliberately, and the absence is not an oversight. A sibling enum
 * ({@code FulfilmentStatus} in marketplace-service) carries an {@code ALLOWED} whitelist because its values
 * describe a JOURNEY — an order that has been packed cannot go back to new, and one that has shipped cannot
 * be cancelled, because the goods are on a van. Order type describes no journey. A take-away does not
 * "become" a delivery through any legal sequence; if it is wrong it was always wrong, and the fix is a
 * correction with an audit trail, which is what {@code SellController.changeOrderType} is.
 *
 * <p>The corollary matters more: <b>nothing may read this to decide whether money is owed, and paying must
 * never change it.</b> Kitchen state and money state advance independently — a take-away is normally paid
 * before it is cooked, a dine-in table an hour after it is eaten. Collapsing the two makes a paid order look
 * unmade and an unmade order look owed for. The kitchen lifecycle that genuinely moves arrives in R2b and is
 * a separate field on purpose.
 *
 * <h3>Stored as a STRING</h3>
 * {@code @Enumerated(EnumType.STRING)} against a {@code VARCHAR(16)} column, never a MySQL {@code ENUM}. A
 * String field mapped to a MySQL {@code ENUM} column is one of the two shapes that crash-looped two services
 * under {@code ddl-auto=validate}, 59 and 9 restarts, and the failure surfaced as unrelated screens breaking.
 *
 * <h3>NULL is an answer</h3>
 * A null order type means "this tenant does not work in service modes" — every existing invoice in every
 * tenant, and every sale in a shop without the capability. It must render as ABSENT everywhere, never as a
 * default: seeding one would retro-label a year of retail sales as dine-in.
 */
public enum OrderType {

    /** Eaten on the premises. R2b attaches a table to these and holds the bill open. */
    DINE_IN("Dine-in"),

    /**
     * Packed and handed over at the counter.
     *
     * <p>The proposed default for a food counter, because it is the commonest and because it is the one
     * that assumes least: a take-away recorded as dine-in overstates covers, while the reverse merely
     * understates them.
     */
    TAKE_AWAY("Take-away"),

    /**
     * Leaves with a rider.
     *
     * <p>⚠ The only type that REFUSES without a customer contact — an order nobody can deliver is not a
     * sale, it is a problem discovered at the door. Dine-in and take-away stay no-customer-needed, so the
     * counter's fast path is untouched. This is also the only type a delivery charge is legitimate on,
     * though that charge is its own slice: it is money, and money gets its own gate.
     */
    DELIVERY("Delivery");

    private final String label;

    OrderType(String label) {
        this.label = label;
    }

    /** Owner-facing words, for a receipt and a report column. */
    public String label() {
        return label;
    }

    /** True for the one type that cannot be served without knowing where it is going. */
    public boolean requiresCustomerContact() {
        return this == DELIVERY;
    }

    /**
     * Resolve a stored or wire value, or null.
     *
     * <p>Falls back to NULL rather than to a default, and never throws. An unreadable value must render as
     * "not recorded" — the same direction {@code Plan.byCode} takes and the opposite of {@code Shape.byCode},
     * which falls back permissively so a bad value cannot stop a shop trading. Here there is nothing to keep
     * trading: an unknown service mode is simply unknown, and inventing DINE_IN would put a number in a
     * report that nobody entered.
     */
    public static OrderType byCode(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (OrderType t : values()) {
            if (t.name().equals(c)) return t;
        }
        return null;
    }
}
