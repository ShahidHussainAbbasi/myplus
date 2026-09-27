/*
 * order-type.js — RST-R2a: how this sale is served. Dine-in, take-away, delivery.
 *
 * Design: microservices/docs/slices/rst-r2a-order-types.md
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY A SEPARATE FILE, AND NOT PART OF counter.js
 *
 * Order type is a property of a SALE, not of the tile counter. A restaurant that never switches the counter
 * on still takes take-away orders, and a retailer with the counter on has no use for service modes at all.
 * Putting it in counter.js would tie one to the other and make "turn the tiles off" quietly mean "stop
 * recording how food left the shop".
 *
 * It is also the same argument counter.js makes for its own existence: business.js is 6,000+ lines and
 * three sessions edit it. Nothing here needs to be inside it.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * ⚠ THE TWO RULES THIS FILE HOLDS
 *
 *  1. IT IS A PROPERTY, NOT A STATUS. Nothing here may read or write anything about money. A take-away is
 *     normally paid before it is cooked and a dine-in table an hour after — the moment this file starts
 *     asking what is owed, those two clocks become one and a paid order starts looking unmade.
 *  2. SILENCE IS AN ANSWER. A shop without the capability never sees the row and posts NO orderType, which
 *     the server stores as "not recorded". `window.sellOrderType` stays undefined for them, so main.js adds
 *     nothing to the payload and their sale is byte-for-byte what it was before this feature existed.
 */
(function (global, $) {
    'use strict';

    /* The default. Take-away assumes least: recorded as dine-in it would overstate covers, and the reverse
     * merely understates them. Ruled 2026-09-25 as the tenant-wide default rather than a per-shop setting —
     * a shop that is mostly dine-in taps once. */
    var DEFAULT = 'TAKE_AWAY';

    var TYPES = [
        { code: 'DINE_IN',   key: 'ui.js.orderTypeDineIn',   fallback: 'Dine-in' },
        { code: 'TAKE_AWAY', key: 'ui.js.orderTypeTakeAway', fallback: 'Take-away' },
        { code: 'DELIVERY',  key: 'ui.js.orderTypeDelivery', fallback: 'Delivery' }
    ];

    function enabled() {
        // hasCapability fails OPEN when capabilities are unresolved, which is right for rendering: an old
        // token must not blank a screen. The SERVER refuses a type the tenant may not use, and that refusal
        // is the control — this only decides whether to draw the row.
        return typeof global.hasCapability !== 'function' || global.hasCapability('orderTypes');
    }

    function t(key, fallback) {
        return (typeof global.t === 'function' && global.t(key) !== key) ? global.t(key) : fallback;
    }

    /** The chosen type, or undefined when this shop does not use them. main.js reads exactly this. */
    function set(code) {
        global.sellOrderType = code;
        $('.ot-btn').each(function () {
            $(this).attr('aria-pressed', String($(this).attr('data-ot') === code));
        });
    }

    function render() {
        var $wrap = $('#orderTypeWrap');
        if (!$wrap.length) return;
        if (!enabled()) {
            $wrap.hide();
            // ⚠ Clear it too, not just hide it. A tenant that switches the capability off mid-session would
            // otherwise keep posting the last type from a row nobody can see, and the server would refuse
            // every sale — a hidden control still sending data is how a UI gate becomes an outage.
            global.sellOrderType = undefined;
            return;
        }
        $wrap.show();
        if (!$wrap.data('built')) {
            $wrap.html('<span class="ot-label">' + escHtml(t('ui.js.orderTypeLabel', 'Order')) + '</span>'
                + TYPES.map(function (x) {
                    return '<button type="button" class="ot-btn" data-ot="' + x.code + '"'
                        + ' aria-pressed="false">' + escHtml(t(x.key, x.fallback)) + '</button>';
                }).join(''));
            $wrap.data('built', true);
        }
        set(global.sellOrderType || DEFAULT);
    }

    /**
     * Back to the default for the next customer.
     *
     * Called from the cart reset, deliberately: a type left over from the previous sale is the same class of
     * defect as the trade discount that was never cleared and was inherited by the NEXT customer. A service
     * mode is cheaper to get wrong than money, but it is the same mistake.
     */
    function reset() {
        if (!enabled()) { global.sellOrderType = undefined; return; }
        set(DEFAULT);
    }

    $(function () {
        $(document).on('click', '.ot-btn', function () {
            set($(this).attr('data-ot'));
        });
        // Capabilities arrive asynchronously; draw when they land and once now in case they already have.
        $(document).on('capabilities:ready', render);
        render();
    });

    global.OrderTypeBar = { render: render, reset: reset, isEnabled: enabled };
})(window, jQuery);
