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

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.intOf(HOLD_MINUTES,
                        "Release unconfirmed stock holds after (minutes)",
                        "When a sale reserves stock but never completes, the hold is returned after this many "
                                + "minutes so the stock can be sold again. Set to 0 to never release "
                                + "automatically — holds then persist until someone investigates them.",
                        DEFAULT_HOLD_MINUTES, GROUP));
    }
}
