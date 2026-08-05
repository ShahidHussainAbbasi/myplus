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
                // Default ON: this is a safety step, so the safe state is the one you get by doing nothing. The UI
                // also treats an absent key / failed config read as ON for the same reason.
                SettingEntry.bool("pharmacy.interaction.blockSevere",
                        "Require acknowledgement of severe drug interactions",
                        "On (default): a SEVERE interaction between items being dispensed must be acknowledged in a "
                                + "dialog before the dispense proceeds. Off: severe interactions are shown as "
                                + "warnings alongside the others.",
                        true, "Pharmacy"),
                // B2B-P0 (#3). The sell screen already warns per LINE as the cashier types. This is the
                // whole-invoice check at submit, which the per-line one cannot do: an invoice-level discount is
                // applied after the lines are entered, so a sale can still finish at zero or negative margin
                // without any single line looking wrong.
                //
                // Defaults to WARN, and an unreadable value also resolves to WARN (standard C3: a safety flag
                // fails ON). "block" is offered for shops that want it enforced, but is not the default —
                // refusing a sale outright at the counter is the shopkeeper's call, not ours.
                SettingEntry.select("pos.sale.marginPolicy",
                        "When a sale makes no profit",
                        "Checks the WHOLE invoice at Complete Sale, after discounts. Warn (default): the sale is "
                                + "recorded and the cashier is told. Block: the sale is refused. Off: no check. "
                                + "Lines with no recorded cost (legacy sales, never-purchased products) are "
                                + "excluded from the calculation rather than counted as pure profit.",
                        "warn", "Point of Sale",
                        List.of(new SettingEntry.Option("off", "Off — no check"),
                                new SettingEntry.Option("warn", "Warn (default) — record it and tell the cashier"),
                                new SettingEntry.Option("block", "Block — refuse the sale"))),
                // B2B-P1 (#9) — the credit limit guard, customer side.
                //
                // WARN here means TAKE CONFIRMATION, not "record it and mention it afterwards": the sale is
                // held, the cashier is asked, and nothing is written unless they accept. A note after the
                // money has moved is not consent — undoing it would mean a void.
                //
                // Safe as a default because the check is INERT without a limit: every existing customer has
                // credit_limit NULL, so this fires only for an account an owner deliberately gave a limit.
                SettingEntry.select("pos.sale.creditLimitPolicy",
                        "When a sale would exceed the customer's credit limit",
                        "Compares what the customer already owes plus the unpaid part of this sale against "
                                + "their credit limit. Warn (default): the cashier is asked to confirm before "
                                + "anything is recorded. Block: the sale is refused and cannot be confirmed "
                                + "past. Off: no check. Customers with no credit limit set are never checked.",
                        "warn", "Point of Sale",
                        List.of(new SettingEntry.Option("off", "Off — no check"),
                                new SettingEntry.Option("warn", "Warn (default) — ask the cashier to confirm"),
                                new SettingEntry.Option("block", "Block — refuse the sale"))),
                // B2B-P1 (#9) — the supplier side. Same rule, opposite direction: this caps what WE owe.
                SettingEntry.select("pos.purchase.creditLimitPolicy",
                        "When a purchase would exceed the supplier's credit limit",
                        "Compares what you already owe this supplier plus the unpaid part of this bill "
                                + "against their credit limit. Warn (default): you are asked to confirm before "
                                + "anything is recorded. Block: the purchase is refused. Off: no check. "
                                + "Suppliers with no credit limit set are never checked.",
                        "warn", "Purchasing",
                        List.of(new SettingEntry.Option("off", "Off — no check"),
                                new SettingEntry.Option("warn", "Warn (default) — ask before recording"),
                                new SettingEntry.Option("block", "Block — refuse the purchase"))),
                // B2B-P0 (#13). OFF by default, deliberately: this prints on documents our customers hand to
                // THEIR customers. Enabled for trial accounts, or by a paying customer's own choice.
                SettingEntry.bool("pos.receipt.showPromo",
                        "Show \"Powered by MaxTheService\" on receipts and statements",
                        "Off (default): nothing is added to your documents. On: a small footer line naming "
                                + "MaxTheService is printed on receipts, invoices and statements. Off by default so "
                                + "no paying customer is surprised to find it on their own invoices.",
                        false, "Receipts"),

                // ---------------------------------------------------------------- B2B Phase 3g: documents
                //
                // The LETTERHEAD. Before 3g the document header printed our own brand ("MyPlus Pharmacy") on
                // every tenant's invoices. These fill it in; each falls back to the store the invoice was
                // raised at, so an owner who sets nothing still stops printing our name on their paperwork.
                SettingEntry.text("pos.document.businessName",
                        "Business name printed on documents",
                        "The name at the top of your invoices and receipts. Leave blank to use the store's name.",
                        "", "Documents"),
                SettingEntry.text("pos.document.addressLine1",
                        "Address line 1",
                        "Printed under the business name. Leave blank to use the store's address.",
                        "", "Documents"),
                SettingEntry.text("pos.document.addressLine2",
                        "Address line 2",
                        "An optional second address line — area, city, postcode.",
                        "", "Documents"),
                SettingEntry.text("pos.document.phone",
                        "Phone printed on documents",
                        "Printed beside the address. Leave blank to use the store's phone number.",
                        "", "Documents"),
                SettingEntry.text("pos.document.logoUrl",
                        "Logo image URL",
                        "An optional logo printed above the business name. Leave blank for no logo.",
                        "", "Documents"),
                // The SELLER's licence. A setting, not a column: a business has one licence, not one per
                // invoice. The BUYER's licence is a field on the customer record.
                SettingEntry.text("pos.document.licenseNo",
                        "Your licence number",
                        "Your trade or drug licence, printed in the invoice header. Pharmacies and "
                                + "distributors are usually required to show it. Leave blank to omit.",
                        "", "Documents"),
                SettingEntry.text("pos.document.licenseExpiry",
                        "Your licence expiry",
                        "Printed beside the licence number. Leave blank to omit.",
                        "", "Documents"),
                // LAYOUT. Default 'auto' is the rule the rest of B2B/B2C already follows: the BUYER decides.
                // A trade account books an invoice; a walk-in gets a till slip. 'thermal'/'a4' are for a shop
                // that wants one format for everything.
                SettingEntry.select("pos.document.layoutMode",
                        "Document format",
                        "Automatic (default): trade customers (Retailer/Wholesale) get a full A4 invoice and "
                                + "walk-in customers get an 80mm till receipt. Thermal: always print the 80mm "
                                + "receipt. A4: always print the full-page invoice.",
                        "auto", "Documents",
                        List.of(new SettingEntry.Option("auto", "Automatic (default) — the customer type decides"),
                                new SettingEntry.Option("thermal", "Always 80mm thermal receipt"),
                                new SettingEntry.Option("a4", "Always A4 invoice"))),
                SettingEntry.text("pos.document.currencySymbol",
                        "Currency symbol on documents",
                        "Printed before the grand total, e.g. \"Rs.\" or \"$\".",
                        "Rs.", "Documents"),
                SettingEntry.text("pos.document.currencyWord",
                        "Currency name in words",
                        "Used by the amount-in-words line, e.g. \"Rupees\" or \"Dollars\".",
                        "Rupees", "Documents"),
                SettingEntry.text("pos.document.currencyFraction",
                        "Fractional currency name",
                        "The sub-unit used in words, e.g. \"Paisa\" or \"Cents\".",
                        "Paisa", "Documents"),
                // Currently English-only by design — see D-3 in the slice doc. An amount in words is a
                // legally meaningful figure, so an unverified translation is worse than printing digits.
                SettingEntry.bool("pos.document.amountInWords",
                        "Print the total in words",
                        "On (default): the invoice total is also written in words, which many trade buyers "
                                + "require. Currently produced in English only; other languages print the "
                                + "figure alone rather than an unverified translation.",
                        true, "Documents"),
                SettingEntry.text("pos.document.footerText",
                        "Footer line on documents",
                        "Replaces the default \"Thank you for your business\". Leave blank for the default.",
                        "", "Documents")
        );
        // NOTE: an earlier "pos.sale.negativeStockAllowed" toggle was removed deliberately. Most "Insufficient
        // stock" cases are expired/held batches excluded from sellable (fix the data — add a fresh batch / correct
        // expiry), not a true shortage; deliberate overselling into negative inventory is not a policy this system
        // offers. Don't re-add a toggle without also building the cross-service oversell path behind it.
    }
}
