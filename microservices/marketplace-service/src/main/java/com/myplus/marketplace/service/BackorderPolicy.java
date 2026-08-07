package com.myplus.marketplace.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.common.settings.SettingsService;
import com.myplus.marketplace.config.MarketplaceSettingsCatalog;

import lombok.RequiredArgsConstructor;

/**
 * OMS O5c — may this shop accept what it cannot fill, and how much of this order can it fill?
 *
 * <h3>One resolver, both callers</h3>
 * The QUOTE (what the shopper is told before committing) and the CHECKOUT (what is invoiced) must agree. A quote
 * that promises everything and a checkout that backorders half is the same class of defect as O5b's header that
 * could disagree with its parcels, and O3's quote that could disagree with the charge.
 *
 * <h3>Fails closed on policy, open on availability</h3>
 * A settings hiccup must never silently start accepting unfillable orders, so {@link #allowed} defaults to
 * false on error. An inventory read failure does the opposite: it returns no split, the order proceeds unchanged
 * and the reserve decides — a shop must not stop taking orders because a stock query timed out.
 */
@Component
@RequiredArgsConstructor
public class BackorderPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(BackorderPolicy.class);

    private final InventoryClient inventoryClient;
    private final SettingsService settingsService;

    /** Is this shop willing to accept an order it cannot fill today? Default OFF — a commercial decision. */
    public boolean allowed(Long org) {
        try {
            return settingsService != null
                    && settingsService.getBoolFor(org, MarketplaceSettingsCatalog.BACKORDER_ALLOWED);
        } catch (RuntimeException e) {
            LOG.warn("Backorder policy unreadable for org {} — treating as OFF", org, e);
            return false;
        }
    }

    /**
     * May an order be accepted when NONE of it can be filled today?
     *
     * <p>On (default) is the correct-accounting position: no invoice is raised until goods are dispatched, so
     * the books only ever recognise what was delivered. Off restricts backorders to orders that can be PARTLY
     * filled, which avoids creating an order with no invoice behind it at the cost of refusing the commonest
     * backorder of all.
     */
    public boolean acceptFullShortfall(Long org) {
        try {
            return settingsService == null
                    || settingsService.getBoolFor(org, MarketplaceSettingsCatalog.BACKORDER_FULL_SHORTFALL);
        } catch (RuntimeException e) {
            LOG.warn("Full-shortfall policy unreadable for org {} — allowing (matches the default)", org, e);
            return true;
        }
    }

    /** How far ahead a backordered order is promised. Floored at 1: "promised today" is not a promise. */
    public int promiseDays(Long org) {
        int days = settingsService == null
                ? MarketplaceSettingsCatalog.DEFAULT_PROMISE_DAYS
                : settingsService.getIntFor(org, MarketplaceSettingsCatalog.BACKORDER_PROMISE_DAYS,
                        MarketplaceSettingsCatalog.DEFAULT_PROMISE_DAYS);
        return Math.max(1, days);
    }

    /** The date to promise a shortfall accepted today. */
    public LocalDate promisedDate(Long org) {
        return LocalDate.now().plusDays(promiseDays(org));
    }

    /**
     * Split {@code requested} against sellable stock, or {@code null} when backorders are off, nothing is
     * requested, the availability read failed, or there is no shortfall at all.
     *
     * <p>Returning null for "no shortfall" keeps the overwhelmingly common case free of behaviour: one extra
     * inventory read and nothing else changes.
     *
     * @param runAs runs the inventory call under the store's identity (the caller owns that context)
     */
    public BackorderSplit.Result splitFor(Long org, Map<Long, Integer> requested) {
        if (!allowed(org) || requested == null || requested.isEmpty()) return null;

        Map<Long, Float> sellable = new HashMap<>();
        try {
            Map<Long, Map<String, Float>> detail = readSellableAsStore(org);
            if (detail != null)
                // SELLABLE, not on-hand: on-hand includes expired batches and stock a checkout in flight is
                // holding, so measuring against it would promise goods that cannot be picked.
                detail.forEach((pid, m) -> sellable.put(pid, m == null ? 0f : m.getOrDefault("sellable", 0f)));
        } catch (RuntimeException readFailed) {
            LOG.warn("Backorder split skipped for org {} — sellable read failed; the reserve decides", org, readFailed);
            return null;
        }

        BackorderSplit.Result result = BackorderSplit.split(requested, sellable);
        return result.hasBackorder() ? result : null;
    }

    /**
     * Read sellable stock AS THE STORE.
     *
     * <p>The identity wrapper lives here, not at the call sites, because one of those call sites is the public
     * QUOTE — which is anonymous, exactly like O3's storefront path. The first cut left the wrapper to the
     * caller: the checkout remembered it, the quote did not, and the quote silently warned nobody while the
     * checkout backordered correctly. A shopper being told one thing and charged another is the failure this
     * class exists to prevent, so the identity is no longer something a caller can forget.
     *
     * <p>{@code X-Org-Id}/{@code X-User-Id} are stamped on the outbound request; without them inventory sees an
     * unauthenticated call and the read fails.
     */
    Map<Long, Map<String, Float>> readSellableAsStore(Long org) {
        java.util.concurrent.atomic.AtomicReference<Map<Long, Map<String, Float>>> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.myplus.common.security.GatewayIdentityForwarding.runAs(
                STOREFRONT_USER, org, () -> out.set(inventoryClient.getStockLevelDetail()));
        return out.get();
    }

    /** The synthetic principal a storefront-originated inventory read runs under (mirrors OrderService). */
    private static final Long STOREFRONT_USER = 0L;
}
