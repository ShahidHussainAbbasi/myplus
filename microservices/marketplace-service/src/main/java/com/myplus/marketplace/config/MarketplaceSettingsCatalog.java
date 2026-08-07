package com.myplus.marketplace.config;

import java.util.List;

import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;

/**
 * OMS O3 — the order/checkout policies a store owner may configure. marketplace-service's FIRST contribution to
 * the shared settings catalog; before this it had no {@code common-settings} consumer at all.
 *
 * <p>Every entry here replaces something that was a literal in code, so every store on the platform behaved
 * identically and could only be changed by a redeploy for all tenants at once. The defaults reproduce exactly
 * what those literals were, so an org that never opens the Configuration screen sees no change in behaviour.
 *
 * <p>Adding an entry is all it takes to surface a toggle — the screen renders from this catalog and has no
 * knowledge of individual settings.
 */
@Component
public class MarketplaceSettingsCatalog implements SettingsCatalogProvider {

    public static final String STANDARD_FEE = "order.shipping.standardFee";
    public static final String EXPRESS_FEE = "order.shipping.expressFee";
    public static final String FREE_OVER = "order.shipping.freeOverAmount";
    public static final String COD_ENABLED = "order.payment.codEnabled";

    /** OMS O5c — may this shop accept an order it cannot fill today? */
    public static final String BACKORDER_ALLOWED = "order.backorder.allowed";
    /** OMS O5c — how far ahead a backordered order is promised. */
    public static final String BACKORDER_PROMISE_DAYS = "order.backorder.promiseDays";
    public static final int DEFAULT_PROMISE_DAYS = 7;
    /** OMS O5c — accept an order when NOTHING is available, not just when part is? */
    public static final String BACKORDER_FULL_SHORTFALL = "order.backorder.acceptFullShortfall";

    private static final String GROUP = "Online orders";

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.money(STANDARD_FEE,
                        "Standard delivery fee",
                        "Charged on a storefront order with Standard delivery. Was fixed at 5.00 for every store "
                                + "on the platform until this became configurable.",
                        "5.00", GROUP),
                SettingEntry.money(EXPRESS_FEE,
                        "Express delivery fee",
                        "Charged on a storefront order with Express delivery.",
                        "15.00", GROUP),
                SettingEntry.money(FREE_OVER,
                        "Free delivery over",
                        "Orders at or above this value ship free, whichever method the shopper picks. "
                                + "Leave at 0 to always charge the fees above.",
                        "0", GROUP),
                SettingEntry.bool(COD_ENABLED,
                        "Accept cash on delivery",
                        "On (default): shoppers may choose Cash on Delivery. Off: only card checkout is accepted "
                                + "and a COD order is refused by the server, not merely hidden.",
                        true, GROUP),
                // OMS O5c. Default OFF: accepting orders you cannot fill is a commercial decision with real
                // consequences (a customer waiting on goods that may never arrive), not a sensible default.
                // Off reproduces exactly today's behaviour — the checkout is refused.
                SettingEntry.bool(BACKORDER_ALLOWED,
                        "Accept orders for out-of-stock items (backorders)",
                        "Off (default): a checkout is refused when stock is short, as now. On: the order is "
                                + "accepted, the part you can fill is invoiced and dispatched, and the rest is "
                                + "recorded as owed until stock arrives. The shopper is told before they commit.",
                        false, GROUP),
                // The order in which these two read matters: this one only applies once backorders are on.
                SettingEntry.bool(BACKORDER_FULL_SHORTFALL,
                        "Accept orders when nothing is in stock",
                        "On (default): an order can be accepted even when none of it can be filled today — no "
                                + "invoice is raised until goods are dispatched, so you only ever bill for what "
                                + "you deliver. Off: only orders you can PARTLY fill are accepted, and a "
                                + "completely out-of-stock item is refused at checkout.",
                        true, GROUP),
                SettingEntry.intOf(BACKORDER_PROMISE_DAYS,
                        "Promise backordered items within (days)",
                        "How far ahead to promise the outstanding part of a backordered order. Shown to the "
                                + "shopper at checkout and used to flag late orders in the back office.",
                        DEFAULT_PROMISE_DAYS, GROUP));
    }
}
