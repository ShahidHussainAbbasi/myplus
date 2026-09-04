package com.myplus.common.audit;

/**
 * E4 — whether the person who caused an audit event was inside the tenant the event belongs to.
 *
 * <h3>Why this is not a role</h3>
 * The obvious design is {@code OWNER · ADMIN · CASHIER · …}, and it is wrong twice over. audit-service does not
 * know roles and never will — they live in auth-service and differ per module — so the value would have to be
 * asserted by every producer and would drift. Worse, a role is not a property of the EVENT: the moment that
 * person is promoted, every historical row describing them becomes false. An audit trail whose past changes
 * when the present does is not a trail.
 *
 * <p>Inside-or-outside, by contrast, is fixed at the moment of the act and is the only distinction the reader
 * actually needs. Who specifically it was is already answerable from {@code userId} and {@code actorEmail}.
 *
 * <h3>The failure this exists to prevent</h3>
 * A platform operator revoking a capability from tenant 44 is not one of tenant 44's staff. Without this axis
 * the trail either hides the change from the customer entirely, or shows it as though a colleague did it —
 * and an owner auditing their own configuration would act on that. A trail that misattributes is worse than
 * one that is missing, because it is believed.
 */
public enum AuditActorType {

    /** Someone inside the tenant the event belongs to. Every row written before E4 is one of these. */
    MEMBER,

    /** MaxTheService platform staff acting ON a customer tenant. The case that required the column. */
    PLATFORM_OPERATOR,

    /** No human: a relay, a scheduled job, a saga compensation. Distinguished so nobody is blamed for it. */
    SYSTEM;

    public String code() {
        return name();
    }
}
