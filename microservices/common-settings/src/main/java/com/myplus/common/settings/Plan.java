package com.myplus.common.settings;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * E1 — what a tenant's SUBSCRIPTION includes, as opposed to what its owner switched on.
 *
 * <h3>The third question, and why it is not the other two</h3>
 * A tenant is one {@link Shape} × a set of {@link Capability}. This is the ceiling over the second of those:
 *
 * <pre>
 *   Shape        what do this tenant's screens look like?      information architecture
 *   Capability   what has this tenant switched on?             tenant configuration  (org_setting)
 *   Plan         what was this tenant SOLD?                    platform entitlement  (this + org_entitlement)
 * </pre>
 *
 * Conflating the last two is the hole E1 closes: {@code org_setting} is written by the tenant's own owner, so
 * without a ceiling above it an owner grants themselves whatever they like.
 *
 * <h3>The ceiling only ever REMOVES</h3>
 * An entitlement can take away a capability the configuration layer would have allowed. It can never switch one
 * on that the owner has not chosen. The framing is AWS's Service Control Policies rather than a permission
 * grant, and it is deliberate: a bound that can only subtract is analysable — the effective set after a deploy
 * is provably a subset of the set before it, which is what makes the grandfathering argument in
 * {@code e1-entitlement-ceiling.md} §7 hold.
 *
 * <h3>Why this is code and not a table (ruling D-3)</h3>
 * Same reason {@link Shape#preset()} is: a plan's contents are a product decision that changes with a release,
 * is read on every token mint, and benefits from being greppable and type-checked. A new tier is ONE enum entry
 * and no other file changes — Open/Closed at the seam that actually moves. The per-tenant deviation from the
 * plan lives in {@code org_entitlement}, which is data, because THAT changes per customer and per contract.
 *
 * <h3>What must never appear here</h3>
 * A client's name, and a capability named after a trade. Same rule as {@link Capability} and {@link Shape}.
 *
 * <p><b>The contents below are a PRICING decision, not an engineering one.</b> They are recorded in one place
 * so whoever owns pricing can change them without reading anything else.
 */
public enum Plan {

    /**
     * The legacy and lapsed tier — basic stock hygiene, no advanced trade.
     *
     * <p>⚠ Every organization created before E1 sits here <b>by accident, not by decision</b>:
     * {@code OrganizationService.getOrCreatePrimaryOrg} builds an {@code Organization} without a plan and
     * {@code @Builder.Default} supplies {@code "FREE"}. Nothing had ever read that value for capability.
     *
     * <p>That is why {@link EntitlementSource} asks TWO questions and why the read path may never consult a
     * plan: reading this default as a licensing decision stripped ten capabilities from every legacy tenant on
     * the deploy that introduced the ceiling. A column default is not a decision. This set bounds what may be
     * switched ON; it may never turn anything off.
     */
    FREE("FREE", EnumSet.of(Capability.BATCH_TRACKING, Capability.EXPIRY_TRACKING, Capability.LOOSE_SELLING)),

    /**
     * Self-signup, time-boxed by {@code Organization.trialEndsAt} (ruling D-4).
     *
     * <p>Everything, on purpose: a trial that hides half the product does not sell the product. The bound is
     * the DATE, not the feature list, and it is applied where the entitlement is resolved rather than by a job
     * that rewrites rows — a missed job would otherwise be free licensing.
     */
    TRIAL("TRIAL", EnumSet.allOf(Capability.class)),

    /**
     * The shared sandbox. Everything, bounded by {@code entryCap} instead — 50 writes per module.
     *
     * <p>{@code entryCap} deliberately does NOT fold into this enum (ruling D-4): it is a LIMIT ("how many
     * rows"), not a capability ("what kind of work"), and merging the two would make "installments" and "50
     * writes" the same kind of thing.
     */
    DEMO("DEMO", EnumSet.allOf(Capability.class)),

    /**
     * The paid tier. Everything today, because every operator-provisioned tenant is a paying customer and
     * there is no middle tier to sell yet.
     *
     * <p>When one is wanted, it is a new constant here with a narrower set — no other file changes.
     */
    PRO("PRO", EnumSet.allOf(Capability.class));

    private final String code;
    private final Set<Capability> capabilities;

    Plan(String code, Set<Capability> capabilities) {
        this.code = code;
        this.capabilities = Collections.unmodifiableSet(capabilities);
    }

    /** The stored value on {@code organizations.plan}. */
    public String code() { return code; }

    /** What this plan includes. Anything absent is not entitled unless an explicit row grants it. */
    public Set<Capability> capabilities() { return capabilities; }

    /** Does this plan include the capability? */
    public boolean includes(Capability capability) {
        return capability != null && capabilities.contains(capability);
    }

    /**
     * Resolve a stored plan code, falling back to {@link #FREE}.
     *
     * <p>Falls back to the NARROWEST tier, which is the opposite direction from {@link Shape#byCode} — and the
     * difference is the point. An unreadable shape must not stop a shop trading, so it resolves permissively.
     * An unreadable plan is a licensing question, and guessing generously there gives away the product to
     * anything that can write a typo into a column. The tenant still trades; it just does not silently acquire
     * capabilities nobody sold it.
     */
    public static Plan byCode(String code) {
        if (code == null || code.isBlank()) return FREE;
        for (Plan p : values()) {
            if (p.code.equalsIgnoreCase(code.trim())) return p;
        }
        return FREE;
    }
}
