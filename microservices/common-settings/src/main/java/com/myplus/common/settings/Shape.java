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
                    Capability.LOOSE_SELLING, Capability.RX_REQUIRED),
            // ⚠ THE FLOOR. A dispensing counter may not switch expiry tracking off.
            //
            // EXP-1 made the capability mean something in the stock engine: with it OFF, dated stock is
            // ordinary sellable stock and the allocator will pick it. That is right for a mobile shop, which
            // has no expiry dates it trusts. For a pharmacy it would mean an owner could turn a checkbox off
            // and begin dispensing expired medicine, with the screens reporting it as ordinary stock. A
            // setting whose worst outcome is that is not a setting.
            //
            // BATCH_TRACKING is deliberately NOT floored with it: a pharmacy that records expiry without
            // batch numbers is doing less than it should, but nothing it does is unsafe. RX_REQUIRED stays
            // optional for the reason above — a veterinary or agri-chem counter is the same shape.
            EnumSet.of(Capability.EXPIRY_TRACKING)),

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

    /*
     * ⚠ WHY THERE IS NO "RESTAURANT" / FOOD_SERVICE SHAPE HERE (decided 2026-09-25, RST vertical)
     *
     * It was proposed and refused, by the rule at the top of this file. On today's build a restaurant is
     * RETAIL plus MADE_TO_ORDER: one capability, the same screens, the same dashboard, the same navigation.
     * That is the "Mobile shop" case word for word — and Mobile shop is the example this class uses to
     * explain why two axes exist. Adding the entry would buy a friendlier word in one dropdown and spend the
     * distinction to get it.
     *
     * The onboarding instruction that follows from this is not a workaround, and should not be apologised
     * for: a food counter picks "Retail counter / POS" and switches ON "Sell items made to order". That is
     * an accurate description of what the business is here.
     *
     * WHAT WOULD MAKE IT A SHAPE — the test is this class's own definition, "what the screens are called and
     * which dashboard opens", not how distinctive the trade feels:
     *   - order types (dine-in / take-away / delivery) that change the workflow,
     *   - tables and open tabs — a sale held open and added to over an hour,
     *   - a kitchen display with its own station routing and its own staff who see no prices.
     * Those are phase R2 of microservices/docs/restaurant-vertical-design.md. When they exist, a food counter
     * genuinely opens a different product and FOOD_SERVICE becomes one enum entry here — preset
     * MADE_TO_ORDER, and revisit EXPIRY_TRACKING then rather than now, because raw meat and dairy are not
     * stock rows until recipes land in R3 and this flag is read by the ALLOCATOR (see ReservationService):
     * switching it on today would let a careless date on a crate of drinks refuse a sale, protecting nothing.
     */

    private final String code;
    private final String label;
    private final Set<Capability> preset;
    private final Set<Capability> mandatory;

    Shape(String code, String label, Set<Capability> preset) {
        this(code, label, preset, EnumSet.noneOf(Capability.class));
    }

    Shape(String code, String label, Set<Capability> preset, Set<Capability> mandatory) {
        this.code = code;
        this.label = label;
        this.preset = Collections.unmodifiableSet(preset);
        this.mandatory = Collections.unmodifiableSet(mandatory);
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
     * EXP-1 — capabilities this shape may not switch OFF, whatever the tenant saved.
     *
     * <p>A preset is a starting point an owner may change; this is a floor they may not go below, because for
     * this kind of business the capability is not a preference. Only {@link #PHARMACY} declares one today
     * ({@code EXPIRY_TRACKING}), and anything mandatory is necessarily also in the preset — a shape cannot
     * require what it does not start with.
     *
     * <p>⚠ It does NOT outrank the platform ceiling. A capability the operator has revoked stays off: the
     * floor answers "may this tenant choose otherwise", not "may this tenant have it at all". A shop whose
     * entitlement has genuinely been withdrawn needs to be told that, not quietly given the capability back.
     */
    public boolean mandates(Capability capability) {
        return capability != null && mandatory.contains(capability);
    }

    /** The capabilities this shape does not allow to be switched off. */
    public Set<Capability> mandatory() { return mandatory; }

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
