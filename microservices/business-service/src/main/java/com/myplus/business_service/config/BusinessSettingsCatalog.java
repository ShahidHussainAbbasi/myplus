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
                SettingEntry.bool("pos.barcode.enabled",
                        "Barcode scanning",
                        "On (default): the sell screen shows a scan box and the product form a Barcode field. "
                                + "Off: both are hidden for shops that don't use barcodes.",
                        true, "Point of Sale"),
                SettingEntry.bool("pos.receipt.autoPrint",
                        "Auto-print receipt after a sale",
                        "On (default): the receipt opens to print automatically when a sale is completed. "
                                + "Off: no auto-print — reprint any time from the sale's Print button.",
                        true, "Receipts"),
                // Pharmacy (review B1). Inert for a non-pharmacy tenant: no product of theirs carries the flag.
                SettingEntry.bool("pharmacy.rx.requirePrescription",
                        "Require a prescription for prescription-only medicines",
                        "On (default): a sale containing a medicine flagged 'prescription required' is refused "
                                + "unless it was started from a prescription (Dispense). Off: the flag is advisory "
                                + "only. Flags are set per medicine on the Clinical & Safety screen.",
                        true, "Pharmacy"),
                SettingEntry.bool("pharmacy.interaction.blockSevere",
                        "Require acknowledgement of severe drug interactions",
                        "On: a SEVERE interaction between items being dispensed must be acknowledged before the "
                                + "dispense proceeds. Off (default): interactions are shown as warnings.",
                        false, "Pharmacy")
        );
        // NOTE: an earlier "pos.sale.negativeStockAllowed" toggle was removed deliberately. Most "Insufficient
        // stock" cases are expired/held batches excluded from sellable (fix the data — add a fresh batch / correct
        // expiry), not a true shortage; deliberate overselling into negative inventory is not a policy this system
        // offers. Don't re-add a toggle without also building the cross-service oversell path behind it.
    }
}
