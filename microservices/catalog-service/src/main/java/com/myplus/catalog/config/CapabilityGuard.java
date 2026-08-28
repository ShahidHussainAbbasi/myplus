package com.myplus.catalog.config;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * C3d — refuse a configuration change the tenant's capabilities do not allow.
 *
 * <h3>Why catalog-service asks the TOKEN and not a settings store</h3>
 * catalog-service holds no {@code SettingsStore}, so {@code CapabilityService} is not on its classpath. Since
 * C3c a tenant's capabilities travel in the JWT, so the answer is already in the request — and adding a
 * settings table here purely to ask a question the token answers would be a schema created for nothing.
 *
 * <h3>One class, because two services in this module were about to grow their own copy</h3>
 * {@code ProductService} guards the per-product tracking and clinical flags; {@code PriceRuleService} guards
 * dealer pricing. Same rule, same wording, same permissive-when-unknown decision — three properties that would
 * drift the first time one of them was edited alone.
 *
 * <h3>Permissive when capabilities are unresolved, and that is a stated limit</h3>
 * A token minted before C3c carries no capability claim. Refusing then would break tenants holding older
 * tokens for a reason they could neither see nor fix, and the gap closes by itself on the next refresh.
 *
 * <p>So this is <b>not</b> {@code assertEnabled}, which fails CLOSED and guards stock, ledger and tax. Every
 * caller here is a CONFIGURATION write, where being wrong means a policy an admin set that the tills then
 * decline to honour — visible, reversible, and self-correcting.
 */
public final class CapabilityGuard {

    private CapabilityGuard() {}

    /**
     * Refuse unless the tenant has the capability.
     *
     * @param capabilityCode short code, e.g. {@code dealerPricing} — the same code {@code [data-capability]}
     *                       carries in the markup and the JWT claim carries on the wire
     * @param message        owner-facing reason. <b>Never names the settings key</b>: a refusal that describes
     *                       the tenant's configuration is an information disclosure, the same rule the
     *                       anti-IDOR reads follow.
     */
    public static void require(String capabilityCode, String message) {
        if (!CurrentUser.capabilityAllowed(capabilityCode)) {
            throw new ValidationException(message);
        }
    }

    /**
     * Refuse only when a flag is being switched ON.
     *
     * <p>Turning a policy OFF must stay possible after a capability is withdrawn, or a product is stuck
     * requiring something the tenant is no longer permitted to record — unsellable, with no way back except a
     * DBA. {@code null} means "leave alone" and is never a change worth refusing.
     */
    public static void requireIfSetting(Boolean beingSet, String capabilityCode, String message) {
        if (!Boolean.TRUE.equals(beingSet)) return;
        require(capabilityCode, message);
    }
}
