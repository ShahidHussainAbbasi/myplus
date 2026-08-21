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
    var CHAIN = ['sellItemDD', 'sellItems', 'sellSellRate', 'sellDiscountTypeDD', 'sellDiscount'];

    /** Every dropdown the chain walks. These are bootstrap-select widgets, so they need the
     *  selection hook below rather than a keystroke handler — see D-23 in the P5 design. */
    var PICKERS = ['sellItemDD', 'sellDiscountTypeDD', 'sellCustomerDD', 'sellPayMethod'];

    /**
     * A sale has TWO phases, and until now only the first had a keyboard.
     *
     *   LINES     scan box / item → qty → price → discount → commit  (repeat)
     *   CHECKOUT  who is buying → how they pay → how much → complete
     *
     * The checkout controls live OUTSIDE <form id="Sell"> — they are assembled separately by main.js —
     * so the line chain could never reach them. A cashier could ring every line without touching the
     * mouse and then had to reach for it to name the customer, which is the point in the sale where a
     * queue actually forms.
     *
     * Order follows the money: who, then method, then what they hand over. Every entry is filtered by
     * usable(), so the ones that are conditional — store credit only when the customer has any,
     * insurance only for pharmacy, due date only when the sale leaves a balance, trade discount only
     * when the tenant enables it — appear in the walk exactly when they are on screen, and never
     * otherwise. Same rule as the line chain: configuration drives the keyboard for free.
     */
    var CHECKOUT = [
        'sellCustomerDD',      // select mode
        'sellCN', 'sellCC',    // manual mode (hidden in select mode, and vice versa)
        'sellPayMethod',
        'sellTradeDiscount',
        'sellStoreCredit',
        'sellInsured',
        'sellRec',
        'dueDateTemp'          // only visible when this sale leaves a balance
    ];

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
    // P6: the rule itself now lives in /js/common/enter-chain.js so the purchase form's chain cannot
    // drift from the sale screen's. Looked up at CALL time, not load time, so script order is free.
    function usable(id) { return global.EnterChain.usable(id); }

    /**
     * The next field Enter should land on, or null to commit the line.
     * `from` is the id Enter was pressed in; `dir` is +1 forward, -1 for Shift+Enter.
     *
     * LINEAR: Item → Qty → Price → Discount → commit. Enter stops on every field the tenant has left
     * usable, in both directions.
     *
     * An earlier version skipped a price the catalog had already filled in, on the theory that a
     * pre-filled value is an answer. That is true at a retail counter and WRONG wherever the rate is
     * negotiated per line — which is most trade and wholesale selling, and the case this chain exists
     * to serve. A price the system proposed is a suggestion, not a decision, and the cashier must pass
     * through it to accept or change it. Discount likewise: it now gets a stop rather than being flown
     * past, so a per-line concession never requires reaching for the mouse.
     *
     * The cost is two extra keystrokes on a line that takes both defaults. That is the right trade:
     * a skipped field the operator needed is a correction after the fact; an extra Enter is not.
     *
     * Fields the TENANT switched off, made read-only, or that the row layout hides are still skipped —
     * see usable(). That is not an optimisation, it is the only correct behaviour: a <select> or input
     * that is not there cannot take focus.
     */
    /**
     * SKIP-AHEAD: an EMPTY field means "this stage does not apply to this sale", so Enter jumps to the
     * next stage instead of the next field.
     *
     *   empty Item      -> the customer      (nothing more to add)
     *   empty Customer  -> Amount received   (a walk-in: method and terms are moot)
     *   chosen Customer -> Payment method    (an account sale: how they pay is a real decision)
     *
     * Reached from the picker handlers on the SECOND Enter — the first opens the menu, and an open
     * menu over an empty field is the signal that the cashier has nothing to choose here.
     *
     * @returns an id to focus, false when already handled, or null for normal handling
     */
    /**
     * The second Enter on an unanswered picker: "nothing here — move me on".
     *
     * ONE RULE FOR EVERY DROPDOWN. It used to name two pickers explicitly and answer null for the rest,
     * so #sellPayMethod and any picker added later had no keyboard way out of them — the same dead end
     * #sellDiscountTypeDD had before P5, rediscovered one field along. Naming fields individually is
     * what let that happen twice; the general answer is simply "the next usable field in this picker's
     * own chain".
     *
     * Two cases stay special, and both are about where the operator IS rather than which field it is:
     *
     *   • an empty ITEM picker means the line is finished, so the cursor belongs in the checkout, not
     *     at the next line field;
     *   • an empty CUSTOMER picker means a walk-in, so the cursor belongs on the money — skipping the
     *     payment method, which is already Cash. (A customer may still be REQUIRED to complete the
     *     sale; that is the submit path's business, and refusing to move the cursor is not how to say
     *     it. Blocking the keyboard does not name a customer, it just strands the cashier.)
     *
     * @returns an id to focus, false when this function has already moved the cursor, or null to leave it
     */
    function skipAhead(from) {
        if (from === 'sellItemDD' && !$('#sellItemDD').val()) {
            goToCheckout();
            return false;
        }
        if (from === 'sellCustomerDD') {
            // No customer => a WALK-IN, and a walk-in pays now. Skip straight to the money.
            //
            // Safe to skip the payment method because it is never empty: it opens on the tenant's
            // `pos.tender.default` (CASH out of the box, and a distributor invoicing on account sets it
            // to Credit). Skipping a field that already holds the right answer costs the cashier
            // nothing; Shift+Enter walks back to it on the rare sale that differs.
            return $('#sellCustomerDD').val() ? 'sellPayMethod' : 'sellRec';
        }
        // Everything else: the next usable field along, in whichever chain this picker belongs to.
        return walk(chainFor(from), from, 1);
    }

    /** The chain a field belongs to — the line form or the checkout. */
    function chainFor(id) { return CHAIN.indexOf(id) >= 0 ? CHAIN : CHECKOUT; }

    function nextField(from, dir) {
        return walk(CHAIN, from, dir);
    }

    /** Shared walk for both chains: the next USABLE id in `list`, or null past the forward end. */
    function walk(list, from, dir) { return global.EnterChain.walk(list, from, dir); }

    /** True when the cursor is somewhere in the checkout block rather than the line form. */
    function inCheckout(id) { return CHECKOUT.indexOf(id) >= 0; }

    /**
     * Leave line entry and go to the checkout — the keyboard bridge that did not exist.
     *
     * Refuses on an empty cart: "go to payment" with nothing to pay for would strand the cashier in a
     * block of fields that cannot complete anything, and the honest response is to leave them where the
     * items are typed.
     */
    function goToCheckout() {
        if (!global.data || global.data.length === 0) { focusEntryPoint(); return false; }
        for (var i = 0; i < CHECKOUT.length; i++) {
            if (usable(CHECKOUT[i])) { focusField(CHECKOUT[i]); return true; }
        }
        // Nothing in the checkout is reachable (every field hidden by configuration) — the sale is
        // already answerable, so go straight to completing it rather than nowhere.
        completeSale();
        return true;
    }
    global.posGoToCheckout = goToCheckout;

    /** Focus a field. bootstrap-select hides the real <select> behind a button, so focusing the
     *  select itself would silently do nothing — reuse focus-flow's rule rather than restating it. */
    function focusField(id) { global.EnterChain.focusField(id); }

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
        // The flow is only true when the feature is on — showing it otherwise would promise a keyboard
        // that does nothing.
        $('#sellKbdHint').toggle(enabled());
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
            if (!list.length) {
                // ENABLED BUT EMPTY — say so, do not hide.
                //
                // This used to hide the panel, on the reasoning that a blank grid claiming to be
                // "best sellers" is worse than no grid. True, but it made "switched on with no sales
                // history yet" look identical to "switched off" or "broken": the owner ticks the box,
                // nothing appears, and there is nothing to tell them why. A new shop — and every shop
                // is new once — sees the feature silently do nothing.
                //
                // The panel now explains itself and fills in as soon as there are sales to rank.
                $('#quickPickGrid').empty();
                $('#quickPickMsg').text(t('ui.js.quickPickEmpty')).show();
                $wrap.show();
                return;
            }

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
        // ── Enter on an EMPTY scan box = "no more items, take my money" ─────────
        // The scan box owns its own Enter for a non-empty code (inline onkeydown -> sellScanAdd, which
        // returns early on blank). An empty Enter there was a dead keystroke — and it is exactly the
        // gesture a cashier already makes: they are returned to this box after every line, so "nothing
        // left to add" is the most natural thing to express here. No new key to learn.
        $(document).on('keydown', '#sellScan', function (e) {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (e.key !== 'Enter') return;
            if (String($(this).val() || '').trim() !== '') return;   // a real scan — leave it to sellScanAdd
            e.preventDefault();
            e.stopPropagation();
            goToCheckout();
        });

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

        // ── The CHECKOUT chain ──────────────────────────────────────────────────
        // Bound separately from the line chain because these controls are not inside <form id="Sell">.
        $(document).on('keydown',
            '#customerSelectMode input, #customerSelectMode select, #customerManualMode input, '
          + '#sellPayMethod, #sellRec, #sellTradeDiscount, #sellStoreCredit, #sellInsured, #dueDateTemp',
        function (e) {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (e.key === 'Escape') {
                // Back to the items — the cashier remembered something. The cart is untouched.
                e.preventDefault();
                focusEntryPoint();
                return;
            }
            if (e.key !== 'Enter') return;
            e.preventDefault();
            e.stopPropagation();

            var target = walk(CHECKOUT, this.id, e.shiftKey ? -1 : 1);
            // Past the last checkout field = complete the sale. Same handler as the button and F2, so
            // the credit-limit checks, the due-date rule and the idempotency key all still apply —
            // this only decides WHEN it is called.
            if (target === null) { completeSale(); return; }
            focusField(target);
        });

        // Enter on the bootstrap-select button for the CUSTOMER picker (same reason as the item picker:
        // the plugin hides the real <select>, so a keystroke never reaches it).
        $(document).on('keydown', '.bootstrap-select > button', function (e) {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (e.key !== 'Enter') return;
            var $sel = $(this).closest('.bootstrap-select').prev('select');
            var id = $sel.attr('id');
            if (id !== 'sellCustomerDD' && id !== 'sellPayMethod') return;
            // DOUBLE-ENTER, done without a timer.
            //
            // The 1st Enter on a picker button opens its menu (a <button data-toggle="dropdown"> is
            // activated by Enter — that is what forced the double press in the first place). If the
            // menu is now OPEN and the field still holds NO value, this is the 2nd Enter on an
            // unanswered field: the cashier is saying "nothing here", so skip ahead.
            //
            // Using the menu's open state rather than a timing window means there is no guess about
            // how fast two presses count as one gesture, AND the open menu is visible feedback that
            // the first press registered — which a timer can never give.
            var $bs = $(this).closest('.bootstrap-select');
            var isOpen = $bs.hasClass('open');
            if (isOpen && $sel.val()) return;        // a value is highlighted/chosen — the picker owns this Enter

            /*
             * CLOSED, and already holding a value — ACCEPT IT AND MOVE ON.
             *
             * This is the commonest checkout there is and it had no way forward. Cash is the
             * pre-selected payment method, so #sellPayMethod holds a value from the moment the screen
             * opens. Advancing was wired only to `changed.bs.select`, which fires on a CHANGE — and
             * keeping the default is not a change. So Enter fell through to the branch below, which
             * opens the menu on an empty picker, did nothing useful on a full one, and left the cashier
             * on the pay method with no keystroke that would leave it. `sellRec` was unreachable on
             * every cash sale.
             *
             * "It already has the right answer" is the reason to move on, not the reason to stop.
             * A picker with no value still opens (below) — that case is unchanged.
             */
            if (!isOpen && $sel.val() && !e.shiftKey) {
                e.preventDefault();
                var fwd = walk(CHECKOUT, id, 1);
                // NOTHING AHEAD => STAY PUT, do not complete the sale.
                //
                // Deliberately unlike the generic branch below, which does treat "past the end" as
                // "finish". This one fires on a picker the operator has not touched — cash is
                // pre-selected, so Enter here means "accept the default and move on". If the fields
                // after it have not rendered yet, walk() answers null for a moment, and finishing the
                // sale on that would turn a mistimed keystroke into a completed transaction. The
                // operator still has F2, which says finish and means it.
                if (fwd) { focusField(fwd); }
                return;
            }
            if (isOpen) {
                e.preventDefault();
                $bs.removeClass('open');             // close it; the answer is "skip"
                var jumped = skipAhead($sel.attr('id'));
                if (jumped === false) return;        // handled (moved to checkout)
                if (jumped) { focusField(jumped); return; }
            }
            // CLOSED and EMPTY: open the menu, same as the line-entry pickers. Without this the
            // customer and tender lists could never be opened from the keyboard — which on a CREDIT
            // sale means the cashier cannot name the customer who owes.
            if (!$sel.val() && !e.shiftKey) {
                // NATIVE click — the plugin has no toggle() in 1.6.2, and an unknown method there
                // is a silent no-op, so a try/catch around it guards nothing.
                e.preventDefault();
                var oBtn = $bs.find('button')[0];
                if (oBtn) oBtn.click();
                return;
            }
            e.preventDefault();
            var target = walk(CHECKOUT, id, e.shiftKey ? -1 : 1);
            if (target === null) { completeSale(); return; }
            focusField(target);
        });

        // ── D-23: advance when a dropdown's value is SELECTED, not when Enter is pressed ────
        //
        // Every picker here is a bootstrap-select: a <button data-toggle="dropdown">. Enter on a focused
        // button ACTIVATES it, so the first Enter opens the menu and only a second could advance — the
        // "double Enter" the operator was forced into. Hooking selection removes that entirely, and it
        // is input-agnostic: choosing with the mouse or a touch screen advances exactly the same way,
        // which no keystroke patch can achieve.
        //
        // GUARD: this event also fires when JS sets a value (loadStock, loadCategories,
        // loadManufacturers all call .val() + selectpicker('refresh')). Only a USER selection may move
        // the cursor — otherwise loading a product would fling focus across the form. bootstrap-select
        // passes clickedIndex for a real interaction and omits it for a programmatic change.
        $(document).on('changed.bs.select', PICKERS.map(function (id) { return '#' + id; }).join(','),
        function (e, clickedIndex) {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (clickedIndex === undefined || clickedIndex === null) return;   // programmatic — ignore
            var id = this.id;
            var list = chainFor(id);          // one answer to "which chain", shared with skipAhead
            var target = walk(list, id, 1);
            if (target === null) {
                if (list === CHAIN) { commitLine(); } else { completeSale(); }
                return;
            }
            focusField(target);
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
            var pickerId = $sel.attr('id');
            /*
             * EVERY picker on the sale screen, line form AND checkout — one rule of thumb.
             *
             * This used to require membership of CHAIN, which quietly excluded the checkout pickers:
             * #sellCustomerDD and #sellPayMethod live in CHECKOUT. So the double-Enter escape below —
             * "open it, and if I press again with nothing chosen, move on" — worked on the item picker
             * and existed nowhere else. On a walk-in cash sale the cursor landed on the customer list
             * and no keystroke would leave it.
             *
             * It is the SECOND time this exact dead end shipped. #sellDiscountTypeDD was added to CHAIN
             * in P5 but not to this guard, and became a stop with no keyboard way out — on a sale with
             * no discount, which is most sales, the cashier was stranded on a chooser they never
             * wanted. Both times the cause was a list of eligible fields maintained by hand, one name
             * at a time, beside the list that decides where the cursor goes.
             *
             * A behaviour a cashier learns on one dropdown must hold on all of them. Membership of
             * PICKERS is the whole test now; which chain a picker belongs to is decided later, by
             * chainFor(), where it is a routing question rather than an eligibility one.
             */
            if (PICKERS.indexOf(pickerId) < 0) return;
            // DOUBLE-ENTER, done without a timer.
            //
            // The 1st Enter on a picker button opens its menu (a <button data-toggle="dropdown"> is
            // activated by Enter — that is what forced the double press in the first place). If the
            // menu is now OPEN and the field still holds NO value, this is the 2nd Enter on an
            // unanswered field: the cashier is saying "nothing here", so skip ahead.
            //
            // Using the menu's open state rather than a timing window means there is no guess about
            // how fast two presses count as one gesture, AND the open menu is visible feedback that
            // the first press registered — which a timer can never give.
            var $bs = $(this).closest('.bootstrap-select');
            if ($bs.hasClass('open')) {
                if ($sel.val()) return;              // a value is highlighted/chosen — the picker owns this Enter
                e.preventDefault();
                $bs.removeClass('open');             // close it; the answer is "skip"
                var jumped = skipAhead(pickerId);
                if (jumped === false) return;        // handled (moved to checkout)
                if (jumped) { focusField(jumped); return; }
            }
            // CLOSED and EMPTY: OPEN the menu so the list can actually be seen and chosen from.
            // Enter used to walk past the whole catalogue without ever showing it.
            //
            // Opened explicitly, not by leaving the event alone and hoping the browser turns
            // Enter-on-a-button into a click — that implicit activation is not reproducible everywhere.
            //
            // Shift+Enter is excluded: it means "go back", and must be able to reverse out of an
            // empty picker rather than opening it.
            //
            // The "nothing here, move on" gesture is unchanged; it happens on the SECOND Enter (the
            // branch above, menu open), which also gives the operator visible confirmation.
            if (!$sel.val() && !e.shiftKey) {
                // NATIVE click — the plugin has no toggle() in 1.6.2, and an unknown method there
                // is a silent no-op, so a try/catch around it guards nothing.
                e.preventDefault();
                var oBtn = $bs.find('button')[0];
                if (oBtn) oBtn.click();
                return;
            }
            e.preventDefault();
            var target = nextField(pickerId, e.shiftKey ? -1 : 1);
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
