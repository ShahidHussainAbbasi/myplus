/**
 * submit-once.js — DUP-1: one submit per intent.
 *
 * WHY THIS FILE EXISTS. A production shop registered **148 products from a single submit**. Nothing along the
 * path stopped it:
 *
 *   • `keyboard-forms.js` turns Enter-past-the-last-field into `$('#add<Entity>').click()`, for all 21 CRUD
 *     modals in the four dashboards;
 *   • nothing anywhere checked `KeyboardEvent.repeat`, so a HELD Enter auto-repeats at ~30/s and every repeat
 *     was a fresh submit (~5 seconds of it is 148);
 *   • `callAjax` is deliberately `nonBlocking: true` (PERF-13 — a save must not freeze the till for 80-170 ms),
 *     so no overlay stood in the way, and the modal stays open for the whole round trip because `closeModal`
 *     runs in the success callback;
 *   • the product name is deliberately not unique and a blank SKU skips the only duplicate check, so the server
 *     accepted every one.
 *
 * Three layers live here, and they are not redundant — each covers what the one above it cannot:
 *
 *   LAYER 1  drop auto-repeat, so the hardware cannot speak for the operator. Cannot help a real double-click.
 *   LAYER 2  coalesce identical in-flight writes, so a double-click costs one POST. Cannot survive a reload.
 *   LAYER 3  a per-form-fill idempotency key the SERVER enforces (product create: V16 + ProductController).
 *            `FormKeys` below is the client half; it needs no cooperation from the keyboard at all.
 *
 * Deliberately NOT here: any re-introduction of a blocking overlay on writes. That would "fix" this by making
 * every save feel slower, which is the trade-off PERF-13 already rejected on purpose.
 */
