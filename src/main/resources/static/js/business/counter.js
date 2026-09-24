/*
 * counter.js — RST: the TILE-FIRST sale screen, for a counter that sells a fixed menu.
 *
 * Design: microservices/docs/restaurant-vertical-design.md §4
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS NOT QUICK-PICK
 *
 * `pos.quickpick.enabled` puts the nine best sellers above the cart as an accelerator ON TOP of the typed
 * line form. That is right for a grocer with a few unbarcoded items. A restaurant is the opposite case: the
 * cashier never types into the line form at all, and the WHOLE menu must be reachable — 87 items across 16
 * categories for the tenant this was built for. Nine tiles chosen by sales history cannot serve that.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY A SEPARATE FILE
 *
 * Three sessions were editing business.js the day this was written, and it is already 6,000+ lines. Nothing
 * here needs to live inside it: the counter composes the SAME cart through the SAME entry point the scan
 * path uses (`scanAddToCart`), so tender, customer, receipt, tax, credit and the books are untouched and
 * cannot drift. A tile is a scan without a barcode.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * ⚠ THE TWO RULES THIS FILE EXISTS TO HOLD
 *
 *  1. ONE TAP ADDS. A sheet on every item costs roughly two extra taps each; at forty items an hour that is
 *     a minute of queue, and at a 24/7 counter the cashier IS the queue. The sheet is for items that carry a
 *     genuine choice, which needs the modifier model (R2) — until then, every tile adds on one tap.
 *  2. THE ORDER NEVER LEAVES THE SCREEN. Categories FILTER the grid; they do not navigate. A drill-down
 *     charges a "back" tap per category change and hides the running order, which is what the customer is
 *     watching while they decide.
 */
