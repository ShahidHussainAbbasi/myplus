package com.myplus.common.settings;

/**
 * E1 — the platform's licensing view of a tenant. <b>Two questions, deliberately, because they have opposite
 * safe answers.</b>
 *
 * <h3>The design error this interface exists to correct</h3>
 * The first version had ONE method — "is this tenant entitled?" — consulted by both the write guard and the
 * read path, and defined as <i>an explicit row, else the plan</i>. That is correct for a write and
 * catastrophic for a read: a legacy tenant carries {@code plan = FREE} from {@code @Builder.Default}, a value
 * nothing had ever read for capability, so on the deploy that introduced the ceiling every such tenant was
 * measured against a plan nobody had sold them and silently lost ten capabilities.
 *
 * <p>Patching it with "no rows at all means no ceiling" narrowed the blast radius and did not fix the shape of
 * the mistake: <b>absence of a licensing record was still being read as a licensing decision.</b> It is not
 * one. So the two questions are now separate, each with the default that is safe for its own direction:
 *
 * <pre>
 *   grantable(org, cap)   may the owner switch this ON?     plan ∪ explicit rows   default: the PLAN decides
 *   revoked(org, cap)     has this been WITHDRAWN?          explicit rows ONLY     default: NO
 * </pre>
 *
 * <h3>Why {@link #revoked} may never consult the plan</h3>
 * It subtracts from what a tenant is already using, so it must fire only on <b>positive evidence that somebody
 * decided</b> — an operator's row saying SUSPENDED, or a grant that has run out. Silence is not evidence. A
 * missing licence record is a data gap, not a customer who has not paid, and treating the two alike is how a
 * shop that was trading yesterday loses its screens today.
 *
 * <p>This is the same fail-OPEN-for-visibility stance {@link CapabilityService} already documents, applied one
 * layer down. The bound that actually protects revenue is {@link #grantable}, which fails closed, guards a
 * decision a human is making, and can explain itself on the screen.
 *
 * <h3>Who implements it</h3>
 * auth-service, because it already owns the tenant and mints the token every other service reads (C3c). Every
 * other service inherits the answer through the {@code caps} claim and needs no implementation, no table and
 * no remote call.
 *
 * <h3>The default is a real bean, not an optional injection</h3>
 * {@code CommonSettingsAutoConfiguration} publishes {@link #PERMISSIVE} under
 * {@code @ConditionalOnMissingBean}, so the injection into {@link CapabilityService} stays <b>required</b>.
 * {@code JpaSettingsStore}'s javadoc records why: OMS O3 shipped a resolver with optional injection and no
 * store, and it silently did nothing for a whole slice. A guard that disables itself when a bean is missing is
 * worse than no guard, because it reads as protection.
 */
public interface EntitlementSource {

    /**
     * May this capability be switched ON for this tenant?
     *
     * <p>The commercial bound, and the one that closes the licensing hole: an owner holds
     * {@code ADMIN_PRIVILEGE} inside their own tenant, so without this they can grant themselves anything.
     * Consulted by {@code EntitlementWriteGuard} at the write, where a refusal reaches a person who can be
     * told which plan they are on.
     *
     * <p>It is also where a capability added to the enum LATER is bounded (F6): a tenant meets it with no row,
     * so their plan decides, and the ceiling does not leak a little on every release.
     *
     * <p>Fails closed in the sense that the plan is the floor — but it never removes anything already in use,
     * because nothing calls it on a read.
     */
    boolean grantable(Long organizationId, Capability capability);

    /**
     * Has this capability been explicitly WITHDRAWN from this tenant?
     *
     * <p>The only thing permitted to subtract from what a tenant already has. True only on positive evidence:
     * an operator's row that is not ACTIVE, or one whose date window has closed. <b>Never true merely because
     * a plan omits the capability, and never true because no row exists.</b>
     *
     * <p>Implementations must not throw for an unknown org or capability; the resolver above treats a runtime
     * failure as "cannot tell", and cannot-tell on a read means the tenant keeps its screens.
     */
    boolean revoked(Long organizationId, Capability capability);

    /**
     * The pre-E1 behaviour: everything grantable, nothing revoked.
     *
     * <p>Used by every service that does not own the entitlement store — which is all of them except auth. Not
     * a hole: those services read the ceiling's RESULT from the token, so a permissive local source is the
     * correct answer to a question they are not the authority on.
     */
    EntitlementSource PERMISSIVE = new EntitlementSource() {
        @Override public boolean grantable(Long organizationId, Capability capability) { return true; }
        @Override public boolean revoked(Long organizationId, Capability capability) { return false; }
    };
}
