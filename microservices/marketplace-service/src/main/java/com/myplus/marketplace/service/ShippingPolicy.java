package com.myplus.marketplace.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingsService;

import static com.myplus.marketplace.config.MarketplaceSettingsCatalog.EXPRESS_FEE;
import static com.myplus.marketplace.config.MarketplaceSettingsCatalog.FREE_OVER;
import static com.myplus.marketplace.config.MarketplaceSettingsCatalog.STANDARD_FEE;

/**
 * OMS O3 — what delivery costs, for THIS store.
 *
 * <h3>One resolver, both callers</h3>
 * {@code CheckoutService.quote()} and {@code place()} both come here. They must agree: a quote that shows free
 * delivery and a checkout that charges for it is the same class of defect as the client-supplied total O1
 * removed — two code paths computing the same money and drifting apart.
 *
 * <h3>Why the fee left the enum</h3>
 * {@code ShippingOption} carried literal fees (5.00 / 15.00), so every store on the platform charged the same
 * delivery and a shop that delivers free could not say so. The enum keeps what is structurally true of a method
 * ({@code requiresAddress} — collection needs no address); what it COSTS is per-tenant policy and lives here.
 *
 * <p>Fail-soft by construction: an unset or malformed setting resolves to the catalog default, which reproduces
 * the old literal exactly. A settings typo must never stop a shop taking an order.
 */
@Component
public class ShippingPolicy {

    /** Defaults mirror the literals that were in {@code ShippingOption} before O3 — no behaviour change if unset. */
    static final BigDecimal DEFAULT_STANDARD = new BigDecimal("5.00");
    static final BigDecimal DEFAULT_EXPRESS = new BigDecimal("15.00");
    static final BigDecimal DEFAULT_FREE_OVER = BigDecimal.ZERO;   // 0 = the threshold is off

    /**
     * REQUIRED. It was {@code required = false} first, and that is precisely how O3 shipped inert during
     * development: the catalog, the migration and this resolver all existed, but marketplace had no
     * {@code SettingsStore} bean, so {@code CommonSettingsAutoConfiguration} (@ConditionalOnBean) never created
     * a {@code SettingsService} — and the optional injection turned that into "every store silently keeps the
     * platform default fee" instead of a startup failure. A service that means to be configurable must refuse
     * to start unconfigurable. The null guards below remain for direct construction in unit tests.
     */
    @Autowired
    private SettingsService settingsService;

    /**
     * The delivery fee for this order.
     *
     * <p>The store is a REQUIRED parameter, not read from the security context. A storefront shopper is
     * anonymous — there is no JWT and therefore no current org on the entire public checkout path — so
     * resolving the tenant ambiently returned the catalog default to every real customer while appearing to
     * work in staff-authenticated tests. The org is always known here (it identifies the cart), so it is
     * passed; see {@code SettingsService.effectiveFor}.
     *
     * @param option   the chosen method
     * @param subtotal goods value BEFORE tax and delivery — what the free-delivery threshold is measured against
     * @param org      the store whose policy applies
     */
    public BigDecimal feeFor(ShippingOption option, BigDecimal subtotal, Long org) {
        if (option == null || option == ShippingOption.PICKUP) return BigDecimal.ZERO;   // collection is never charged

        // Free over N, when configured. Measured on the GOODS value, not the grand total: including delivery
        // would make the threshold self-referential (adding the fee could push the order over it and remove the
        // fee), and including tax would move the threshold whenever a tax rate changed.
        BigDecimal freeOver = decimal(org, FREE_OVER, DEFAULT_FREE_OVER);
        if (freeOver.signum() > 0 && subtotal != null && subtotal.compareTo(freeOver) >= 0)
            return BigDecimal.ZERO;   // at the threshold counts as over it — "free over 5000" includes 5000

        return switch (option) {
            case EXPRESS -> decimal(org, EXPRESS_FEE, DEFAULT_EXPRESS);
            case STANDARD -> decimal(org, STANDARD_FEE, DEFAULT_STANDARD);
            default -> BigDecimal.ZERO;
        };
    }

    /** Is cash on delivery accepted by this store? Default true — the behaviour before O3. */
    public boolean codEnabled(Long org) {
        if (settingsService == null) return true;
        return settingsService.getBoolFor(org, com.myplus.marketplace.config.MarketplaceSettingsCatalog.COD_ENABLED);
    }

    private BigDecimal decimal(Long org, String key, BigDecimal fallback) {
        // Unwired settings (a deployment without common-settings, or a unit test) fall back to the default
        // rather than failing — the same reason PartyClient is optional in business-service.
        if (settingsService == null) return fallback;
        BigDecimal v = settingsService.getDecimalFor(org, key, fallback);
        // A negative fee is not a discount; treat it as unset rather than paying the shopper to order.
        return (v == null || v.signum() < 0) ? fallback : v;
    }
}
