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
                // Default ON (changed from off): the compact row is now the standard sale screen, not an
                // opt-in. A tenant who prefers the tall one-field-per-line form switches it off here.
                // NOTE this is the effective default for anyone with NO stored override — so it changes the
                // sale screen for every existing tenant that never touched the setting.
                // P7.2 — keyboard navigation on the REGISTRATION forms (Customer, Vendor, Product,
                // Company, payments). Separate from the sale-screen keys above: a shop can want a
                // keyboard-driven till and ordinary mouse-driven data entry, or the reverse.
                //
                // Both fail OPEN, unlike the POS flags. The reasoning inverts because the risk does:
                // an unexpected function key on a live till can complete a sale, whereas Enter moving
                // to the next box cannot do anything a Tab press could not. Losing the feature to a
                // config hiccup would be the worse outcome here.
                SettingEntry.bool("ui.keyboard.formNav.enabled",
                        "Keyboard navigation on registration forms",
                        "On (default): Enter moves to the next field on the Customer, Vendor, Product, "
                                + "Company and payment forms, Shift+Enter goes back, and Esc closes the form. "
                                + "Off: those forms behave as before, with Tab only. Phones and small "
                                + "tablets are unaffected either way.",
                        true, "Data entry"),
                SettingEntry.bool("ui.keyboard.enterSubmits",
                        "Enter saves on the last field",
                        "On (default): pressing Enter on the last field of a registration form saves it, "
                                + "so a record is completed without reaching for the mouse. Off: only "
                                + "Ctrl+Enter saves, which suits teams worried that a stray Enter could "
                                + "save a half-typed record. Ctrl+Enter always saves either way.",
                        true, "Data entry"),
                SettingEntry.bool("pos.keyboard.enabled",
                        "Compact one-row sale entry",
                        "On (default): item, quantity, price and discount sit on a single row above the "
                                + "cart, so a line is entered without scrolling a tall form, and Enter moves "
                                + "between the fields. Off: the older layout with one field per line. "
                                + "Phones and small tablets keep the stacked layout either way.",
                        true, "Sale entry"),
                // UI/UX P2. Fails CLOSED for the same reason as pos.keyboard.enabled: a config-read
                // hiccup must never arm function keys on a till nobody has trained for them, nor make
                // a '*' in a barcode suddenly mean "multiply".
                /*
                 * #23 — stock checking at ITEM SELECTION, off by default.
                 *
                 * At a counter the customer has already collected the goods: they are physically on the
                 * counter before the cashier types anything. A stock check at selection therefore prevents
                 * nothing — the goods are leaving either way — while costing a round trip per line and, when
                 * it fired, showing "No stock available, please purchase this item" AND RESETTING the item
                 * picker, throwing away the cashier's entry in front of the customer.
                 *
                 * It also fires wrongly by design: sellable EXCLUDES expired and quarantined batches, so a
                 * product with 16 on hand can read 0 sellable while the customer is holding one.
                 *
                 * Refusing does not prevent the sale. It prevents RECORDING it — which leaves the revenue
                 * unbooked and the stock still wrong, a strictly worse outcome.
                 *
                 * DEFAULT FALSE, deliberately, and note this is the opposite of the fail-closed convention
                 * used by the keyboard settings above. Those fail closed because a config hiccup must not arm
                 * behaviour a till was not trained for. Here a config hiccup must not BLOCK a sale, so the
                 * safe direction is inverted: the till keeps selling.
                 *
                 * ON is for shops that promise future delivery — B2B orders and pre-sales — where the stock
                 * position is a commitment rather than a description of what is already on the counter.
                 *
                 * The SUBMIT-time FEFO reservation is unaffected either way. This governs the pre-fill guard,
                 * not the rule that allocates stock.
                 */
                SettingEntry.bool("pos.stock.validateOnSelect",
                        "Check stock when an item is selected",
                        "Off (default): the till never blocks on stock while items are being entered — the "
                                + "sellable count is shown for information and the cashier carries on. Suits a "
                                + "counter, where the goods are already in the customer's hands. On: selecting "
                                + "an item with no sellable stock is refused at entry — for a shop that sells "
                                + "against a stock position it is promising to fulfil later.",
                        false, "Sale entry"),
                SettingEntry.bool("pos.sale.confirmOnComplete",
                        "Ask before completing a sale",
                        "On (default): Complete Sale asks first, and offers to PARK the sale instead — for "
                                + "the customer who has left their wallet in the car. Off: the sale completes "
                                + "on the first press, which a busy counter may prefer. Enter answers the "
                                + "question, so keyboard entry costs one extra keystroke either way.",
                        true, "Sale entry"),
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
                /*
                 * WHICH BUSINESS IS THIS? — one choice instead of nine.
                 *
                 * The nine `pos.entry.*` switches below are correct and stay. What they are not is a
                 * QUESTION a shopkeeper can answer: nine independent booleans describe 512 possible tills,
                 * none of which anyone designed, and every one of them defaults ON — so a corner shop opens
                 * the busiest screen in the product and has to switch nine things off to reach the four
                 * fields it actually uses.
                 *
                 * A preset answers the question they can answer — "I run a pharmacy" — and writes the nine.
                 *
                 * DESIGN PATTERN. This is Strategy with an explicit escape hatch: the preset supplies a
                 * named set of defaults, the individual switches remain and OVERRIDE it. That ordering is
                 * the whole contract — `posFieldsFor()` applies the preset first and the tenant's own
                 * overrides second, so choosing a preset never destroys a deliberate choice, and a shop
                 * that wants pharmacy-plus-bonus is not forced to pick the nearest wrong answer.
                 *
                 * CUSTOM is not a fallback for "unrecognised". It is the honest name for "leave the nine
                 * exactly as they are", which is what every existing tenant needs on the deploy that
                 * introduces this: their configured screen must not move because a new setting appeared.
                 * Hence it is also the DEFAULT.
                 *
                 * The column ORDER is not configurable and deliberately so. A till is read by position; a
                 * shop that can reorder its own columns has a screen no two of its staff read the same way,
                 * and no support call can ever be answered generically. Presets choose which fields are
                 * PRESENT, never where they sit.
                 */
                SettingEntry.select("pos.entry.preset",
                        "What kind of shop is this?",
                        "Sets the sale line to the fields that trade normally needs — one choice instead "
                                + "of nine switches. The individual switches below still win wherever you "
                                + "have set one, so a preset never overwrites a decision you made on "
                                + "purpose. Leave on Custom to keep exactly what you have today.",
                        "CUSTOM", "Sale entry",
                        java.util.List.of(
                                new SettingEntry.Option("CUSTOM",
                                        "Custom — use the individual switches below"),
                                new SettingEntry.Option("RETAIL",
                                        "Retail / general store — item, quantity, price"),
                                new SettingEntry.Option("PHARMACY",
                                        "Pharmacy / veterinary — adds batch and expiry"),
                                new SettingEntry.Option("DISTRIBUTION",
                                        "Distribution / wholesale — adds bonus and line discount"),
                                new SettingEntry.Option("RESTAURANT",
                                        "Restaurant / counter service — item and quantity only"))),

                /*
                 * THE SALE LINE AS ONE ROW — and, until now, a feature no tenant could reach.
                 *
                 * `pos.entry.compactRow` has been read since P1 (business.js sets posRowLayoutEnabled from
                 * it) but was never DECLARED here, so it never appeared on the Configuration screen and no
                 * shop could switch it on. The Configuration screen renders from this catalogue and knows
                 * nothing about individual settings, which is its strength — and it is why an undeclared key
                 * is invisible rather than merely undocumented. The only org that had it on was one a test
                 * had written the row for directly.
                 *
                 * That is C1 read backwards. The rule here is "a toggle that changes nothing is worse than
                 * no toggle"; this was the mirror image — a change with no toggle.
                 *
                 * DEFAULT ON. Every other pos.entry.* setting defaults true to preserve the behaviour it
                 * was retrofitted onto; this one defaults true because the row IS the intended till. The
                 * stacked form remains one switch away for a shop that prefers it.
                 *
                 * Note the browser still fails CLOSED on a settings FAILURE — an absent or unreadable
                 * response yields the stacked layout, because re-laying-out a till mid-sale because a
                 * config call hiccuped is the surprise worth avoiding whichever way the default points.
                 */
                SettingEntry.bool("pos.entry.compactRow",
                        "Enter each sale line as one row",
                        "On (default). The item, quantity, price and discount sit side by side under a "
                                + "single header, the way a till reads, instead of stacked one per line. "
                                + "Turn off for the taller form, which some operators prefer on a small "
                                + "screen. Which FIELDS appear is set separately below.",
                        true, "Sale entry"),
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

                // ─── Selling on installment (INST-1) ───────────────────────────────────────────
                //
                // OFF by default, and that is a decision rather than caution: a default is not a decision,
                // and an existing shop that never asked for financing must see its sale screen unchanged.
                //
                // ⚠ EVERY select VALUE BELOW IS LOWERCASE. SettingsService.getChoice lower-cases the stored
                // value before matching the allowed set and SILENTLY returns the fallback otherwise — so a
                // value of "MONTHLY" here would be saved by the owner, read back as the default forever, and
                // log nothing at all. The allowed values live as constants on the classes that read them
                // (Frequency.SettingValue, InstallmentPlanService.ORDER_*) so the catalog and the reader
                // cannot drift apart.
                SettingEntry.bool("pos.installment.enabled",
                        "Sell on installment",
                        "Off (default). On: the sale screen offers a down payment and a dated payment "
                                + "schedule for one high-value item — a handset, an appliance, a machine. "
                                + "Everything else about the counter is unchanged.",
                        false, "Installments"),
                SettingEntry.intOf("pos.installment.defaultCount",
                        "How many payments by default",
                        "6 by default. The cashier can change it on any sale.",
                        6, "Installments"),
                SettingEntry.select("pos.installment.frequency",
                        "How often payments fall due",
                        "Monthly by default. Monthly dates follow the calendar, so a plan starting on the "
                                + "31st falls due on the 30th or 28th in shorter months rather than drifting "
                                + "earlier each time.",
                        "monthly", "Installments",
                        java.util.List.of(
                                new SettingEntry.Option("monthly", "Monthly"),
                                new SettingEntry.Option("fortnightly", "Every two weeks"),
                                new SettingEntry.Option("weekly", "Weekly"))),
                SettingEntry.intOf("pos.installment.minDownPaymentPct",
                        "Smallest down payment (%)",
                        "0 by default — no minimum. Set 20 to require a fifth of the price at the counter.",
                        0, "Installments"),
                SettingEntry.intOf("pos.installment.maxOpenPlansPerCustomer",
                        "How many open plans one customer may hold",
                        "1 by default. Raise it for a shop that finances more than one item per household.",
                        1, "Installments"),
                SettingEntry.intOf("pos.installment.blockIfOverdueDays",
                        "Refuse a new plan while a payment is this many days late",
                        "0 (off) by default. Set 30 to stop a customer taking a second plan while an "
                                + "existing payment is a month behind.",
                        0, "Installments"),
                // ── INST-5a: serialised units and repossession ──────────────────────────────────────
                // Five settings, and the discipline is: CONFIGURATION where TENANTS differ, PARAMETERS where
                // TRANSACTIONS differ. Whether a shop repossesses at all is a tenant policy; whether THIS
                // handset came back smashed is a fact about one repossession and is passed with the request.
                // A setting for the second kind is how a screen ends up with thirty toggles nobody reads.
                SettingEntry.bool("pos.installment.serialRequired",
                        "Require an IMEI or serial number on a financed sale",
                        "Off by default. On: a plan cannot be sold without a serial, and the same serial "
                                + "cannot be on two live plans at once. A mobile or electronics shop wants "
                                + "this on; a shop financing furniture has nothing to type in it.",
                        false, "Installments"),
                SettingEntry.bool("pos.installment.repossession.enabled",
                        "Allow a financed item to be repossessed",
                        "Off by default. On: a defaulted plan can be closed by taking the item back — the "
                                + "unpaid balance is credited off and the unit returns to stock. Money already "
                                + "paid is kept.",
                        false, "Installments"),
                SettingEntry.intOf("pos.installment.repossession.minOverdueDays",
                        "Days late before an item may be repossessed",
                        "30 by default. Stops a customer who is three days late having their phone taken. "
                                + "Set 0 to allow repossession as soon as anything is overdue.",
                        30, "Installments"),
                SettingEntry.intOf("pos.installment.repossession.protectedGoodsPct",
                        "Refuse repossession once this much of the price is paid (%)",
                        "0 (off) by default. Consumer-credit rules in many markets make goods PROTECTED once "
                                + "a share of the price has been paid — commonly two thirds — after which the "
                                + "goods cannot be taken back without a court order. Set 66 to enforce that.",
                        0, "Installments"),
                SettingEntry.bool("pos.installment.repossession.writeOffBalance",
                        "Taking the item back settles the debt",
                        "On by default, which is the usual practice: the item comes back and the remaining "
                                + "balance is credited off. Off: the balance stays owing as an ordinary "
                                + "receivable after the item is recovered — a deficiency claim, which needs "
                                + "the paperwork to support it.",
                        true, "Installments"),
                SettingEntry.bool("pos.installment.remind.enabled",
                        "Build a collections worklist of who to chase",
                        "Off by default — a default is not a decision. On: a background scan records each "
                                + "instalment as it falls due or falls behind, so the shop gets a list of who "
                                + "to ring with the numbers already on it. Nothing is sent to the customer; "
                                + "this is a list for the counter, not a message.",
                        false, "Installments"),
                SettingEntry.intOf("pos.installment.remind.beforeDays",
                        "Days before the due date to start chasing",
                        "3 by default. A courtesy call this many days out; after the date it becomes a "
                                + "collection call. Set 0 to list nothing until a payment is actually late.",
                        3, "Installments"),
                SettingEntry.bool("pos.installment.requireCnic",
                        "Require the customer's CNIC",
                        "Off by default. On: a plan cannot be sold to a customer with no CNIC on file — "
                                + "the identity a financed sale is chased on.",
                        false, "Installments"),
                SettingEntry.select("pos.installment.allocationOrder",
                        "When a customer owes both a plan and invoices, clear which first",
                        "By due date (default) puts the money on whatever is owed soonest. Choose plans "
                                + "first if you are chasing a schedule, or invoices first if you are "
                                + "clearing the oldest paper at month end.",
                        "by-due-date", "Installments",
                        java.util.List.of(
                                new SettingEntry.Option("by-due-date", "Whatever is due soonest"),
                                new SettingEntry.Option("installments-first", "Installment plans first"),
                                new SettingEntry.Option("invoices-first", "Ordinary invoices first"))),
                SettingEntry.bool("pos.installment.markupEnabled",
                        "Charge more on terms than for cash",
                        "Off, and not yet available. Charging a markup makes the difference finance "
                                + "income rather than sales, which needs its own account before it can be "
                                + "booked correctly. Until then, price the item at its installment price.",
                        false, "Installments"),

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
                // U2 - the uplift for breaking a pack. Design: docs/slices/u2-loose-sale-arithmetic.md 4.1.
                //
                // DEFAULT 0, so a shop that has not asked for this sees no change at all: a tablet costs
                // exactly the pack price divided. It ships in the same slice as loose selling rather than
                // later because every trade that sells loose prices it above the pack rate - breaking a pack
                // destroys the ability to sell it sealed - and a first release without it is a feature shops
                // decline to switch on.
                //
                // MONEY rather than INT because 2.5% is a real answer.
                SettingEntry.money("pos.sale.looseMarkupPct",
                        "Extra % when a pack is broken",
                        "Applies only to the loose part of a line. 0 (default): a piece costs the pack price "
                                + "divided by the pack size. 10: a piece costs 10% more than that. Whole packs "
                                + "are always charged at the pack price, so ten tablets out of a pack of ten "
                                + "never costs more than the sealed pack beside it. The rate is rounded UP to "
                                + "the nearest paisa - rounding down would lose money on every broken pack.",
                        "0", "Point of Sale"),
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
                // "Booked By" OVERRIDE. Blank (default) keeps the per-sale behaviour: the invoice shows the
                // person who actually rang it, stamped when the sale was written. Setting a value replaces
                // that with one fixed line on every document — which is what a shop wants when the invoice
                // should name a department or a licence holder rather than a cashier.
                SettingEntry.text("pos.document.bookedBy",
                        "Booked By line on invoices",
                        "Leave blank (default) to print the person who rang each sale. Set a value "
                                + "— a department, a licence holder, the proprietor — to print that on "
                                + "every invoice instead.",
                        "", "Documents"),
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
