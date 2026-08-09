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
                // ─── Sale entry ────────────────────────────────────────────────────────────────
                // The sell screen serves a corner shop, a wholesale distributor and a pharmacy from
                // ONE form, so which fields belong on it is a per-tenant answer, not ours. Every
                // toggle below defaults to TODAY'S behaviour: a tenant that changes nothing sees the
                // screen unchanged. Hiding a field never drops it from the invoice — the control stays
                // in the DOM and keeps submitting (see pos-rowentry.css for why that matters).
                SettingEntry.bool("pos.keyboard.enabled",
                        "Compact one-row sale entry",
                        "Off (default): the sale form stays as it is today, one field per line. "
                                + "On: item, quantity, price and discount sit on a single row above the "
                                + "cart, so a line is entered without scrolling a tall form. Phones and "
                                + "small tablets keep the stacked layout either way.",
                        false, "Sale entry"),
                // UI/UX P2. Fails CLOSED for the same reason as pos.keyboard.enabled: a config-read
                // hiccup must never arm function keys on a till nobody has trained for them, nor make
                // a '*' in a barcode suddenly mean "multiply".
                SettingEntry.bool("pos.keyboard.shortcuts.enabled",
                        "Keyboard shortcuts and quantity scanning",
                        "Off (default): no shortcut keys, and a scanned code is taken literally. "
                                + "On: F2 completes the sale, F3 parks it, F4 opens parked sales, "
                                + "F8 tenders the exact amount and F9 clears the cart (each also on "
                                + "Alt+S/P/R/E/C) — and scanning \"12*code\" adds twelve at once.",
                        false, "Sale entry"),
                // UI/UX P3. Fails CLOSED like its siblings: a config hiccup must not put an unexpected
                // grid of products above the cart on a live till.
                SettingEntry.bool("pos.quickpick.enabled",
                        "Quick-pick tiles for your best sellers",
                        "Off (default). On: your best-selling products appear as tiles above the cart "
                                + "and can be added with Alt+1 to Alt+9 — for goods with no barcode, "
                                + "like loose produce, bakery items or services.",
                        false, "Sale entry"),
                SettingEntry.intOf("pos.quickpick.count",
                        "How many quick-pick tiles to show",
                        "9 by default, which is what Alt+1 to Alt+9 can reach. More tiles are still "
                                + "clickable but have no shortcut key.",
                        9, "Sale entry"),
                SettingEntry.intOf("pos.quickpick.days",
                        "Days of sales history the tiles are based on",
                        "30 by default. A shorter window follows what is selling right now; a longer "
                                + "one is steadier across a slow week.",
                        30, "Sale entry"),
                SettingEntry.bool("pos.entry.showDescription",
                        "Show the item Description field",
                        "On (default). Turn off if your product names already say enough — it is one "
                                + "less field to pass through on every line.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.showBonus",
                        "Show the Bonus (free goods) field",
                        "On (default). Wholesale and distribution use it for \"20 billed, 2 free\"; a "
                                + "retail till almost never does.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.showStock",
                        "Show on-hand stock on the sale line",
                        "On (default): the cashier sees what is in stock as they pick an item. Turn off "
                                + "for a counter where stock levels should not be visible to staff.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.showExpiry",
                        "Show the batch expiry date",
                        "On (default). Essential for pharmacy and food; noise for hardware or apparel.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.priceEditable",
                        "Let the cashier change the selling price",
                        "On (default): the price is pre-filled but can be typed over — normal for "
                                + "wholesale, where every deal is negotiated. Off: the catalog price is "
                                + "fixed at the till, which is what most retail chains want.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.lineDiscountEnabled",
                        "Allow a discount on each line",
                        "On (default). Off: no per-line discount at all — use the invoice-level trade "
                                + "discount instead, or fixed prices only.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.showDiscountType",
                        "Let the cashier choose amount vs percent for a line discount",
                        "On (default). Off: the line discount is always taken as a fixed amount, which "
                                + "removes a dropdown from every line.",
                        true, "Sale entry"),
                SettingEntry.bool("pos.entry.showReceivable",
                        "Show the per-line Receiveable total",
                        "On (default). The cart below already totals the sale, so shops that find it "
                                + "redundant can turn it off.",
                        true, "Sale entry"),
                SettingEntry.intOf("pos.entry.defaultQty",
                        "Default quantity on a new line",
                        "1 (default) suits a retail counter. A wholesaler selling by the carton may "
                                + "prefer a larger starting quantity.",
                        1, "Sale entry"),

                // ─── Customer & credit ─────────────────────────────────────────────────────────
                // Default TRUE because that is what the till does TODAY — main.js's sale handler refuses
                // to submit without a customer ("Customer is mandatory regardless of payment mode").
                // Turning it OFF is the new capability: a retail counter can ring up an anonymous cash
                // sale without typing a name for every customer, which is the single biggest queue cost
                // at a shop that does not run accounts.
                SettingEntry.bool("pos.customer.required",
                        "Require a customer on every sale",
                        "On (default): a sale cannot be completed without choosing or naming a customer "
                                + "— right for wholesale, where every invoice belongs to an account. Off: "
                                + "a walk-in cash sale can be rung up without one, and is recorded against "
                                + "a walk-in name.",
                        true, "Customer & credit"),
                SettingEntry.text("pos.customer.walkInName",
                        "Name to use for a walk-in sale",
                        "Used only when a customer is not required and the cashier leaves it blank. "
                                + "Appears on the invoice, so the sale is still attributable.",
                        "Walk-in Customer", "Customer & credit"),
                SettingEntry.bool("pos.customer.showBalance",
                        "Show the customer's previous balance and credit limit",
                        "On (default): previous balance, new total due and remaining credit appear once "
                                + "a customer is chosen. Off: hide them at a busy retail till.",
                        true, "Customer & credit"),
                SettingEntry.select("pos.customer.defaultMode",
                        "How the cashier picks a customer by default",
                        "Choose from existing customers (default), or type a name and mobile straight "
                                + "onto the sale. The cashier can still switch on any sale.",
                        "select", "Customer & credit",
                        java.util.List.of(
                                new SettingEntry.Option("select", "Choose an existing customer"),
                                new SettingEntry.Option("manual", "Type the name each time"))),

                // ─── Payment ───────────────────────────────────────────────────────────────────
                SettingEntry.select("pos.tender.default",
                        "Payment method selected by default",
                        "Cash (default) suits a retail counter. A distributor invoicing on account will "
                                + "want Credit, so the common case needs no change.",
                        "CASH", "Payment",
                        java.util.List.of(
                                new SettingEntry.Option("CASH", "Cash"),
                                new SettingEntry.Option("CARD", "Card"),
                                new SettingEntry.Option("CREDIT", "Credit (on account)"),
                                new SettingEntry.Option("WALLET", "Wallet"),
                                new SettingEntry.Option("BANK_TRANSFER", "Bank transfer"))),
                SettingEntry.bool("pos.invoice.tradeDiscountEnabled",
                        "Allow an invoice-level trade discount",
                        "On (default): a whole-order concession can be entered at the foot of the sale, "
                                + "separate from per-line discounts. Off: hide it.",
                        true, "Payment"),

                // ─── Workflow ──────────────────────────────────────────────────────────────────
                SettingEntry.bool("pos.park.enabled",
                        "Allow parking a sale to serve the next customer",
                        "On (default): a held sale can be set aside and resumed, so a customer who is "
                                + "still deciding does not hold up the queue.",
                        true, "Workflow"),
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