(function (global, $) {
    'use strict';

    /* ══ LAYER 1 ═══════════════════════════════════════════════════════════════════════════════════════════
     *
     * A held key is ONE intent. `e.repeat` is the browser telling us exactly which events came from the
     * operator and which from the keyboard's auto-repeat, so this needs no heuristics and no timers.
     *
     * ⚠ REGISTERED ON `window`, IN THE CAPTURE PHASE, AND BOTH HALVES MATTER.
     *
     * The worst case is Enter held while focus sits ON the submit button. `enter-chain.js` never sees that —
     * the button's id is not in its chain, so it returns early (enter-chain.js:252) and the BROWSER fires the
     * repeated `click` itself. Only a listener that gets there first can stop it, and capture order runs
     * window → document → … , so registering on `window` beats every existing document-level capture handler
     * REGARDLESS of script order. Putting this on `document` instead would make it silently dependent on which
     * <script> tag came first — the kind of ordering a later reader cannot see and will eventually break.
     */
    global.addEventListener('keydown', function (e) {
        if (!e.repeat) return;                       // a first press is always the operator

        var k = e.key;
        if (k !== 'Enter' && k !== ' ') return;      // only the two keys that ACTIVATE things

        var el = e.target;
        var tag = el && el.tagName;

        // A textarea keeps its plain Enter — that is what the control is for, and holding it to add blank lines
        // is the operator's business. Same carve-out enter-chain already makes.
        if (tag === 'TEXTAREA') return;

        var isButton = tag === 'BUTTON'
            || (tag === 'INPUT' && /^(submit|button|image|reset)$/i.test(el.type || ''))
            || (el && el.getAttribute && el.getAttribute('role') === 'button');

        // A held SPACE is only an activation on a button. In any text field it is typing, and swallowing it
        // would stop an operator holding space in a description box — a much worse bug than the one being
        // fixed. Enter is safe to drop anywhere outside a textarea, because nothing uses repeated Enter.
        if (k === ' ' && !isButton) return;

        e.preventDefault();
        e.stopImmediatePropagation();                // nothing downstream should see a phantom press
    }, true);


    /* ══ LAYER 2 ═══════════════════════════════════════════════════════════════════════════════════════════
     *
     * In-flight request de-duplication (request coalescing), as a DECORATOR over `$.ajax`.
     *
     * WHY NOT `$.ajaxPrefilter`, the obvious hook: a prefilter can only cancel a request by `abort()`, and an
     * abort runs the call site's `error` handler. The operator would be shown "Could not save the product."
     * immediately after a save that SUCCEEDED. There are 66 direct `$.ajax` POST sites across 21 files plus
     * `callAjax` and `jsonPost`, and not one of them can be told "this particular failure is fake".
     *
     * A decorator can hand the duplicate the FIRST request's own promise, so the second press resolves with the
     * first response: one POST, one success message, no false error, and no edit to any of those 66 sites.
     */

    /** signature -> the live jqXHR. Entries are removed in `.always()`, so nothing can go stale. */
    var inFlight = {};

    /** Coalesced-away duplicates, counted for the gate (and for anyone debugging "my click did nothing"). */
    var coalesced = 0;

    function isWriteMethod(m) {
        m = String(m || 'GET').toUpperCase();
        return m === 'POST' || m === 'PUT' || m === 'PATCH';
    }

    /**
     * A stable string for the body, or null when we cannot be sure what the body IS.
     *
     * Null means "do not dedupe this", and that is the right default: FormData, Blobs and streams cannot be
     * compared cheaply or safely, and guessing that two of them are equal risks dropping a real save. Better to
     * leave such a call alone and let layers 1 and 3 cover it.
     */
    function bodySignature(data) {
        if (data == null) return '';
        if (typeof data === 'string') return data;
        if (global.FormData && data instanceof global.FormData) return null;
        if (global.Blob && data instanceof global.Blob) return null;
        if ($.isPlainObject(data) || Array.isArray(data)) {
            try { return JSON.stringify(data); } catch (e) { return null; }
        }
        return null;
    }

    /** Run a jQuery-style callback option, which may be one function or an array of them. */
    function fire(cb, ctx, args) {
        if (!cb) return;
        var list = Array.isArray(cb) ? cb : [cb];
        for (var i = 0; i < list.length; i++) {
            if (typeof list[i] === 'function') list[i].apply(ctx, args);
        }
    }

    var nativeAjax = $.ajax;

    $.ajax = function (url, options) {
        // jQuery accepts ajax(options) and ajax(url, options). Normalise without mutating the caller's object.
        if (typeof url === 'object' && url !== null) { options = url; url = undefined; }
        options = $.extend({}, options || {});
        if (typeof url === 'string') options.url = url;

        var method = options.type || options.method;

        // Opt-out for a caller that genuinely must fire the same write twice in one round trip. Nothing in the
        // app needs it today; it exists so the honest answer to "my duplicate is legitimate" is one flag rather
        // than a reason to delete this guard.
        if (options.dedupe === false || !isWriteMethod(method)) {
            return nativeAjax.call($, options);
        }

        var body = bodySignature(options.data);
        if (body === null) return nativeAjax.call($, options);

        var sig = String(method).toUpperCase() + ' ' + (options.url || '') + ' ' + body;
        var live = inFlight[sig];

        if (live) {
            /*
             * THE SAME WRITE IS ALREADY ON THE WIRE. Attach this caller's handlers to it and hand back that
             * jqXHR, so the caller behaves exactly as if its own request had run — including returning a real
             * jqXHR, which call sites chain `.done()`/`.fail()` onto.
             *
             * `beforeSend` is deliberately NOT run for the duplicate: nothing is being sent.
             */
            coalesced++;
            var ctx = options.context || options;
            if (options.success) live.done(function (d, s, x) { fire(options.success, ctx, [d, s, x]); });
            if (options.error) live.fail(function (x, s, t) { fire(options.error, ctx, [x, s, t]); });
            // `complete(jqXHR, textStatus)` — and the jqXHR IS the live request, in both the success and the
            // failure case, so there is nothing to disambiguate out of always()'s first argument.
            if (options.complete) live.always(function (a, s) { fire(options.complete, ctx, [live, s]); });
            return live;
        }

        var jqXHR = nativeAjax.call($, options);
        inFlight[sig] = jqXHR;
        jqXHR.always(function () { delete inFlight[sig]; });
        return jqXHR;
    };


    /* ══ LAYER 2b — the affordance ════════════════════════════════════════════════════════════════════════
     *
     * Coalescing is invisible, and an operator who sees nothing happen presses again. So while a write is on
     * the wire, the open modal's submit control goes disabled.
     *
     * A BUTTON, never a field: `disabled` inputs are dropped from FormData, which this codebase has already
     * paid for once (the name fields that stopped submitting on edit). Buttons are not in FormData at all.
     *
     * Driven by the convention `keyboard-forms.js` already relies on, so no list of entity names lives here.
     * Background calls (`global: false`) never reach ajaxSend, so a preload cannot disable anything.
     */
    function openModalSubmit() {
        var $modal = $('.crud-overlay.open').first();
        if (!$modal.length) return null;
        var sel = $modal.attr('data-kbd-submit');
        var $btn = sel ? $(sel) : $('#add' + $modal.attr('id').replace(/Modal$/, ''));
        return $btn.length ? $btn.first() : null;
    }

    $(document).ajaxSend(function (evt, jqXHR, settings) {
        if (!isWriteMethod(settings && settings.type)) return;
        var $btn = openModalSubmit();
        // Only ever disable a button that is currently enabled, and remember it ON THE jqXHR so completion
        // re-enables exactly what this request disabled. Counting in-flight requests instead would re-enable
        // early whenever two unrelated writes overlapped.
        if ($btn && !$btn.prop('disabled')) {
            $btn.prop('disabled', true);
            jqXHR.__submitOnceBtn = $btn;
        }
    });

    $(document).ajaxComplete(function (evt, jqXHR) {
        var $btn = jqXHR && jqXHR.__submitOnceBtn;
        if ($btn) { $btn.prop('disabled', false); delete jqXHR.__submitOnceBtn; }
    });


    /* ══ LAYER 3 (client half) ════════════════════════════════════════════════════════════════════════════
     *
     * One idempotency key per FORM-FILL, so the server can recognise a repeat of the same intent however it
     * arrives — a held Enter, a double-click, a retry after a dropped connection, even a reload.
     *
     * Shared rather than per-form, because the sale already proved the shape (`getSaleIdempotencyKey` in
     * main.js, SF-3) and a second private copy per register is how two implementations of one rule drift apart.
     * The sale keeps its own for now: that is the most load-bearing write in the product and not worth
     * disturbing for tidiness.
     *
     * ⚠ THE LIFECYCLE IS THE WHOLE RISK, AND IT CUTS BOTH WAYS.
     *
     *   retire too late   the next record replays the previous one — an operator catalogues twenty products,
     *                     the shop gets one, and every save reported success. Strictly WORSE than the bug
     *                     this slice fixes, and invisible until someone counts rows.
     *   retire too early  a genuine retry looks like a new record, and the duplicate comes back.
     *
     * So: retire ON SUCCESS ONLY, at the very top of the success handler, BEFORE anything that can throw.
     * (SF-3b was caused by exactly this ordering: a throw between retiring the key and clearing the cart.)
     * A failure keeps the key, which is what makes retrying a timed-out save safe.
     */
    var keys = {};

    function uuid() {
        if (global.crypto && typeof global.crypto.randomUUID === 'function') return global.crypto.randomUUID();
        // Old-browser fallback, same shape as getSaleIdempotencyKey's.
        return 'k-' + Date.now() + '-' + Math.random().toString(36).slice(2, 11);
    }

    global.FormKeys = {
        /** The key for this form-fill, minted on first use. Stable until retired. */
        get: function (name) {
            if (!keys[name]) keys[name] = uuid();
            return keys[name];
        },
        /** Called after a SUCCESSFUL save: the next record is a new intent and needs a new key. */
        retire: function (name) { delete keys[name]; },
        /** Read without minting — for the gate, and for asserting that a key really rotated. */
        peek: function (name) { return keys[name] || null; }
    };

    // Exposed for the gate: lets a spec prove a duplicate was dropped rather than merely that one row exists.
    global.SubmitOnce = {
        coalescedCount: function () { return coalesced; },
        inFlightCount: function () { return Object.keys(inFlight).length; }
    };
})(window, jQuery);
