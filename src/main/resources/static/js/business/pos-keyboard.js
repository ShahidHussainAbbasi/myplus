/* ==========================================================================
 * pos-keyboard.js — the keyboard contract for the sale screen (design P1, step 2).
 *
 * WHAT IT DOES
 * Enter walks the fields a cashier must fill and then commits the line, so a
 * sale can be entered without reaching for the mouse:
 *
 *     Item  --Enter-->  Qty  --Enter-->  Price  --Enter-->  Disc  --Enter-->  ADD
 *
 * ...except it SKIPS anything already satisfied or switched off, so the common
 * line is really  item -> 12 -> Enter.  Shift+Enter walks back. Esc clears the
 * row without committing.
 *
 * WHAT IT DOES NOT DO
 * It never reimplements a behaviour that exists. Committing a line is
 * `$('#addInviceItem').click()` — the same handler, the same validation, the
 * same cart write as the mouse path. A second "add to cart" would be a
 * correctness risk dressed up as a UX feature.
 *
 * OFF BY DEFAULT
 * Everything here is inert unless the org turned on `pos.keyboard.enabled`
 * (window.posKeyboardEnabled, set by loadPosFeatureFlags). The flag is read on
 * EVERY keystroke rather than cached at bind time, so toggling the setting
 * takes effect without a reload.
 *
 * WHY ENTER IS SAFE TO BIND
 * <form id="Sell"> declares method="POST" but contains no type="submit" button,
 * so the browser's implicit-submission rule already does nothing with Enter.
 * We are giving a dead keystroke a job, not overriding a live one. The handler
 * still calls preventDefault() so that stays true if a submit button is ever
 * added.
 * ========================================================================== */
