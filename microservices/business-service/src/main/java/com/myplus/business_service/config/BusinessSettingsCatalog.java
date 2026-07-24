package com.myplus.business_service.config;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The POS/commerce settings an owner may configure — this service's contribution to the shared catalog. Each
 * entry is one Configuration-screen toggle; behaviour reads {@code settingsService.getBool(key)}. Adding a
 * policy here is all it takes to surface it (no schema change). Behaviour-wiring of each flag lands
 * incrementally in the code path it governs.
 */
@Component
public class BusinessSettingsCatalog implements SettingsCatalogProvider {

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.bool("pos.receipt.showTaxBreakdown",
                        "Show tax breakdown on receipts",
                        "On (default): the receipt lists tax per rate. Off: a single tax total.",
                        true, "Receipts"),
                SettingEntry.bool("pos.sale.negativeStockAllowed",
                        "Allow selling into negative stock",
                        "Off (default): a sale is refused when stock is insufficient. On: the sale proceeds and "
                                + "stock may go negative (correct later).",
                        false, "Sales")
        );
    }
}
