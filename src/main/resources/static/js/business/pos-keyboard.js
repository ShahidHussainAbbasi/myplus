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
    /*
     * The line chain, now opening with WHO IS BUYING (task #13).
     *
     * The customer used to be the first stop of CHECKOUT, i.e. after every line was rung. That is
     * defensible for a walk-in and wrong for everyone else: the customer decides contract and tier
     * pricing, the credit limit and any store credit, so choosing them last meant each line was priced
     * against a customer the system did not know it had, and then re-priced once it did. Choosing first
     * makes the first price the right price.
     *
     * `sellCN` / `sellCC` are the manual-entry pair and are hidden in select mode (and vice versa);
     * usable() filters whichever is off-screen, so exactly one of the two appears in the walk.
     *
     * A walk-in is NOT slowed down. The picker may be left blank and the double-Enter escape below skips
     * it — which is the rule that makes putting it first safe to impose at all. Without that escape this
     * change would put a mandatory stop in front of every cash sale.
     */
    var CHAIN = ['sellCustomerDD', 'sellCN', 'sellCC',
                 'sellItemDD', 'sellItems', 'sellSellRate', 'sellDiscountTypeDD', 'sellDiscount'];

    /** Every dropdown the chain walks. These are bootstrap-select widgets, so they need the
     *  selection hook below rather than a keystroke handler — see D-23 in the P5 design. */
    var PICKERS = ['sellItemDD', 'sellDiscountTypeDD', 'sellCustomerDD', 'sellPayMethod'];

    /**
     * A sale has TWO phases, and until now only the first had a keyboard.
     *
     *   LINES     who is buying → scan box / item → qty → price → discount → commit  (repeat)
     *   CHECKOUT  how they pay → how much → complete
     *
     * "Who is buying" moved from the head of CHECKOUT to the head of LINES in task #13 — see the CHAIN
     * comment above for why. The two-phase shape is unchanged; only the customer crossed the line.
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
        // sellCustomerDD / sellCN / sellCC deliberately NOT here any more — they moved to the head of
        // CHAIN (task #13). Leaving them in both would make the cashier name the customer twice per sale:
        // once before the lines and again on the way to payment.
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
    /**
     * WHERE THE PAY METHOD SENDS THE CURSOR.
     *
     * The checkout chain walks the form's field ORDER, which is a layout decision and not a workflow one.
     * On a cash sale that put the cursor in Trade discount — a field most cash sales never touch — while
     * the field the method actually requires, the amount handed over, sat two stops further on. The
     * cashier's next keystroke and the chain's next stop disagreed on every single sale.
     *
     * So the METHOD chooses. Each entry names the field that method cannot be completed without:
     *   cash and its equivalents  the amount tendered, because that is what computes the change
     *   an account sale           the due date, because a balance nobody dated cannot be chased
     *
     * ⚠ THE OPTIONAL FIELDS STAY REACHABLE. Trade discount, store credit and insurance are still in
     * CHECKOUT and still walked: Shift+Enter reverses into them, and Enter from the money field carries
     * on through whatever else the tenant has switched on. They stop being ON THE CRITICAL PATH; they do
     * not stop existing. A tenant only sees those fields because they asked for them, and a keyboard that
     * made them unreachable would be a worse bargain than the one it replaced.
     *
     * A method absent from this table falls back to the positional walk — the previous behaviour, which
     * is the right default for anything nobody has thought about yet.
     */
    var AFTER_METHOD = {
        CASH:          'sellRec',
        CARD:          'sellRec',
        WALLET:        'sellRec',
        BANK_TRANSFER: 'sellRec',
        SPLIT:         'sellRec',
        CREDIT:        'dueDateTemp'
    };

    /**
     * The field the CURRENT pay method wants next, or null to walk positionally.
     *
     * Falls back whenever the named field is not usable — hidden by configuration, or not yet rendered.
     * A rule that pointed at a field nobody can see would strand the cursor, which is worse than the
     * ordering it set out to improve.
     */
    function afterPayMethod() {
        var wanted = AFTER_METHOD[String($('#sellPayMethod').val() || '').toUpperCase()];
        return (wanted && usable(wanted)) ? wanted : null;
    }
    /** Exposed so the gate can assert the ROUTING RULE rather than a rendering of it. */
    global.posAfterPayMethod = afterPayMethod;

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
        // Empty cart: back to the GOODS, not to the entry point. Since the entry point became the customer,
        // routing here would bounce customer → item → customer and trap the cashier in a loop with no way
        // forward. "Leave them where the items are typed" is what this line has always meant.
        if (!global.data || global.data.length === 0) { focusGoodsEntry(); return false; }
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
    /**
     * Where the cursor lands when the sale screen opens: <b>the customer</b>.
     *
     * <h3>A decision taken deliberately, against my first instinct</h3>
     * This originally landed on the scan box, on the reasoning that most sales are walk-ins and starting on
     * the customer would add a stop to every one of them. The product owner's ruling was the opposite, and
     * the ruling is right for a reason the reasoning missed: a sale is priced by WHO is buying — contract and
     * tier prices, credit limit, store credit — so a cashier who rings lines first has been pricing against a
     * customer the system did not know it had. Starting here makes the first price the right price, every
     * time, instead of relying on somebody remembering to look down the screen.
     *
     * <h3>The walk-in still is not slowed down</h3>
     * The picker is in PICKERS, so it behaves exactly like the item picker: a blank one is skipped with the
     * same double Enter a cashier already knows. One familiar keystroke, not a new rule to learn — which is
     * what makes customer-first affordable on a busy counter.
     *
     * <p>Order of preference, so the entry point degrades sensibly rather than landing nowhere: the customer
     * picker, then the manual-name field when the operator is in manual mode (the picker is hidden then), and
     * only then the goods — for a tenant whose customer block is switched off entirely.
     */
    function focusEntryPoint() {
        if (usable('sellCustomerDD')) { focusField('sellCustomerDD'); return; }
        if (usable('sellCN')) { focusField('sellCN'); return; }
        focusGoodsEntry();
    }

    /**
     * Where the GOODS are typed — the scan box, or the item picker when scanning is off.
     *
     * <h3>Why this is separate from focusEntryPoint()</h3>
     * They used to be the same function, and separating them fixes a loop that appeared the moment the entry
     * point moved to the customer:
     *
     *   empty item picker + double Enter → goToCheckout() → cart is empty, so fall back to the entry point
     *   → which is now the CUSTOMER → skip the customer → back to the item picker → round again.
     *
     * The cashier could not leave. {@code goToCheckout}'s own comment already said what the fallback meant —
     * "leave them where the items are typed" — and that stopped being true when the entry point changed
     * underneath it. Two callers wanted two different answers from one function, which is the whole bug.
     */
    function focusGoodsEntry() {
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

            // Forward from the PAY METHOD, the method decides — see AFTER_METHOD. Backwards is left
            // positional on purpose: Shift+Enter means "the field before this one", and a shortcut that
            // reversed into somewhere other than where you came from is disorienting.
            var target = (this.id === 'sellPayMethod' && !e.shiftKey && afterPayMethod())
                || walk(CHECKOUT, this.id, e.shiftKey ? -1 : 1);
            // Past the last checkout field = complete the sale. Same handler as the button and F2, so
            // the credit-limit checks, the due-date rule and the idempotency key all still apply —
            // this only decides WHEN it is called.
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
             * #sellCustomerDD and #sellPayMethod both lived in CHECKOUT at the time. (#sellCustomerDD has
             * since moved to CHAIN — task #13 — which changes nothing here, and that is the point of the
             * paragraph below.) So the double-Enter escape below —
             * "open it, and if I press again with nothing chosen, move on" — worked on the item picker
             * and existed nowhere else. On a walk-in cash sale the cursor landed on the customer list
             * and no keystroke would leave it.
             *
             * ONE handler, not two. There used to be a second keydown handler on this very same
             * selector, a few lines up, that took only #sellCustomerDD and #sellPayMethod. Widening
             * this guard to cover them made both fire on the same keystroke: that one opened the menu,
             * this one saw it open, read that as the SECOND Enter and skipped ahead — a single press
             * doing the work of two, so the customer list could never be opened from the keyboard at
             * all. Two handlers on one selector cannot be reasoned about separately, so there is one.
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
            var $bs    = $(this).closest('.bootstrap-select');
            var isOpen = $bs.hasClass('open');
            var has    = !!$sel.val();

            if (isOpen && has) return;               // a value is highlighted/chosen — the picker owns this Enter
            // Same rule as the search-box path: a row the operator ARROWED onto is a choice, and the
            // plugin's own opening highlight is not. See the kbdArrowed note below.
            if (isOpen && $bs.data('kbdArrowed')) return;
            if (isOpen) {
                e.preventDefault();
                $bs.removeClass('open');             // close it; the answer is "skip"
                var jumped = skipAhead(pickerId);
                if (jumped === false) return;        // handled (moved to checkout)
                if (jumped) { focusField(jumped); return; }
                return;                              // nothing ahead: STAY PUT, menu already closed
            }

            /*
             * CLOSED, and already holding a value — ACCEPT IT AND MOVE ON.
             *
             * The commonest checkout there is, and it once had no way forward. Cash is pre-selected, so
             * #sellPayMethod holds a value from the moment the screen opens. Advancing was wired only to
             * `changed.bs.select`, which fires on a CHANGE — and keeping the default is not a change. So
             * Enter fell through to the open-the-menu branch, which does nothing on a full picker, and
             * the cashier sat on the pay method with no keystroke that would leave it.
             *
             * "It already has the right answer" is the reason to move on, not the reason to stop.
             */
            if (has && !e.shiftKey) {
                e.preventDefault();
                var fwd = walk(chainFor(pickerId), pickerId, 1);
                if (fwd) { focusField(fwd); return; }
                // Past the end. What that MEANS differs by chain, and the difference is deliberate:
                //   line chain — the operator has finished a line, so commit it.
                //   checkout   — do NOT complete the sale. This branch fires on a picker nobody
                //                touched (cash is pre-selected); if the fields after it have not
                //                rendered yet walk() answers null for a moment, and finishing on that
                //                would turn a mistimed keystroke into a completed transaction. F2 says
                //                finish and means it.
                if (chainFor(pickerId) === CHAIN) commitLine();
                return;
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
            // Shift+Enter on an empty picker: reverse out of it rather than opening it.
            e.preventDefault();
            var target = walk(chainFor(pickerId), pickerId, e.shiftKey ? -1 : 1);
            if (target === null) {
                if (chainFor(pickerId) === CHAIN && !e.shiftKey) commitLine();
                return;
            }
            focusField(target);
        });

        /*
         * DID THE OPERATOR NAVIGATE THE LIST THEMSELVES?
         *
         * The one fact that separates "Enter means take this row" from "Enter means I am done here" on a
         * list with nothing typed into it. bootstrap-select highlights a row on open, so the highlight
         * alone proves nothing; an arrow key is the operator moving it.
         *
         * Cleared on every open, so intent never leaks from one visit to the next.
         */
        $(document).on('keydown', '.bs-searchbox input', function (e) {
            if (!onSellScreen()) return;                       // the sale screen's rule, not the app's
            if (e.keyCode !== 38 && e.keyCode !== 40) return;   // ArrowUp / ArrowDown
            $(e.target).closest('.bootstrap-select').data('kbdArrowed', true);
        });

        /*
         * Cleared when the menu OPENS, and the open is always a click on the toggle — including from the
         * keyboard, because the Enter-opens-the-menu branch above performs a real `oBtn.click()`.
         *
         * ⚠ There is deliberately no `shown.bs.select` handler here. I wrote one, then checked: this
         * build of bootstrap-select fires NO `bs.select` events at all — grep finds none. It would have
         * been dead code that reads as the primary reset, leaving the real one looking like a fallback
         * nobody needs, which is how a later edit deletes the line that was doing the work.
         */
        $(document).on('click', '.bootstrap-select > button', function () {
            if (!onSellScreen()) return;
            $(this).closest('.bootstrap-select').data('kbdArrowed', false);
        });

        /*
         * THE SECOND ENTER — which never arrives at the button.
         *
         * The handler above opens the menu, and bootstrap-select then moves focus INTO the live-search
         * box it renders inside the menu (searchable-selects.js sets data-live-search on every select
         * on the page). So the second Enter is delivered to that input, not to the button — and the
         * "nothing here, move on" branch above, bound to `.bootstrap-select > button`, could not run.
         *
         * The escape was therefore unreachable from the keyboard on EVERY picker, including the item
         * one it was written for. It read as implemented, had a passing test either side of it — the
         * menu does open, and a chosen value does advance — and the one gesture in between was dead.
         * A test that presses Enter once cannot tell an open menu from a working escape.
         *
         * Enter here means three different things, and the search text is what distinguishes them:
         *   typed something   the plugin owns it — Enter picks the highlighted match
         *   empty + no value  "nothing here" -> skip ahead (the rule)
         *   empty + a value   accept what is already chosen and move on
         *
         * That last case is not merely tidy: re-selecting the value a picker already holds fires no
         * change event, so leaving it to the plugin closes the menu and moves nothing.
         */
        document.addEventListener('keydown', function (e) {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (e.key !== 'Enter' || e.shiftKey) return;

            var $box = $(e.target).closest('.bs-searchbox').find('input');
            if (!$box.length || e.target !== $box[0]) return;

            var $bs = $(e.target).closest('.bootstrap-select');
            var $sel = $bs.prev('select');
            var pickerId = $sel.attr('id');
            if (PICKERS.indexOf(pickerId) < 0) return;

            // The operator is filtering. Their Enter belongs to the list, not to the chain.
            if ($box.val()) return;

            /*
             * ⚠ AN EMPTY SEARCH BOX IS NOT AN EMPTY ANSWER — the operator may have ARROWED to a row.
             *
             * Arrowing down is the mouse-free way to choose and it types nothing, so the empty-box rule
             * below read it as "nothing here, move on": the keystroke was swallowed, the menu closed and
             * the cursor advanced WITHOUT SELECTING ANYTHING. The cashier lands on the next field with no
             * product chosen, which is worse than doing nothing.
             *
             * ⚠ AND `.active` CANNOT BE THE TEST. bootstrap-select highlights a row the moment the menu
             * opens, so "something is highlighted" is true before the operator has touched anything —
             * handing Enter back then reinstates exactly what this handler was built to stop: on an
             * untouched list, Enter selecting whoever happened to be highlighted and invoicing the sale
             * to them.
             *
             * The question is INTENT, and only navigation answers it. `kbdArrowed` is set by an arrow key
             * and cleared every time the menu opens, so it means "the operator moved the highlight
             * themselves" — and that Enter is a choice.
             */
            if ($bs.data('kbdArrowed')) return;

            /*
             * CAPTURE PHASE, and it has to be.
             *
             * bootstrap-select binds its OWN delegated keydown covering `.bs-searchbox input` on
             * document, and its script loads first — so on the bubble phase it runs before anything
             * added here, and its Enter handler CLICKS the active row. On an unfiltered list that
             * silently picks whichever option happens to be highlighted, which on the customer picker
             * means the sale is invoiced to a customer nobody chose.
             *
             * Running first and stopping the event is the only way to take this keystroke back;
             * stopPropagation alone would be too late, because "too late" is the whole problem.
             */
            e.preventDefault();
            e.stopPropagation();
            if (e.stopImmediatePropagation) e.stopImmediatePropagation();
            $bs.removeClass('open');        // what bootstrap's own clearMenus() does

            if ($sel.val()) {               // already answered: accept it and move on
                var fwd = walk(chainFor(pickerId), pickerId, 1);
                if (fwd) { focusField(fwd); return; }
                if (chainFor(pickerId) === CHAIN) commitLine();
                return;
            }

            var jumped = skipAhead(pickerId);
            if (jumped === false) return;   // handled (moved to checkout)
            if (jumped) { focusField(jumped); return; }
            focusField(pickerId);           // nothing ahead: stay put, menu closed
        }, true);

        /*
         * CHOOSING IS ANSWERING — every picker hands the cursor on once its value settles.
         *
         * This existed for the item picker alone, where the async loadStock()/quote fills price and
         * stock and the cursor then lands in Qty. The checkout pickers had nothing: choosing a customer
         * left the cursor parked on the picker's button, so the cashier chose and then had to press
         * Enter AGAIN to move — and after the arrow-key fix that second press was the only way forward,
         * which is what a till reported.
         *
         * One handler for all of them, with the destination coming from walk() over the picker's own
         * chain. That is also why "what comes after the pay method" needs no rules here: usable() hides
         * what the method makes irrelevant, so Cash lands on the amount tendered and an account sale on
         * the due date, purely because those are the fields on screen.
         *
         * The delay is unchanged and still earns its place: the customer picker triggers a credit-standing
         * read, and moving the cursor before that lands would fight it.
         */
        $(document).on('change', PICKERS.map(function (id) { return '#' + id; }).join(','), function () {
            if (!enabled() || !onSellScreen() || blocked()) return;
            if (!$(this).val()) return;
            var pickerId = this.id;
            global.setTimeout(function () {
                if (!enabled() || !onSellScreen() || blocked()) return;

                /*
                 * ASK WHETHER THE CASHIER HAS MOVED ON — do not try to enumerate where the picker leaves
                 * the cursor.
                 *
                 * This used to whitelist the places focus was EXPECTED to be after a selection: the
                 * button, the hidden <select>, or nowhere. bootstrap-select does not guarantee any of
                 * them — depending on browser and on whether the pick came from the mouse, the live
                 * search or a menu key, focus can land on the menu anchor or stay in the search input.
                 * When it did, the whitelist missed, the advance was skipped, and the cashier was left
                 * on the picker with Enter doing nothing. Reported from a real till; invisible to the
                 * test, which focused the button itself before pressing Enter and so never took the
                 * path a person takes.
                 *
                 * The guard's PURPOSE was never "is focus at the picker" — it was "has the cashier
                 * already chosen where to type?", so a delayed jump cannot yank the cursor out of Price
                 * mid-keystroke. That question is answered by asking whether focus is in a typing field
                 * OUTSIDE the picker, which is a closed set and does not depend on plugin internals.
                 */
                var a = document.activeElement;
                var list = chainFor(pickerId);
                var here = list.indexOf(pickerId);
                var at = (a && a.id) ? list.indexOf(a.id) : -1;

                /*
                 * ONLY a LATER FIELD OF THIS LINE counts as "the cashier has moved on".
                 *
                 * The concern this guard exists for is precise and narrow: someone picks an item and
                 * immediately starts typing a PRICE, and a quarter of a second later the cursor is yanked
                 * back to Qty mid-keystroke. That is Price and Discount — fields further down this very
                 * chain. Nothing else.
                 *
                 * Anywhere else is not a decision. The barcode box in particular is where this screen
                 * PARKS the cursor between lines, so treating it as "chosen" — which the previous version
                 * did, by testing for any <input> outside the picker — stood the advance down on the one
                 * screen state that is most common. That is the bug a till reported.
                 */
                if (at > here) return;

                var next = (pickerId === 'sellPayMethod' && afterPayMethod())
                    || walk(list, pickerId, 1);
                if (next) focusField(next);
                // Deliberately NOT committing the line or completing the sale when there is nothing
                // ahead. Choosing a value is not the same as saying "and that is the last thing I want
                // to do" — F2 finishes a sale, and a dropdown selection must never stand in for it.
            }, 250);
        });
    });
})(window);
