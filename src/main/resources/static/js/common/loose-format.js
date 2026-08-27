/*
 * loose-format.js — how a sale line is READ by a person.
 *
 * Design: microservices/docs/slices/u4-loose-on-paper.md
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * ONE FUNCTION, SIX CALLERS
 *
 * A stored line becomes something a person reads in six places: the receipt family (80mm / A4 / trade, which
 * share one normaliser), the cart grid twice (manual add and scan add), the sale detail report, its summary
 * row, and the quantity box when an invoice is loaded for editing.
 *
 * Six hand-written conversions would be six chances to disagree — and two of them ALREADY did. Adding five
 * tablets by hand showed `0.5` in the cart while scanning `5L*CODE` showed `5`: same product, same sale, two
 * unlabelled numbers, because the two paths built their row independently.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS FILE HAS NO DEPENDENCIES
 *
 * `receipt.js` renders into a print window that does not necessarily load the till's module, and
 * `loose-sell.js` binds keyboard handlers that must never follow a receipt into a print preview. A pure
 * module — no jQuery, no DOM, no globals beyond its own export — can be loaded by both, and unit-tested
 * without a browser. That is what makes converging six callers safe rather than merely tidy.
 */
(function (global) {
    'use strict';

    function num(v) {
        var n = Number(v);
        return isFinite(n) ? n : 0;
    }

    /**
     * How to present one sale line.
     *
     * @param  line a Sell row or a cart line — anything carrying quantity/sellRate and, if it was sold
     *              loose, soldUnit/soldQuantity/soldRate/packSizeSnapshot.
     * @return {{isLoose:boolean, qty:number, unit:string, rate:number, packs:number}}
     *
     * <p><b>A line that was not sold loose comes back exactly as its caller would have read it anyway.</b>
     * `soldUnit` is null on every row written before U2 and on every pack sale after it, so this function is
     * an identity for the overwhelming majority of lines in every tenant. That is the property that lets all
     * six callers adopt it without a shop noticing.
     */
    function looseDisplay(line) {
        var l = line || {};
        var plainQty = num(l.quantity);
        var plainRate = (l.sellRate != null) ? num(l.sellRate)
            : (l.stock && l.stock.bsellRate != null) ? num(l.stock.bsellRate) : 0;

        if (String(l.soldUnit || '').toUpperCase() !== 'LOOSE') {
            return { isLoose: false, qty: plainQty, unit: '', rate: plainRate, packs: plainQty };
        }

        /*
         * The customer's own version of the transaction. `soldRate` is READ, never derived from
         * quantity/sellRate — a tax inspector reconciles `quantity x rate` against the line total, and
         * "5 tablets" beside a rate of 120.00 would not reconcile. U2 stored the per-piece rate for exactly
         * this moment.
         */
        var pieces = num(l.soldQuantity);
        return {
            isLoose: true,
            qty: pieces > 0 ? pieces : plainQty,
            unit: l.looseUnitPlural || l.looseUnit || '',
            rate: (l.soldRate != null) ? num(l.soldRate) : plainRate,
            packs: plainQty
        };
    }

    /**
     * "5 tablets", or "2" for an ordinary line.
     *
     * <p>The caller is responsible for escaping: `unit` is tenant-authored text and reaches the DOM in most
     * of these six places. This module deliberately does no escaping of its own, because a string that has
     * been escaped twice is a string that prints `&amp;amp;`.
     */
    function looseQtyText(line) {
        var d = looseDisplay(line);
        if (!d.isLoose || !d.unit) return String(d.qty);
        return d.qty + ' ' + d.unit;
    }

    /**
     * How many PACKS this line takes off the shelf — the stock view of the same line.
     *
     * <p>Read from the stored `quantity`, never recomputed from `soldQuantity / packSize`: the server derived
     * it once, at the sale, and a second derivation here would disagree the day a rounding rule changes.
     */
    function loosePacks(line) { return looseDisplay(line).packs; }

    /**
     * ⚠ THE PACK SIZE THAT APPLIED AT THE SALE — never the product's current one.
     *
     * A receipt reprinted next year must say what was sold THEN. If a shop moves a product from packs of 10
     * to packs of 12, a stored `quantity = 0.5` re-read against today's pack size becomes SIX tablets on a
     * receipt for a sale of five. U2 froze `packSizeSnapshot` on the line for this exact moment.
     */
    function loosePackSize(line) {
        var l = line || {};
        return (l.packSizeSnapshot != null) ? num(l.packSizeSnapshot) : null;
    }

    global.looseDisplay = looseDisplay;
    global.looseQtyText = looseQtyText;
    global.loosePacks = loosePacks;
    global.loosePackSize = loosePackSize;
})(typeof window !== 'undefined' ? window : this);
