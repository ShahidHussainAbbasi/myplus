package com.myplus.common.settings;

/**
 * E3 — whether a tenant may trade at all.
 *
 * <h3>The third axis, and it is not the other two</h3>
 * <pre>
 *   Shape        what do this tenant's screens look like?   information architecture
 *   Capability   what has this tenant switched on?          tenant configuration
 *   Plan         what was this tenant SOLD?                 platform entitlement
 *   Status       may this tenant TRADE?                     platform lifecycle   ← this
 * </pre>
 *
 * Deliberately independent of {@link Plan}: an operator upgrading a suspended customer's plan in preparation
 * for their return must not silently let them back in before payment has cleared. Status is changed by an
 * explicit act, never as a side effect of something else.
 *
 * <h3>Why this is an enum and not the free-text column it replaces</h3>
 * {@code organizations.status} is a {@code String}, written {@code "ACTIVE"} at creation and — until E3 — read
 * by nothing. That is the same shape as {@code plan} before E2 validated it at its one write (finding F2), and
 * the same shape as {@code Organization.type}, which the vertical-profile design flagged as *free text at the
 * column, enumerated in code*. An operator typing {@code SUSPEND} must be told, not left believing a customer
 * is stopped while they carry on selling.
 *
 * <h3>SUSPENDED and CLOSED differ only in intent, and that is enough</h3>
 * Both refuse a login today. They answer different questions on a report — dunning versus churn — and a merged
 * state can never be taken apart again. Stripe keeps {@code unpaid} and {@code canceled} separate for exactly
 * this reason.
 *
 * <h3>Nothing here deletes anything</h3>
 * {@code CLOSED} is a status, not a purge. Salesforce deactivates users and keeps them; AWS and Atlassian
 * retain data well beyond the end of a subscription. Deletion is a different, irreversible conversation.
 */
public enum OrganizationStatus {

    /** Trading normally. The state every tenant is created in. */
    ACTIVE("ACTIVE"),

    /**
     * Temporarily stopped — non-payment, abuse, a dispute. Reversible by an operator, and the reversal is
     * gated ({@code tenant-lifecycle.cy.js} case 2) because a lever that only goes one way is an accident
     * waiting to happen: a wrong suspension stops a real business trading.
     */
    SUSPENDED("SUSPENDED"),

    /** The customer has left. Refuses a login exactly as {@link #SUSPENDED} does; kept apart for reporting. */
    CLOSED("CLOSED");

    private final String code;

    OrganizationStatus(String code) {
        this.code = code;
    }

    /** The stored value on {@code organizations.status}. */
    public String code() { return code; }

    /** May a tenant in this state obtain a token? */
    public boolean allowsSignIn() { return this == ACTIVE; }

    /**
     * Resolve a stored status, falling back to {@link #ACTIVE}.
     *
     * <p>Falls back PERMISSIVELY, which is the opposite of {@link Plan#byCode} and deliberate. An unreadable
     * plan must not give the product away, so it resolves to the narrowest tier. An unreadable <i>status</i>
     * is the difference between a shop trading and a shop shut: a typo, a value from an older build, or a NULL
     * left by a migration must never be the reason a paying customer cannot log in on a Monday morning.
     *
     * <p>The strictness lives at the WRITE instead — {@link #parse} refuses anything it does not recognise —
     * so an unknown value cannot get into the column in the first place.
     */
    public static OrganizationStatus byCode(String code) {
        if (code == null || code.isBlank()) return ACTIVE;
        for (OrganizationStatus s : values()) {
            if (s.code.equalsIgnoreCase(code.trim())) return s;
        }
        return ACTIVE;
    }

    /**
     * Resolve for a WRITE: returns null for anything unrecognised so the caller can refuse.
     *
     * <p>Separate from {@link #byCode} because reads and writes want opposite answers here, and one method
     * doing both is how a silent fallback ends up applied to an operator's typo.
     */
    public static OrganizationStatus parse(String code) {
        if (code == null || code.isBlank()) return null;
        for (OrganizationStatus s : values()) {
            if (s.code.equalsIgnoreCase(code.trim())) return s;
        }
        return null;
    }
}
