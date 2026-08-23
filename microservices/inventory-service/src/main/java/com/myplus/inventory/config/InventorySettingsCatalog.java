package com.myplus.inventory.config;

import java.util.List;

import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;

/**
 * OMS O5a — the stock-hold policies a merchant may configure. inventory-service's FIRST contribution to the
 * shared settings catalog.
 *
 * <p>Adding an entry is all it takes to surface a control: the Configuration screen renders from this catalog
 * and has no knowledge of individual settings.
 */
@Component
public class InventorySettingsCatalog implements SettingsCatalogProvider {

    public static final String HOLD_MINUTES = "inventory.reservation.holdMinutes";

    private static final String GROUP = "Stock holds";

    /** Long enough for a slow checkout, short enough that a leak self-heals within the hour. */
    public static final int DEFAULT_HOLD_MINUTES = 30;

    /** O7 D1c — how long a CONFIRMED ORDER's stock stays promised. */
    public static final String ORDER_HOLD_MINUTES = "inventory.reservation.orderHoldMinutes";

    /**
     * Three days.
     *
     * <p>Not the checkout default, and the difference is the whole point. A distributor confirms an order this
     * afternoon and the van goes out tomorrow morning; under a 30-minute hold the stock would be swept
     * overnight — silently, working exactly as designed — and the order would reach dispatch with nothing
     * reserved. The feature would look implemented and do nothing on any order that waited.
     *
     * <p>Three days covers a normal delivery round with a weekend in it. Longer would mean a forgotten order
     * quietly sterilising stock for a week; shorter would break the ordinary case this exists to serve.
     */
    public static final int DEFAULT_ORDER_HOLD_MINUTES = 3 * 24 * 60;

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.intOf(HOLD_MINUTES,
                        "Release unconfirmed stock holds after (minutes)",
                        "When a sale reserves stock but never completes, the hold is returned after this many "
                                + "minutes so the stock can be sold again. Set to 0 to never release "
                                + "automatically — holds then persist until someone investigates them.",
                        DEFAULT_HOLD_MINUTES, GROUP),
                SettingEntry.intOf(ORDER_HOLD_MINUTES,
                        "Hold stock for a confirmed order for (minutes)",
                        "When an order is approved, its stock is set aside so nothing else can sell it. This "
                                + "is how long that promise lasts if the order is never dispatched. It is "
                                + "deliberately much longer than a till hold — an order confirmed today may "
                                + "not go out until tomorrow. Set to 0 to hold indefinitely.",
                        DEFAULT_ORDER_HOLD_MINUTES, GROUP));
    }
}
