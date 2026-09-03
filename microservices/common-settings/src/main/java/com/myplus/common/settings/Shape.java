package com.myplus.common.settings;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * C4 — the OTHER axis: what KIND of business this tenant is.
 *
 * <h3>Shape and capability are not the same question</h3>
 * A tenant is <b>one shape × a set of capabilities</b>. The shape is the information architecture — what the
 * screens are called and which dashboard opens. {@link Capability} is what the tenant may DO. Two distributors
 * that differ on whether they sell on terms are the same shape with different capabilities, and no amount of
 * relabelling a vertical produces that difference. Flattening the two into one list is the mistake §4b of the
 * design argues against at length.
 *
 * <h3>What a shape is FOR here: seeding, not deciding</h3>
 * A shape supplies a sensible starting set of capabilities so onboarding is one question instead of twelve.
 * It never has the last word — an explicit tenant override always wins ({@link CapabilityService}). Without
 * that rule, choosing "Pharmacy" would silently destroy deliberate choices and the only safe advice would be
 * "never change your profile", which is not a setting, it is a trap.
 *
 * <h3>{@link #GENERAL} is the migration, and it is why this deploy changes nothing</h3>
 * Every existing tenant has no {@code org.shape} row, so every one resolves to {@code GENERAL}, whose preset is
 * <b>every capability</b>. That is exactly today's behaviour — capabilities all default ON. A tenant only ever
 * narrows by explicitly picking a shape, which is a deliberate act on their own Configuration screen and
 * reversible in a click.
 *
 * <p><b>What must never appear here:</b> a client's name. {@code if (organizationId == 24)} is the failure this
 * whole mechanism exists to prevent. "Mobile shop" is not a shape — it is {@link #RETAIL} plus serial tracking,
 * condition grading and installments, which is precisely the point of having two axes.
 */
public enum Shape {

    /**
     * No shape chosen. Everything on — the state every tenant is in before anybody picks one.
     *
     * <p>Deliberately first, so a corrupt or unrecognised stored value resolving to the fallback lands on the
     * permissive option rather than silently stripping a tenant's screens.
     */
    GENERAL("general", "General - show every feature",
            EnumSet.allOf(Capability.class)),

    /** A counter that sells finished goods one at a time. Handsets, furniture, hardware, clothing. */
    RETAIL("retail", "Retail counter / POS",
            EnumSet.of(Capability.INSTALLMENTS, Capability.DEALER_PRICING)),

    /**
     * Dispensing. Batches with expiry, first-expiry-first-out, part-packs, and prescriptions.
     *
     * <p>{@code RX_REQUIRED} is on by default but genuinely optional — a veterinary or agri-chem counter is
     * the same shape and often is not prescription-controlled. That is a capability the owner switches off,
     * not a reason to invent a second shape.
     */
    PHARMACY("pharmacy", "Pharmacy / dispensing",
            EnumSet.of(Capability.BATCH_TRACKING, Capability.EXPIRY_TRACKING, Capability.FEFO_ALLOCATION,
                    Capability.LOOSE_SELLING, Capability.RX_REQUIRED)),

    /** Selling on to other businesses: reps, routes, collections and tiered prices. */
    DISTRIBUTION("distribution", "Distribution / wholesale",
            EnumSet.of(Capability.BATCH_TRACKING, Capability.EXPIRY_TRACKING, Capability.FEFO_ALLOCATION,
                    Capability.FIELD_SALES, Capability.JOURNEY_PLANNING, Capability.COLLECTIONS,
                    // Free goods are how distribution actually trades — a distributor without bonus offers
                    // is the exception, not the default.
                    Capability.DEALER_PRICING, Capability.BONUS_SCHEMES)),

    /** Selling to the public online. Orders arrive without anybody at a till. */
    STOREFRONT("storefront", "Online storefront",
            EnumSet.of(Capability.DEALER_PRICING));

    private final String code;
    private final String label;
    private final Set<Capability> preset;

    Shape(String code, String label, Set<Capability> preset) {
        this.code = code;
        this.label = label;
        this.preset = Collections.unmodifiableSet(preset);
    }

    /** The stored value, e.g. {@code retail}. */
    public String code() { return code; }

    /** Owner-facing name on the Configuration screen. Written for a shopkeeper, not an engineer. */
    public String label() { return label; }

    /** The capabilities this kind of business starts with. Anything absent starts OFF. */
    public Set<Capability> preset() { return preset; }

    /** Is this capability part of this shape's starting set? */
    public boolean includes(Capability capability) {
        return capability != null && preset.contains(capability);
    }

    /**
     * Resolve a stored code, falling back to {@link #GENERAL}.
     *
     * <p>Falls back rather than throwing, and falls back to the PERMISSIVE option on purpose. An unreadable
     * shape — a typo, a value written by an older build, a row from a shape this version has dropped — must
     * not silently strip a working tenant of its screens. The failure mode of guessing wrong here is a support
     * call either way; this direction is the one that does not stop a shop trading.
     */
    public static Shape byCode(String code) {
        if (code == null || code.isBlank()) return GENERAL;
        for (Shape s : values()) {
            if (s.code.equalsIgnoreCase(code.trim())) return s;
        }
        return GENERAL;
    }

    /** The settings key a tenant's shape is stored under. One place builds it, so it cannot drift. */
    public static String settingKey() { return "org.shape"; }
}
