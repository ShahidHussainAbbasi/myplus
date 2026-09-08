/*
 * last-rate.js — what THIS customer last paid for THIS product, beside the rate in the cart.
 *
 * ── WHAT IT IS, AND WHAT IT IS EMPHATICALLY NOT ─────────────────────────────────────────────────
 * A negotiating aid. At a counter that haggles, the question a cashier is asked across the counter is
 * "what did I pay last time?", and the honest answer used to require leaving the sale to go and look.
 *
 * It is a HINT, never an instruction:
 *   • what they DID pay, not what they SHOULD pay — dealer and tier pricing answer that, authoritatively
 *     and server-side, and this must never be confused with them;
 *   • it never writes the rate box. Nothing here changes a price. The cashier decides;
 *   • absent is a valid answer. A product this customer has not bought before shows NOTHING — not a
 *     dash, not a zero, and never the product's general last rate, which is a different question and
 *     the exact trap `Product.lastSaleRate` sets (that field is stamped by the PURCHASE flow and has no
 *     customer in it at all).
 *
 * ── HOW IT STAYS OFF THE HOT PATH ───────────────────────────────────────────────────────────────
 * ONE trigger: a COMMITTED LINE. Not a customer selection, not a product selection — a committed line
 * is the first moment both halves of the question exist, and it is the only moment the answer can
 * change in a way the operator can see. One batched read for the whole cart, never one per line.
 *
 * Changing the customer CLEARS the hints without fetching (see clear()): what is on screen becomes
 * wrong the instant the name changes, and the right answer is not yet worth a round trip because
 * nothing has been rung up for the new customer. The next committed line asks.
 *
 * Re-applied on the grid's own `draw` event rather than woven into each add/remove/edit path: DataTables
 * rebuilds its cells on every redraw, so anything injected once is wiped by the next line. Hooking the
 * redraw is the only version of this that cannot be forgotten by a future caller.
 */
