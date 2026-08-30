/*
 * loose-sell.js — U3: selling a broken pack at the counter.
 *
 * Design: microservices/docs/slices/u3-loose-at-the-till.md
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * THE ONE RULE THAT SHAPES THIS FILE
 *
 * `#sellItems` — the quantity box — stays a PURE NUMBER. It is read numerically in seven places across
 * business.js and pos-keyboard.js, every one shaped `val()*1 > 0 ? val() : 1`. A letter in that box becomes
 * NaN, `NaN > 0` is false, and the line SILENTLY becomes one pack: the customer pays for ten tablets and the
 * shelf loses ten. No error, no log.
 *
 * So the unit is NOT typed. It lives here, in one variable, set three ways — the toggle, F7/Alt+L, or a
 * `5L*CODE` scan — and read by the cart-add path. Seven numeric readers change to zero.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHERE THE PRICE COMES FROM
 *
 * The per-piece rate is fetched from the server (`/looseInfo`), once per product, and cached. The browser
 * only ever MULTIPLIES it. Recomputing `ceilToCents(price × (1 + markup/100) ÷ packSize)` here would be a
 * second implementation of a rounding rule, and the day either copy changes the shop quotes one price on
 * screen and charges another on the receipt.
 */
(function (global, $) {
    'use strict';

    /** The unit for the line being composed. Reset whenever the product changes. */
    var unit = 'PACK';

    /** productId -> {allowLoose, packSize, looseUnit, looseUnitPlural, looseRate, packRate}. One fetch each. */
    var info = {};

    var current = null;   // the loose info for the product on the line right now, or null

    function t(key, fallback) {
        return (typeof global.t === 'function' && global.t(key) !== key) ? global.t(key) : fallback;
    }

    /** The product currently selected on the sale line. */
    function selectedProductId() {
        var v = $('#sellItemDD').val();
        return v ? Number(v) : null;
    }

    function isLoose() { return unit === 'LOOSE' && !!(current && current.allowLoose); }

    /* ── state ────────────────────────────────────────────────────────────────────────────────────────── */

    /**
     * A product was picked. Fetch its pack rules once and cache them.
     *
     * <p>A failure here must NOT block the sale: the till falls back to pack-only, which is exactly what it
     * does today. Losing a hint is a degraded screen; refusing the line would be a stopped counter.
     */
    function onProductPicked(productId) {
        unit = 'PACK';                 // a new product never inherits the last line's unit
        current = null;
        render();
        if (!productId) return;

        if (info[productId]) { current = info[productId]; render(); return; }

        $.get(serverContext + 'looseInfo', { productId: productId })
            .done(function (resp) {
                var d = (typeof apiOk === 'function' && apiOk(resp)) ? apiData(resp) : null;
                info[productId] = d || { allowLoose: false };
                if (selectedProductId() === productId) { current = info[productId]; render(); }
            })
            .fail(function () {
                info[productId] = { allowLoose: false };
                if (selectedProductId() === productId) { current = info[productId]; render(); }
            });
    }

    /** Set the unit. Refuses — visibly — on a product that may not be split. */
    function setUnit(next) {
        if (next === 'LOOSE' && !(current && current.allowLoose)) {
            // Inert, but not silent: a cashier who presses the key deserves to learn why nothing happened.
            if (typeof showFormError === 'function') {
                showFormError(t('ui.js.looseNotAllowed', 'This product is not sold by the piece.'));
            }
            return false;
        }
        unit = next;
        render();
        if (typeof calculateNetSell === 'function') calculateNetSell();
        return true;
    }

    function toggleUnit() { return setUnit(unit === 'LOOSE' ? 'PACK' : 'LOOSE'); }

    /**
     * U8b — stamp `packSizeSnapshot` on a cart line even when it is sold as a PACK.
     *
     * <p>A prescription's quantity is DERIVED — dose x frequency x duration — so it can only ever be a count
     * of TABLETS. Two packs of ten dispensed against a fifteen-tablet script is therefore twenty tablets, and
     * recording "2" understates the register exactly as the loose bug did.
     *
     * <p>Converting needs the pack size on the line, and until now only LOOSE lines carried it. Harmless on
     * the way to the server: `packSizeSnapshot` is server-populated and ignored inbound (see SellDTO), so
     * this is a display/record aid, never an input the server trusts.
     */
    function stampPackSize(line) {
        if (line && current && current.allowLoose && current.packSize > 1 && line.packSizeSnapshot == null) {
            line.packSizeSnapshot = current.packSize;
        }
        return line;
    }

    /* ── the hint line — the feature ──────────────────────────────────────────────────────────────────── */

    function pieces() {
        var n = Number($('#sellItems').val());
        return (n > 0) ? n : 0;
    }

    /** packs = pieces ÷ packSize. Display only — the SERVER derives the stored quantity. */
    function packsFor(n) {
        if (!current || !current.packSize) return n;
        return Math.round((n / current.packSize) * 10000) / 10000;
    }

    /** total = wholePacks × packRate + remainder × looseRate. Mirrors the server so the hint matches the bill. */
    function lineTotal(n) {
        if (!current) return 0;
        var whole = Math.floor(n / current.packSize);
        var rem = n % current.packSize;
        return whole * Number(current.packRate) + rem * Number(current.looseRate);
    }

    function render() {
        var $wrap = $('#sellUnitWrap'), $hint = $('#sellLooseHint');
        if (!$wrap.length) return;

        if (!(current && current.allowLoose)) {
            // Absent, not disabled — the ordinary till is unchanged.
            $wrap.hide();
            $hint.hide().empty();
            return;
        }

        $wrap.show();
        $('#sellUnitLoose').text(current.looseUnitPlural || current.looseUnit || t('ui.piece', 'Piece'));
        $('#sellUnitPack').toggleClass('active', unit === 'PACK');
        $('#sellUnitLoose').toggleClass('active', unit === 'LOOSE');

        var n = pieces();
        if (unit !== 'LOOSE' || !n) { $hint.hide().empty(); return; }

        var noun = (n === 1 ? (current.looseUnit || '') : (current.looseUnitPlural || current.looseUnit || ''));
        var each = Number(current.looseRate).toFixed(2);
        var packs = packsFor(n);
        var text = n + ' ' + noun + ' · ' + each + ' ' + t('ui.each', 'each')
                 + ' · ' + packs + ' ' + t('ui.ofAPack', 'of a pack')
                 + ' · ' + lineTotal(n).toFixed(2);
        // escHtml: the unit noun is tenant data and reaches the DOM (XSS-safe rendering standard).
        $hint.text(text).show();
    }

    /* ── what the cart line carries ───────────────────────────────────────────────────────────────────── */

    /**
     * The two fields a loose line adds to the payload.
     *
     * <p>`quantity` is still sent, because every existing display path reads it — but the SERVER IGNORES IT
     * on a LOOSE line and derives it from `soldQuantity ÷ packSize`. So a browser that gets the conversion
     * wrong cannot mis-sell; it can only mis-display.
     */
    function decorate(line) {
        if (!isLoose()) return line;
        var n = pieces();
        if (!(n > 0)) return line;
        line.soldUnit = 'LOOSE';
        line.soldQuantity = n;
        line.quantity = packsFor(n);
        line.soldRate = Number(current.looseRate);
        line.packSizeSnapshot = current.packSize;
        // The NOUN, so the cart grid can say "5 tablets" without a second lookup. The stored row gets it
        // from the product on read (getUserSell enriches itemName the same way); a cart line has not been
        // stored yet, so it carries its own.
        line.looseUnit = current.looseUnit;
        line.looseUnitPlural = current.looseUnitPlural;
        return line;
    }

    /**
     * What the TILL's own line maths must use for a loose line.
     *
     * <p>⚠ THE DEFECT THIS FIXES. `sellLineMath` computes `qty x rate`, and the quantity box on a loose line
     * holds PIECES while the rate box holds the PACK price — so five tablets of a 120.00 pack computed as
     * 5 x 120 = <b>600.00</b>. The hint line said 60.00 and the cart row said 600.00, on the same screen, at
     * the same moment. The stock guard was wrong the same way: it compared 5 PIECES against an on-hand
     * figure counted in PACKS and refused sales the shop could make.
     *
     * <p>The substitution mirrors the server exactly — quantity becomes packs, and the rate becomes
     * `lineTotal / packs` — so the cart, the running total and the stock check all agree with the invoice
     * the server will write.
     *
     * @return {{qty:number, rate:number}} or null when this is an ordinary line
     */
    function lineOverride() {
        if (!isLoose()) return null;
        var n = pieces();
        if (!(n > 0)) return null;
        var packs = packsFor(n);
        if (!(packs > 0)) return null;
        var total = lineTotal(n);
        return { qty: packs, rate: total / packs };
    }

    /** A whole number of pieces, mirroring the server's refusal rather than replacing it. */
    function validate() {
        if (!isLoose()) return true;
        var n = pieces();
        if (n !== Math.floor(n)) {
            if (typeof showFormError === 'function') {
                showFormError(t('ui.js.loosePiecesWhole', 'Pieces must be a whole number.'));
            }
            $('#sellItems').addClass('alert-danger').focus();
            return false;
        }
        return true;
    }

    function reset() { unit = 'PACK'; render(); }

    /* ── the keyboard: F7, with Alt+L as the alias ────────────────────────────────────────────────────── */

    /*
     * ⚠ MATCHED ON `event.code`, NEVER `event.key`.
     *
     * `e.key` is the character the LAYOUT produces: on an Arabic or Urdu keyboard the physical L key sends
     * 'ل', so `e.key === 'l'` never fires. `e.code === 'KeyL'` is the same on every layout on earth, and this
     * platform ships in six languages including ar and ur.
     *
     * (The existing Alt+s/p/r/e/c aliases in pos-keyboard.js still match on e.key and therefore do nothing on
     * such a layout. Pre-existing, logged in the U3 slice doc, deliberately not fixed here.)
     */
    function onKeyDown(e) {
        if (!$('#sellItemDD').length) return;                 // not the sale screen
        if (e.ctrlKey || e.metaKey) return;
        var isF7 = (e.key === 'F7' || e.code === 'F7');
        var isAltL = (e.altKey && e.code === 'KeyL');
        if (!isF7 && !isAltL) return;
        if (!(current && current.allowLoose)) return;         // inert on an ordinary product — no surprise
        e.preventDefault();
        toggleUnit();
    }

    $(function () { document.addEventListener('keydown', onKeyDown, false); });

    global.LooseSell = {
        onProductPicked: onProductPicked,
        setUnit: setUnit,
        toggle: toggleUnit,
        decorate: decorate,
        stampPackSize: stampPackSize,
        lineOverride: lineOverride,
        validate: validate,
        reset: reset,
        render: render,
        isLoose: isLoose,
        info: function () { return current; },
        /** Used by the scan path: force the unit after a `5L*CODE` entry. */
        applyScanUnit: function (u) { if (u === 'LOOSE') setUnit('LOOSE'); else unit = 'PACK'; }
    };

    /** The toggle's onclick handlers, kept global because the markup is Thymeleaf. */
    global.setSellUnit = function (u) { setUnit(u); };
})(window, jQuery);