(function (global) {
    'use strict';

    /* The commit chain, in the order a cashier fills it. Ids only — the DOM is
     * the single source of truth for whether each one currently applies. */
    var CHAIN = ['sellItemDD', 'sellItems', 'sellSellRate', 'sellDiscount'];

    /** Read-only fields that display a computed value. They are still submitted and still written to
     *  (see pos-rowentry.css on FormData) — they simply must not cost a Tab, because a cashier can
     *  never type into them. This is the review's F3: 7-11 dead stops on a single sale. */
    var DEAD_STOPS = [
        'sellItemDesc', 'sellStock', 'bexpDate', 'sellTotalAmount', 'sellrm',   // line form
        'sellPurchaseRate', 'sellNetAmount', 'sellReturn',                      // hidden profit block
        'sellCh', 'sellDueThis', 'sellPaidSoFar',                               // checkout
        'sellPrevDue', 'sellNewTotalDue', 'sellCreditLimit', 'sellCreditAvailable'  // account row
    ];

    function enabled() { return global.posKeyboardEnabled === true; }

    /** P2 — the action keys and the scan-box quantity multiplier. A separate setting from the Enter
     *  chain: a shop may want fast line entry without arming function keys on an untrained till. */
    function shortcuts() { return global.posShortcutsEnabled === true; }

    /** True when the sale screen is the thing the user is actually looking at. Handlers are delegated
     *  from `document`, so without this an Enter pressed on the Purchase or Reports screen — which
     *  reuse some of the same helpers — would drive the sell form. */
    function onSellScreen() {
        var el = document.getElementById('sellDiv');
        return !!el && $(el).is(':visible');
    }

    /** True when something is layered OVER the screen: a CRUD modal or the shared confirm dialog.
     *  Committing a line, or worse completing a sale, from behind a dialog the cashier cannot see is
     *  the one failure mode worth designing against. */
    function blocked() {
        return document.querySelector('.crud-overlay.open') !== null
            || document.querySelector('.uiC-backdrop') !== null;
    }

    /** A field participates in the chain only if it is present, visible, and editable. This is what
     *  makes the chain follow the tenant's configuration for free: a shop that switched the line
     *  discount off has hidden #sellDiscount, so Enter on Price commits instead of landing in it. */
    function usable(id) {
        var $f = $('#' + id);
        if (!$f.length) return false;
        if ($f.prop('disabled') || $f.prop('readonly')) return false;
        // :visible is false for anything inside a display:none ancestor, which covers both
        // .pos-hidden (tenant turned it off) and .pos-more (off the compact row).
        return $f.is(':visible');
    }

    /** Fields the forward pass never stops on, because leaving them blank is a normal sale. Reachable
     *  by Tab or Shift+Enter when the cashier actually wants one — but making everybody press Enter
     *  past a discount they are not giving is the exact overhead this feature exists to remove. */
    var OPTIONAL = { sellDiscount: true };

    /**
     * A field is "satisfied" when it already holds a value the cashier was never going to change, so
     * stopping there would cost a keystroke and teach them to hammer Enter.
     *
     * ONLY THE PRICE. An earlier version also counted Qty — a bug: `loadStock()` pre-fills Qty with
     * `pos.entry.defaultQty` (1 by default) the moment an item is picked, so Qty was ALWAYS
     * "satisfied" and the chain skipped straight past it. The cashier could never type a quantity
     * with Enter, and landed in the optional discount instead. A DEFAULT is not a decision; a price
     * the catalog supplied is.
     */
    function satisfied(id) {
        return id === 'sellSellRate' && Number($('#' + id).val()) > 0;
    }

    /**
     * The next field Enter should land on, or null to commit the line.
     * `from` is the id Enter was pressed in; `dir` is +1 forward, -1 for Shift+Enter.
     *
     * Backwards NEVER commits and never skips satisfied fields — going back is a deliberate act to
     * change something, so it must stop on fields the forward pass flew over.
     */
    function nextField(from, dir) {
        var i = CHAIN.indexOf(from);
        if (i < 0) return null;
        for (var j = i + dir; j >= 0 && j < CHAIN.length; j += dir) {
            var id = CHAIN[j];
            if (!usable(id)) continue;
            if (dir > 0 && (satisfied(id) || OPTIONAL[id])) continue;
            return id;
        }
        return (dir > 0) ? null : from;   // forward past the end = commit; back past the start = stay
    }

    /** Focus a field. bootstrap-select hides the real <select> behind a button, so focusing the
     *  select itself would silently do nothing — reuse focus-flow's rule rather than restating it. */
    function focusField(id) {
        var el = document.getElementById(id);
        if (!el) return;
        if ($(el).hasClass('selectpicker')) {
            var $btn = $(el).next('.bootstrap-select').find('button').first();
            if ($btn.length) { $btn.trigger('focus'); return; }
        }
        try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
        if (el.select) { try { el.select(); } catch (e2) {} }   // typing replaces, not appends
    }

    /** Where the cashier starts the next line: the scan box when the org uses barcodes, else the
     *  item picker. Called after every commit so the till is always ready for the next scan. */
    function focusEntryPoint() {
        if (global.posBarcodeEnabled !== false && $('#sellScan').is(':visible')) {
            focusField('sellScan');
        } else {
            focusField('sellItemDD');
        }
    }
    global.posFocusEntryPoint = focusEntryPoint;

    /** Commit the in-progress line through the EXISTING add-to-cart handler, then reset for the next
     *  one. Returns without committing when the line is obviously incomplete, so Enter on an empty
     *  row is a no-op rather than a validation error the cashier has to dismiss. */
    function commitLine() {
        if (!$('#sellItemDD').val()) { focusField('sellItemDD'); return; }
        if (!(Number($('#sellItems').val()) > 0)) { focusField('sellItems'); return; }
        $('#addInviceItem').trigger('click');
        // The click path calls resetForm() + resetBSDD() itself; we only decide where focus lands.
        global.setTimeout(focusEntryPoint, 0);
    }

    /** Esc: abandon the line being composed without touching the cart. Reuses the form's own Cancel
     *  button so "clear the row" means exactly what it has always meant. */
    function clearLine() {
        $('#resetInviceItem').trigger('click');
        if (typeof global.resetBSDD === 'function') { try { global.resetBSDD('sellItemDD'); } catch (e) {} }
        focusEntryPoint();
    }

    /** Take the display-only fields out of the tab order. Idempotent, and re-applied whenever the
     *  screen is shown because parts of the checkout row are built/revealed after load. */
    function markDeadStops() {
        for (var i = 0; i < DEAD_STOPS.length; i++) {
            var el = document.getElementById(DEAD_STOPS[i]);
            if (el) el.setAttribute('tabindex', '-1');
        }
    }
    /** ...and put them back, so turning the setting off restores the old tab order without a reload. */
    function clearDeadStops() {
        for (var i = 0; i < DEAD_STOPS.length; i++) {
            var el = document.getElementById(DEAD_STOPS[i]);
            if (el) el.removeAttribute('tabindex');
        }
    }

    /** Called by loadPosFeatureFlags after the flags land, and whenever the sell screen is shown. */
    global.applyPosKeyboard = function () {
        if (enabled()) { markDeadStops(); } else { clearDeadStops(); }
    };

    /* ══ P2 — action keys ═══════════════════════════════════════════════════════════════════════
     * Each ACTION is a named function so the key table below stays a table, and so the gate can call
     * the behaviour without synthesising a function key.
     */

    /** The live cart total, read from the footer cell the cart itself maintains. There is no second
     *  arithmetic here on purpose: a tender that disagreed with the printed total by a rounding step
     *  would be a bug nobody could explain at the counter. */
    function cartTotal() {
        var el = document.getElementById('sellTotal');
        var v = el ? Number(String(el.innerHTML).replace(/[^0-9.\-]/g, '')) : NaN;
        return isNaN(v) ? 0 : v;
    }

    /** F8 — the customer is paying the exact amount, which is most transactions at a busy counter.
     *  The number was always sitting in the cart footer; it was simply never offered. */
    function exactCash() {
        var total = cartTotal();
        if (!(total > 0)) { return; }                 // nothing to tender
        // While EDITING an invoice, "Amount Received" means ADDITIONAL payment on top of what was
        // already paid — tendering the whole bill again would double-count it (SF-1/SF-2).
        var priorPaid = (global.editingInvoice && global.editingPaid) ? Number(global.editingPaid) : 0;
        var due = Math.round((total - priorPaid) * 100) / 100;
        if (!(due > 0)) { return; }
        $('#sellRec').val(due.toFixed(2));
        if (typeof global.calculateChange === 'function') global.calculateChange();
        $('#sellRec').trigger('focus');               // so the cashier can override before completing
    }

    /** F2 — complete the sale through the EXISTING submit handler: same validation, same idempotency
     *  key, same receipt. Never a second checkout path. */
    function completeSale() {
        var $b = $('#addSell');
        if (!$b.length || $b.prop('disabled')) return;   // already in flight (jsonPost locks it)
        $b.trigger('click');
    }

    function parkSale()   { if (typeof global.parkCurrentSale === 'function') global.parkCurrentSale(); }
    function resumeParked(){ if (typeof global.showParked === 'function') global.showParked(); }

    /**
     * F9 — discard the cart, ASKING FIRST.
     *
     * The screen's own Clear Cart button calls resetCart() with no confirmation, and that is
     * defensible for a mouse: the operator aimed at a red button and hit it. A function key is a
     * different risk — F9 is next to F8, and a mis-hit would silently destroy a part-rung sale with
     * a queue waiting. So the KEYBOARD path confirms even though the button does not.
     *
     * (Deliberately not adding the prompt to resetCart() itself: that would change the behaviour of
     * an existing control nobody asked me to change. Raised for the user instead.)
     */
    function clearCart() {
        if (!global.data || global.data.length === 0) return;    // nothing to discard, nothing to ask
        if (typeof global.uiConfirm !== 'function') { $('#resetSellItem').trigger('click'); return; }
        global.uiConfirm({
            title: t('ui.js.clearCartTitle'),
            message: t('ui.js.clearCartBody', global.data.length),
            confirmText: t('ui.js.clearCartOk'),
            tone: 'warning'
        }).then(function (ok) {
            if (ok) { $('#resetSellItem').trigger('click'); }
        });
    }

    global.posExactCash = exactCash;   // exported for the gate + any future toolbar button

    /* ══ P3 — quick-pick tiles ══════════════════════════════════════════════════════════════════
     * The shop's best sellers, above the cart, one keystroke each. Goods with no barcode (loose
     * produce, bakery, services) otherwise fall back to the full item form on every single sale.
     */

    /** Tiles currently drawn, in grid order. Index 0 is Alt+1. */
    var quickPick = [];

    function quickPickEnabled() { return global.posQuickPickEnabled === true; }

    /** Add a tile's product to the cart — the SAME path a scan takes, so pricing, the pharmacy Rx
     *  warning and the cart totals all behave identically. A tile is a scan without the barcode. */
    function addQuickPick(i) {
        var t = quickPick[i];
        if (!t) return;
        if (typeof global.scanAddToCart !== 'function') return;
        global.scanAddToCart({
            id: t.productId, name: t.name, sku: t.sku,
            unit: t.unit, sellingPrice: t.sellingPrice
        }, 1);
        // Feedback goes in the QUICK-PICK panel, not through sellScanMsg(). That writes to
        // #sellScanMsg, which lives inside #sellScanRow — hidden whenever pos.barcode.enabled is off.
        // Quick pick exists FOR shops with nothing to scan, so those are precisely the tenants most
        // likely to have switched the scan box off, and their tiles would have added stock silently.
        var qty = (typeof global.cartQty === 'function') ? global.cartQty(t.productId) : 1;
        $('#quickPickMsg').text((t.name || '') + ' ×' + qty).show();
    }
    global.posAddQuickPick = addQuickPick;

    /**
     * Fetch and draw the tiles. Called when the sale screen opens.
     *
     * An empty or failed result hides the panel entirely rather than showing an empty box: a shop
     * with no sales history yet has no best sellers, and a blank grid claiming to be one is worse
     * than no grid. Every product stays reachable through the picker either way.
     */
    function renderQuickPick() {
        var $wrap = $('#quickPickWrap');
        if (!$wrap.length) return;
        if (!quickPickEnabled()) { quickPick = []; $wrap.hide(); return; }

        var n = Number(global.posQuickPickCount) > 0 ? Number(global.posQuickPickCount) : 9;
        var days = Number(global.posQuickPickDays) > 0 ? Number(global.posQuickPickDays) : 30;

        $.get(serverContext + 'topProducts', { days: days, limit: n }, function (resp) {
            // GenericResponse carries lists in `collection` — never `data`.
            var list = (resp && resp.collection) ? resp.collection : [];
            quickPick = list;
            $('#quickPickMsg').hide().text('');   // a stale "×3" from the previous sale is a lie
            if (!list.length) { $wrap.hide(); return; }

            var html = list.map(function (t, i) {
                var price = (t.sellingPrice == null) ? ''
                    : (typeof srMoney === 'function' ? srMoney(t.sellingPrice) : String(t.sellingPrice));
                // escHtml on every product-supplied value — these are user data (XSS-safe rendering rule).
                return '<button type="button" class="qp-tile" data-qp="' + i + '">'
                    + (i < 9 ? '<span class="qp-key">Alt+' + (i + 1) + '</span>' : '')
                    + '<span class="qp-name">' + escHtml(t.name || ('#' + t.productId)) + '</span>'
                    + (price ? '<span class="qp-price">' + escHtml(price) + '</span>' : '')
                    + '</button>';
            }).join('');
            $('#quickPickGrid').html(html);
            $wrap.show();
        }, 'json').fail(function () {
            quickPick = [];
            $wrap.hide();          // an accelerator that cannot load simply is not there
        });
    }
    global.renderQuickPick = renderQuickPick;

    /**
     * key -> action. F-keys are what till operators expect; the Alt aliases cover kiosks and browsers
     * that swallow function keys (and Linux desktops that bind F-keys to the window manager).
     *
     * Deliberately NOT bound: F1 (help), F5 (reload), F11 (fullscreen), F12 (devtools) — keys the
     * browser or OS owns, where an override either fails silently or angers the user.
     */
    var ACTIONS = {
        'F2': completeSale, 'F3': parkSale, 'F4': resumeParked, 'F8': exactCash, 'F9': clearCart
    };
    var ALT_ACTIONS = {
        's': completeSale, 'p': parkSale, 'r': resumeParked, 'e': exactCash, 'c': clearCart
    };

    /** A field the tenant switched off must not be reachable by shortcut either — otherwise a shop
     *  that disabled parking still has a key that parks. */
    function actionAllowed(fn) {
        var f = global.posFields || {};
        if ((fn === parkSale || fn === resumeParked) && f.park === false) return false;
        return true;
    }

    $(function () {

        // ── Enter / Shift+Enter / Esc inside the line form ──────────────────────
        // Delegated from document so it survives the form being reset, re-rendered, or reloaded for
        // an edit — none of which re-run this file.
        $(document).on('keydown', '#Sell input, #Sell select', function (e) {
            if (!enabled() || !onSellScreen() || blocked()) return;

            // The scan box owns its own Enter (inline onkeydown -> sellScanAdd). Leave it alone:
            // two handlers on one key is how a scan ends up added twice.
            if (this.id === 'sellScan') return;

            if (e.key === 'Escape') {
                e.preventDefault();
                clearLine();
                return;
            }
            if (e.key !== 'Enter') return;

            // preventDefault BEFORE anything else: if a submit button is ever added to this form,
            // the default action becomes a full page POST and the sale is lost.
            e.preventDefault();
            e.stopPropagation();

            var target = nextField(this.id, e.shiftKey ? -1 : 1);
            if (target === null) { commitLine(); return; }
            focusField(target);
        });

        // ── P2: action keys (F2/F3/F4/F8/F9 and their Alt aliases) ──────────────
        // Bound to the document, not the form: F8 must work while the cashier is anywhere on the sale
        // screen, including in the cart or the customer picker. The guards are what keep that safe.
        $(document).on('keydown', function (e) {
            // The screen guards apply to BOTH features; the feature flags are then checked per branch.
            // P2 (action keys) and P3 (quick pick) are separate settings, so a shop that turns on quick
            // pick alone must still get Alt+1..9 — otherwise the tiles would show "Alt+1" badges that
            // do nothing, which is worse than showing no badge at all.
            if (!onSellScreen() || blocked()) return;
            if (!shortcuts() && !quickPickEnabled()) return;

            var fn = null;
            if (e.altKey && !e.ctrlKey && !e.metaKey && e.key) {
                // P3: Alt+1..9 rings up a quick-pick tile. Digits and the P2 letter aliases cannot
                // collide, so the order of these two branches is a readability choice, not a rule.
                if (/^[1-9]$/.test(e.key)) {
                    if (!quickPickEnabled()) return;
                    var idx = Number(e.key) - 1;
                    if (idx < quickPick.length) {
                        e.preventDefault();
                        e.stopPropagation();
                        addQuickPick(idx);
                    }
                    return;   // Alt+digit belongs to quick pick, filled or not
                }
                if (!shortcuts()) return;
                fn = ALT_ACTIONS[String(e.key).toLowerCase()] || null;
            } else if (!e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
                if (!shortcuts()) return;
                fn = ACTIONS[e.key] || null;
            }
            if (!fn || !actionAllowed(fn)) return;

            // preventDefault only once we KNOW we are handling it — swallowing an unbound F-key would
            // take the browser's own shortcuts away from the operator for no benefit.
            e.preventDefault();
            e.stopPropagation();
            fn();
        });

        // Tiles are clickable as well as keyable — a touch till has no Alt key.
        $(document).on('click', '.qp-tile', function (e) {
            e.preventDefault();
            if (!quickPickEnabled() || blocked()) return;
            addQuickPick(Number($(this).attr('data-qp')));
        });

        // ── Enter on the bootstrap-select BUTTON ────────────────────────────────
        // The picker replaces the <select> with a button + menu, so a keystroke there never reaches
        // the select the handler above is bound to. When the menu is CLOSED, Enter means "I have
        // chosen, move on"; when it is open, it belongs to the picker's own item selection.
        $(document).on('keydown', '.bootstrap-select > button', function (e) {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (e.key !== 'Enter') return;
            var $sel = $(this).closest('.bootstrap-select').prev('select');
            if ($sel.attr('id') !== 'sellItemDD') return;
            if ($(this).closest('.bootstrap-select').hasClass('open')) return;   // picker's own Enter
            e.preventDefault();
            var target = nextField('sellItemDD', e.shiftKey ? -1 : 1);
            if (target === null) { commitLine(); return; }
            focusField(target);
        });

        // After an item is picked (typically with the MOUSE — the keyboard path already routes through
        // the chain) the async loadStock()/quote fills price and stock. Land the cursor in Qty once
        // that settles: it is the one field a cashier always types.
        //
        // Guarded on where focus actually IS when the timer fires. Without that, a cashier who picks
        // an item and immediately types into Price has the cursor yanked back to Qty a quarter of a
        // second later, mid-keystroke — a delayed focus steal is worse than no focus help at all.
        $(document).on('change', '#sellItemDD', function () {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (!$(this).val()) return;
            global.setTimeout(function () {
                if (!enabled() || !onSellScreen() || blocked()) return;
                var a = document.activeElement;
                var stillAtPicker = !a || a === document.body
                    || a.id === 'sellItemDD'
                    || $(a).closest('.bootstrap-select').prev('select').attr('id') === 'sellItemDD';
                if (stillAtPicker && usable('sellItems')) focusField('sellItems');
            }, 250);
        });
    });
})(window);
