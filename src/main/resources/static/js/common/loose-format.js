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

    /**
     * U6 — a shelf quantity in the language of the person counting it.
     *
     *     9.5 packs of 10   ->  { packs: 9, pieces: 5, text: "9 packs + 5 tablets" }
     *     10                ->  { packs: 10, pieces: 0, text: "10" }        an ordinary product
     *
     * <p><b>Why 9.5 is not good enough.</b> It is arithmetically true and operationally useless: nobody
     * counts half a pack. It means nine sealed packs and five loose tablets, and until the screen says so a
     * shop cannot reconcile what it holds against what the system claims.
     *
     * <h3>⚠ The residue must not become a phantom tablet</h3>
     * Stock is kept in SELLING units, which accepts a bounded drift: one third of a pack stores as 0.3333, so
     * three single sales from a pack of three leave 0.0001 behind rather than exactly zero. Rendered naively
     * that reads "0 packs + 0.0003 tablets" — worse than the number it replaces, because it looks like a
     * defect and invites someone to go looking for one.
     *
     * <p>So the piece count is ROUNDED TO WHOLE PIECES and a remainder under half a piece shows nothing. The
     * stored number is untouched; only the reading changes. <i>A display that invents precision the data does
     * not have is a lie told confidently.</i>
     *
     * @param onHand   the stored quantity, in packs
     * @param packSize pieces per pack, or null/1 for an ordinary product
     * @param unit     what one piece is called, e.g. "tablets"
     */
    function shelfText(onHand, packSize, unit) {
        var qty = num(onHand);
        var size = num(packSize);
        if (!(size > 1)) return { packs: qty, pieces: 0, text: String(qty) };

        var whole = Math.floor(qty + 1e-9);              // 9.9999 is nine packs and a rounding artefact
        var pieces = Math.round((qty - whole) * size);

        if (pieces >= size) { whole += 1; pieces = 0; }  // 9.99999 x 10 rounds to 10 pieces = one more pack
        if (pieces <= 0) return { packs: whole, pieces: 0, text: String(whole) };

        var noun = unit || 'pcs';
        return { packs: whole, pieces: pieces, text: whole + ' + ' + pieces + ' ' + noun };
    }

    global.shelfText = shelfText;
    global.looseDisplay = looseDisplay;
    global.looseQtyText = looseQtyText;
    global.loosePacks = loosePacks;
    global.loosePackSize = loosePackSize;
})(typeof window !== 'undefined' ? window : this);
