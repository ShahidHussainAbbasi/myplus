package com.myplus.common.settings;

/**
 * C1 — what a tenant is allowed to DO, as opposed to what its screens look like.
 *
 * <h3>The two axes, and why this enum is only one of them</h3>
 * A tenant is <b>one shape × a set of capabilities</b>. The shape is the information architecture — retail,
 * pharmacy, distribution — and it decides wording and which dashboard. This enum is the other axis: the
 * behaviour a tenant may switch on. A mobile shop and a furniture showroom differ on shape alone; a pharmacy
 * differs by capability, and no amount of relabelling a POS produces one.
 *
 * <h3>Why these are settings and not a table of their own</h3>
 * {@code org_setting} already does per-tenant configuration with catalog defaults, tenant overrides, an owner
 * screen that renders itself, and a bounded per-tenant cache. A second configuration store would mean two
 * places to look, two caches, and two answers the day they disagree. So a capability IS a setting, under a
 * reserved {@code org.cap.*} namespace.
 *
 * <h3>What must never appear here</h3>
 * A client's name. {@code if (organizationId == 24)} is the failure this whole mechanism exists to prevent —
 * and so is {@code if ("PHARMA".equals(type))}. Capabilities describe behaviour, never customers and never
 * verticals.
 *
 * <h3>Default ON, deliberately</h3>
 * Every capability here defaults to enabled. On the deploy that introduces this, every tenant keeps exactly
 * the screens and endpoints it had — the first slice changes the SOURCE of a decision, never the decision.
 * Turning something off is then an owner's explicit act, and reversible.
 */
public enum Capability {

    // ── inventory behaviour ─────────────────────────────────────────────────────────────────────

    /** Goods are tracked in batches with their own identity. Pharmacy, agri-chem, food. */
    BATCH_TRACKING("batchTracking", "Track stock in batches",
            "Each delivery keeps its own batch number, so stock can be traced back to what arrived when."),

    /** Batches carry an expiry date, and it is enforced on the sale path. */
    EXPIRY_TRACKING("expiryTracking", "Track expiry dates",
            "Records an expiry against each batch and keeps expired stock out of what can be sold."),

    /** First-expiry-first-out allocation, rather than whatever is nearest. */
    FEFO_ALLOCATION("fefoAllocation", "Sell nearest-expiry stock first",
            "Picks the batch closest to expiry when a sale is rung up, so stock is used before it lapses."),

    /**
     * Individually identified units — IMEI on a handset, a serial on an appliance.
     *
     * <p><b>Tenant-level permission, not a per-product rule.</b> A mobile shop sells handsets that are
     * IMEI-tracked AND chargers that are not, so the product decides whether it needs a serial and this
     * decides whether the tenant may ask at all. Enforcement is capability AND product policy; a tenant
     * without the capability cannot set the product policy.
     */
    SERIAL_TRACKING("serialTracking", "Track individual serial / IMEI numbers",
            "For goods identified one unit at a time — a handset's IMEI, an appliance's serial number. "
                    + "You choose which products need one."),

    /** A condition grade on individually tracked goods — new, used, refurbished. */
    CONDITION_GRADING("conditionGrading", "Record item condition (new / used)",
            "Marks whether a unit is new, used or refurbished. Used with serial tracking on second-hand trade."),

    /** Selling a pack by the piece — tablets from a strip, cable by the metre. */
    LOOSE_SELLING("looseSelling", "Sell loose units from a pack",
            "Sell part of a pack — tablets from a strip, cable by the metre — with the price worked out for you."),

    // ── trade behaviour ─────────────────────────────────────────────────────────────────────────

    /** A prescription is required before a controlled product may be dispensed. */
    RX_REQUIRED("rxRequired", "Require a prescription for controlled items",
            "Blocks dispensing of prescription-only products until a valid prescription is recorded."),

    /** Reps book orders in the field, against outlets on a territory. */
    FIELD_SALES("fieldSales", "Field sales and order booking",
            "Reps book orders away from the counter, against the outlets assigned to them."),

    /** Journey plans, beats, visit verification. */
    JOURNEY_PLANNING("journeyPlanning", "Journey plans and visits",
            "Plan which outlets a rep visits and on which day, and record what happened on each visit."),

    /** Cash collected in the field, settled at day end. */
    COLLECTIONS("collections", "Driver and rep collections",
            "Record cash collected on delivery, and settle it against the books at the end of the day."),

    /** Selling on terms, with a schedule and a chase list. */
    INSTALLMENTS("installments", "Sell on installments",
            "Sell goods on a payment plan, with a schedule, reminders and a collections worklist."),

    /** Price tiers per customer class — dealer, wholesale, retail. */
    DEALER_PRICING("dealerPricing", "Dealer and tier pricing",
            "Different price lists for dealers, wholesale and retail customers."),

    /**
     * Free-goods offers — "buy 10, get 1 free" — from suppliers and to customers.
     *
     * <p>ONE capability for one engine, deliberately, rather than separate supplier and customer switches:
     * they are the same rule resolved against a different party, and two flags would let a tenant end up in a
     * state where an offer can be authored but never applied.
     */
    BONUS_SCHEMES("bonusSchemes", "Bonus and free-goods offers",
            "Buy-and-get offers from suppliers and to customers, with the free goods counted in stock.");

    private final String code;
    private final String label;
    private final String help;

    Capability(String code, String label, String help) {
        this.code = code;
        this.label = label;
        this.help = help;
    }

    /** The short code, e.g. {@code serialTracking}. Used in {@code [data-capability]} and on the wire. */
    public String code() { return code; }

    /** Owner-facing name on the Configuration screen. */
    public String label() { return label; }

    /** Owner-facing explanation. Written for a shopkeeper, not an engineer. */
    public String help() { return help; }

    /**
     * The settings key this capability is stored under.
     *
     * <p>One place builds it, so the namespace cannot drift. {@code org.cap.} is reserved: a settings key in
     * that namespace that does not correspond to a value here is a mistake, not a feature.
     */
    public String settingKey() { return "org.cap." + code; }

    /** Resolve by code, or null. Used when a code arrives over the wire or off an HTML attribute. */
    public static Capability byCode(String code) {
        if (code == null) return null;
        for (Capability c : values()) {
            if (c.code.equals(code)) return c;
        }
        return null;
    }
}