(function (global, $) {
    'use strict';

    var products = [];          // [{id, name, sellingPrice, categoryName}]
    var cats = [];              // category names, in menu order
    var activeCat = null;
    var loaded = false;

    function enabled() { return global.posCounterEnabled === true; }

    function t(key, fallback) {
        return (typeof global.t === 'function' && global.t(key) !== key) ? global.t(key) : fallback;
    }

    function money(n) {
        if (n == null || n === '') return '';
        return (typeof srMoney === 'function') ? srMoney(n) : String(n);
    }

    /* ── data ─────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * The menu, read ONCE per screen open.
     *
     * ⚠ `q=-1` because the counter must show the WHOLE menu. `/catalogProductPicker` pages at 2,000 and
     * orders by NAME, so a paged read silently drops everything after position 2,000 — the exact defect that
     * made three other specs look like cache failures on a 3,400-product tenant. `/getUserProduct?q=-1`
     * returns the tenant's own list with its categories, which is what the tiles are grouped by.
     */
    function load(done) {
        if (loaded) { done && done(); return; }
        $.get(serverContext + 'getUserProduct?q=-1', function (resp) {
            var rows = (resp && (resp.collection || resp.data)) || [];
            products = rows.filter(function (p) {
                // A tile you cannot sell is a tile that wastes a tap. Inactive products are already excluded
                // by the endpoint; this guards a row with no price, which would ring up as zero.
                return p && p.id != null && p.sellingPrice != null;
            }).map(function (p) {
                return {
                    id: p.id, name: p.name || ('#' + p.id),
                    sellingPrice: Number(p.sellingPrice),
                    cat: p.categoryName || p.category || t('ui.js.uncategorised', 'Uncategorised')
                };
            });
            var seen = {};
            cats = [];
            products.forEach(function (p) { if (!seen[p.cat]) { seen[p.cat] = 1; cats.push(p.cat); } });
            cats.sort();
            activeCat = cats.length ? cats[0] : null;
            loaded = true;
            done && done();
        }).fail(function () {
            // A counter that cannot read the menu falls back to the typed form, which is still on the screen.
            // Losing the tiles is a degraded till; blocking the screen would be a stopped one.
            loaded = true;
            done && done();
        });
    }

    /** Let a write elsewhere (a new product, a price change) be picked up on the next open. */
    function invalidate() { loaded = false; }

    /* ── render ───────────────────────────────────────────────────────────────────────────────────────── */

    function renderCats() {
        var $b = $('#counterCats');
        if (!$b.length) return;
        $b.html(cats.map(function (c) {
            // escHtml: category names are tenant data reaching the DOM (XSS-safe rendering standard).
            return '<button type="button" class="ctr-cat" role="tab" data-cat="' + escHtml(c) + '"'
                + ' aria-selected="' + (c === activeCat) + '">' + escHtml(c) + '</button>';
        }).join(''));
    }

    function renderItems() {
        var $g = $('#counterItems');
        if (!$g.length) return;
        var list = products.filter(function (p) { return p.cat === activeCat; });
        if (!list.length) {
            $g.html('<div class="ctr-empty">' + escHtml(t('ui.js.counterEmpty',
                'No items in this category yet.')) + '</div>');
            return;
        }
        $g.html(list.map(function (p) {
            return '<button type="button" class="ctr-tile" data-pid="' + p.id + '">'
                + '<span class="ctr-nm">' + escHtml(p.name) + '</span>'
                + '<span class="ctr-pr">' + escHtml(money(p.sellingPrice)) + '</span></button>';
        }).join(''));
    }

    function render() {
        var $wrap = $('#counterWrap');
        if (!$wrap.length) return;
        if (!enabled()) { $wrap.hide(); return; }
        $wrap.show();
        renderCats();
        renderItems();
    }

    /** Open (or refresh) the counter for the sale screen. */
    function open() {
        if (!enabled()) { $('#counterWrap').hide(); return; }
        load(render);
    }

    /* ── adding ───────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * One tap = one unit on the order.
     *
     * ⚠ Routed through `scanAddToCart`, deliberately. That function already merges a repeat tap onto the
     * existing line, stamps pack size, prices the line with the SAME sellLineMath the manual add uses, and
     * re-quotes for the chosen customer. Composing a cart line here would be a second implementation of all
     * of it — and U15-A4 is the record of what happens when a second add path prices lines its own way: a
     * scan billed 600.00 for a line worth 15.00, and the cashier's change came off that figure.
     */
    function add(pid) {
        var p = products.filter(function (x) { return String(x.id) === String(pid); })[0];
        if (!p) return;
        if (typeof scanAddToCart !== 'function') return;
        scanAddToCart({ id: p.id, name: p.name, sellingPrice: p.sellingPrice }, 1, 'PACK', null);
        flash(p.name);
    }

    function flash(name) {
        var $m = $('#counterMsg');
        if (!$m.length) return;
        $m.text(name + '  ✓').show();
        clearTimeout($m.data('t'));
        $m.data('t', setTimeout(function () { $m.fadeOut(180); }, 1100));
    }

    /* ── quantity on the ORDER LINE ───────────────────────────────────────────────────────────────────── */

    /**
     * ⚠ Quantity belongs on the line, not in a dialog before the item exists.
     *
     * A "how many?" prompt at add time means a miscount costs a remove-and-re-add; on the line it costs one
     * tap. This is the +/- the design calls for, and it re-prices through the cart's own maths rather than
     * writing a total — so a loose line, a discounted line and a re-quoted line all stay correct.
     */
    function step(pid, delta) {
        if (!global.data) return;
        var idx = -1;
        for (var i = 0; i < global.data.length; i++) {
            if (String(global.data[i].productId) === String(pid)) { idx = i; break; }
        }
        if (idx < 0) return;
        var line = global.data[idx];

        // A LOOSE line counts in pieces, and its shelf quantity is derived. Stepping `quantity` directly
        // would desync soldQuantity from quantity — the defect U15-A4 fixed on the scan path. Out of scope
        // here: the counter sells whole menu items, so a loose line can only arrive from the typed form.
        if (String(line.soldUnit || '').toUpperCase() === 'LOOSE') return;

        var next = (Number(line.quantity) || 0) + delta;
        if (next <= 0) {
            // THIS line (idx), not "the first line for the product" — two lines of one item are two lines.
            if (typeof UIT === 'function') { UIT(pid, idx); return; }
            return;
        }
        line.quantity = next;
        var m = sellLineMath(line.sellRate, next, 0,
            (line.stock && line.stock.bsellDiscount) || 0,
            (line.stock && line.stock.bsellDiscountType) || '0');
        line.totalAmount = m.total;
        line.netAmount = m.profit;
        // CART-1: the line changed in data[]; business.js draws the grid from it and refreshes the subtotals,
        // the payable line, Change and Due. Patching the row here was a second copy of the row format.
        if (typeof global.renderCart === 'function') global.renderCart();
    }

    /* ── wiring ───────────────────────────────────────────────────────────────────────────────────────── */

    $(function () {
        $(document).on('click', '.ctr-cat', function () {
            activeCat = $(this).attr('data-cat');
            renderCats();
            renderItems();
        });
        $(document).on('click', '.ctr-tile', function () {
            add($(this).attr('data-pid'));
        });
        // Delegated on the cart body so it survives every DataTables redraw.
        $(document).on('click', '.ctr-step', function (e) {
            e.stopPropagation();   // the cart row has its own click handler for selection
            step($(this).attr('data-pid'), Number($(this).attr('data-d')) || 0);
        });
    });

    global.Counter = { open: open, render: render, invalidate: invalidate, step: step, isEnabled: enabled };
})(window, jQuery);