(function (global, $) {
    'use strict';

    var cache = {};        // productId -> { rate, dated }
    var lastKey = null;    // customer + product set the cache was fetched for
    var bound = false;

    function enabled() { return global.posShowLastRate === true; }
    function ctx() { return global.serverContext || '/'; }

    function tr(key, fallback) {
        return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
            ? global.t(key) : fallback;
    }

    /** The customer this sale is for, in whichever mode the till is in. Null for an unnamed walk-in. */
    function customerId() {
        var id = $.trim($('#sellCustomerDD').val() || '');
        return id || null;   // manual-entry walk-ins have no id yet, so there is no history to read
    }

    /** The product being composed in the entry row, before it is committed. */
    function entryProductId() {
        var id = $.trim($('#sellItemDD').val() || '');
        return id || null;
    }

    /**
     * Every product the answer is wanted for: the committed lines, PLUS the one being composed.
     *
     * The entry product is included because that is the moment the number is actually useful — while
     * the rate is being decided, not after it has been typed. Asking for it costs nothing extra: it
     * joins the same batched request the cart already makes.
     */
    function wantedProductIds() {
        var out = [];
        (global.data || []).forEach(function (line) {
            var pid = line && (line.productId != null ? line.productId : line.itemId);
            if (pid != null && out.indexOf(String(pid)) < 0) out.push(String(pid));
        });
        var entry = entryProductId();
        if (entry && out.indexOf(entry) < 0) out.push(entry);
        return out;
    }

    /** dd MMM — short enough for a cart cell, unambiguous unlike a bare number. */
    function shortDate(iso) {
        if (!iso) return '';
        var d = new Date(String(iso).replace(' ', 'T'));
        if (isNaN(d.getTime())) return '';
        var M = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        return d.getDate() + ' ' + M[d.getMonth()];
    }

    function money(n) {
        var v = Number(n);
        if (!isFinite(v)) return '';
        return v.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    /**
     * Draw the hint into every cart row whose product has history.
     *
     * ⚠ Appended to the price cell rather than given a column of its own. #sellCartScroll already scrolls
     * sideways on a narrow till, and an eighth column would push the Action button off the edge on the
     * screens most likely to need this — a phone at a market stall.
     */
    function annotate() {
        var table = global.tablesi;
        if (!table || !enabled()) return;

        table.rows().every(function () {
            var $tr = $(this.node());
            $tr.find('.lr-hint').remove();
            if (!enabled()) return;

            var row = this.data() || [];
            var pid = String(row[0]);
            var hit = cache[pid];
            if (!hit || hit.rate == null) return;      // never bought this before: say nothing at all

            var $price = $tr.children('td').eq(3);     // 0 id · 1 name · 2 qty · 3 PRICE
            if (!$price.length) return;

            // Quoting BELOW what they last paid is the one case worth flagging: it is a concession the
            // shop is making right now, and it is the number an owner asks about afterwards. Above or
            // equal is unremarkable and stays neutral - a screen that shouts at everything says nothing.
            var now  = Number(String(row[3]).replace(/[^0-9.\-]/g, ''));
            var last = Number(hit.rate);
            var below = isFinite(now) && isFinite(last) && now < last;

            var when = shortDate(hit.dated);
            var title = tr('ui.js.lastRateTitle', 'What this customer last paid')
                      + (when ? ' — ' + when : '');

            $price.append(
                '<span class="lr-hint' + (below ? ' lr-below' : '') + '" title="' + escAttr(title) + '">'
                + '<span class="lr-cap">' + escHtml(tr('ui.js.lastRateCap', 'last')) + '</span>'
                + '<b>' + escHtml(money(hit.rate)) + '</b>'
                + (when ? '<i>' + escHtml(when) + '</i>' : '')
                + '</span>');
        });
    }

    /**
     * The hint beside the ENTRY row's rate box, for the product being composed.
     *
     * ⚠ This is where the number earns its keep. The cart hint is a review — the price is already
     * typed. Here it sits next to the box while the cashier decides what to charge, which is the moment
     * they are being asked "what did I pay last time?" across the counter.
     *
     * Still never authoritative: it does not fill the box, and nothing here changes a price.
     */
    function annotateEntry() {
        var $cell = $('#sellSellRate').closest('.pos-cell');
        $cell.find('.lr-entry').remove();
        if (!enabled() || !$cell.length) return;

        var pid = entryProductId();
        var hit = pid ? cache[pid] : null;
        if (!hit || hit.rate == null) return;      // no product chosen, or never bought: say nothing

        var when = shortDate(hit.dated);
        $cell.append(
            '<span class="lr-hint lr-entry" title="' + escAttr(tr('ui.js.lastRateTitle',
                'What this customer last paid') + (when ? ' \u2014 ' + when : '')) + '">'
            + '<span class="lr-cap">' + escHtml(tr('ui.js.lastRateCap', 'last')) + '</span>'
            + '<b>' + escHtml(money(hit.rate)) + '</b>'
            + (when ? '<i>' + escHtml(when) + '</i>' : '')
            + '</span>');
    }

    function escHtml(v) {
        return (typeof global.escHtml === 'function') ? global.escHtml(v == null ? '' : String(v))
            : String(v == null ? '' : v);
    }
    function escAttr(v) { return escHtml(v).replace(/"/g, '&quot;'); }

    /**
     * Fetch when — and only when — the answer could have changed.
     *
     * The key is customer + product set. Adding a line the cart already holds, editing a quantity, or
     * redrawing the grid all leave it identical, so they cost nothing.
     */
    function refresh() {
        if (!enabled()) { cache = {}; lastKey = null; annotate(); annotateEntry(); return; }

        var cid = customerId();
        var ids = wantedProductIds();
        // Nothing to answer FOR: no customer, or nothing chosen. Costs no request - ruling 1.
        if (!cid || !ids.length) { cache = {}; lastKey = null; annotate(); annotateEntry(); return; }

        var key = cid + '|' + ids.slice().sort().join(',');
        // Already asked this exact question. Re-selecting the same product, editing a quantity or
        // redrawing the grid all land here and cost nothing.
        if (key === lastKey) { annotate(); annotateEntry(); return; }
        lastKey = key;

        var qs = 'customerId=' + encodeURIComponent(cid)
               + ids.map(function (i) { return '&productIds=' + encodeURIComponent(i); }).join('');

        // global:false — a hint must never raise the blocking overlay over a counter, and must never be
        // the reason a sale stops. A failure simply leaves the hints undrawn.
        $.ajax({ url: ctx() + 'lastSoldRates?' + qs, dataType: 'json', global: false })
            .done(function (res) {
                cache = (res && (res.object || res.data)) || {};
                annotate(); annotateEntry();
            })
            .fail(function () { cache = {}; annotate(); annotateEntry(); });
    }

    /**
     * Forget everything and take the hints off the screen. No request.
     *
     * What changing the customer does — and the distinction from refresh() is the whole point.
     *
     * The cart may already be carrying hints fetched for the PREVIOUS customer. Leaving them there
     * would show one customer another customer's prices, which is the single worst thing this feature
     * could do and the reason case 2 of the gate exists. But re-fetching immediately would spend a
     * round trip answering a question nobody has asked yet: the operator has changed WHO is buying and
     * has not yet said WHAT. The next committed line asks, with both facts settled.
     *
     * So: wrong answers are removed at once, the right one is fetched when there is something to
     * answer. Nothing stale is ever on screen, and nothing is fetched speculatively.
     */
    function clear() {
        cache = {};
        lastKey = null;
        annotate(); annotateEntry();   // cache empty, so every hint is removed from both places
    }

    function bind() {
        if (bound) return;
        bound = true;

        // The grid rebuilds its cells on every redraw, so the hints are re-applied there. This is also
        // what covers add, remove and edit without any of those paths knowing this file exists.
        if (global.tablesi && global.tablesi.on) global.tablesi.on('draw.lastRate', annotate);

        /*
         * ⚠ THE DISPLAY MUST BE TRUE FOR THE CURRENT PAIR — reflect, do not merely clear.
         *
         * Clearing alone was half right. It stops the previous customer's price being shown under a new
         * name, but the cart LINES are still there and they now belong to somebody else: the honest
         * answer is that customer's history for those products, not a blank. And the cashier may never
         * add another line — they may just take the money — so "the next commit will fix it" leaves the
         * hint wrong-by-omission for the rest of the sale.
         *
         * The reconciliation with "do not fetch on selection" is that nothing is fetched SPECULATIVELY:
         *   • nothing chosen and an empty cart -> clear, no request. There is nothing to answer for.
         *   • anything chosen -> ask, because there is now something true to show.
         * Re-selecting the same product, or reopening a picker without changing it, matches the cache
         * key and costs nothing at all.
         */
        function reflect() {
            if (!customerId() || !wantedProductIds().length) { clear(); return; }
            lastKey = null;      // the pair changed, so the previous answer no longer applies
            refresh();
        }

        // Both events fire for one bootstrap-select choice; the cache key makes the second a no-op.
        $(document).on('changed.bs.select change', '#sellCustomerDD', reflect);
        $(document).on('changed.bs.select change', '#sellItemDD', reflect);
    }

    global.LastRate = { refresh: refresh, annotate: annotate, annotateEntry: annotateEntry,
                    clear: clear, bind: bind };
})(window, jQuery);
