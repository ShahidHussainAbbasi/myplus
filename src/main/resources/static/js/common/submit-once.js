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
        // .last(), not .first() — PUR-INLINE. Overlays share one z-index, so the LAST open one in document
        // order is the one on screen and the one being submitted. With ProductModal stacked over
        // PurchaseModal, .first() greyed out the purchase's Save button while the PRODUCT was saving.
        var $modal = $('.crud-overlay.open').last();
        if (!$modal.length) return null;
        var sel = $modal.attr('data-kbd-submit');
        var id = $modal.attr('id');
        // ⚠ NEVER THROW FROM HERE. This runs inside an ajaxSend handler, and jQuery increments
        // jQuery.active BEFORE triggering ajaxSend but decrements it in the completion path — so an
        // exception thrown here aborts jQuery.ajax() with the counter already raised and NEVER lowered.
        // Every later "is the app idle?" check then waits forever on a request that was never sent.
        // All 21 overlays carry an id today; this costs one line and removes the failure mode entirely.
        if (!sel && !id) return null;
        var $btn = sel ? $(sel) : $('#add' + id.replace(/Modal$/, ''));
        return $btn.length ? $btn.first() : null;
    }

    /* ══ LAYER 2c — BLK-2: the pressed control says what it is doing ═════════════════════════════════════
     *
     * "Block the risky action, not the whole user interface." A disabled button that looks exactly like an
     * enabled one tells the operator nothing, so they press again or wonder whether it worked. The control that
     * started the write carries the waiting state instead: disabled, aria-busy, a spinner and "Saving…" /
     * "Posting…", at its own width so nothing moves under the pointer — and the rest of the screen stays usable
     * wherever the form is safe without the veil (design: slices/blk-2-busy-controls.md §2.3).
     *
     * ONE helper for what used to be seven hand-rolled disables. `hold()` returns its own release (a token, not a
     * shared counter), so two holders of one control cannot release each other early.
     *
     * ⚠ THE RELEASE IS WHERE THIS CAN GO WRONG, so its rules are explicit:
     *   • it runs in ajaxComplete on the SAME jqXHR that held it — success, error, abort and a CONFIRM answer all
     *     pass through complete, so a failed save cannot leave a button reading "Saving…" for ever;
     *   • the content is restored only if it is STILL OURS — an app that relabelled the button meanwhile
     *     ("Save" → "Update") keeps its label rather than getting a stale one back;
     *   • an ALREADY-disabled control is never held — nobody pressed it, and re-enabling it on release would
     *     unlock what someone else locked;
     *   • nothing here may throw: it runs inside ajaxSend (see openModalSubmit's note).
     */
    var BUSY_STYLE_ID = 'submit-once-busy-style';

    function injectBusyStyles() {
        if (document.getElementById(BUSY_STYLE_ID)) return;
        var s = document.createElement('style');
        s.id = BUSY_STYLE_ID;
        s.appendChild(document.createTextNode(
            '.is-busy{cursor:progress}' +
            '.busy-spin{display:inline-block;width:.9em;height:.9em;margin-right:.4em;vertical-align:-.12em;' +
            'border-radius:50%;border:2px solid currentColor;border-right-color:transparent;' +
            'animation:busySpin .7s linear infinite}' +
            '.is-busy-compact .busy-spin{margin-right:0}' +
            '@keyframes busySpin{to{transform:rotate(360deg)}}' +
            '@media (prefers-reduced-motion: reduce){.busy-spin{animation:none;border-right-color:currentColor;opacity:.55}}'));
        (document.head || document.documentElement).appendChild(s);
    }

    /** The label for a kind of write, in the page's language; English if the dictionary lacks the key. */
    function busyText(kind) {
        var key = kind === 'post' ? 'ui.js.busyPosting' : 'ui.js.busySaving';
        var fallback = kind === 'post' ? 'Posting…' : 'Saving…';
        try {
            if (typeof global.tHas === 'function' && global.tHas(key) && typeof global.t === 'function') return global.t(key);
        } catch (e) { /* fall through to English */ }
        return fallback;
    }

    /** An element from an element, a jQuery object or a selector; null for anything else (incl. document). */
    function busyElement(target) {
        if (!target) return null;
        var el = target;
        if (typeof target === 'string') { try { el = document.querySelector(target); } catch (e) { return null; } }
        else if (target.jquery) el = target[0];
        return (el && el.nodeType === 1) ? el : null;
    }

    var DISABLEABLE = /^(BUTTON|INPUT|SELECT|TEXTAREA|FIELDSET)$/;
    function noop() {}

    /**
     * Put a control into its busy state. Returns release(), safe to call more than once.
     *
     * @param kind  'post' (money, stock, documents) or 'save' (default); a data-busy-kind attribute also works
     * @param opts  {label:false} — disable and mark busy, but leave the content alone (a control that was NOT
     *              the one pressed, e.g. a modal's second submit)
     */
    function hold(target, kind, opts) {
        try {
            var el = busyElement(target);
            if (!el) return noop;
            var state = el.__busy;
            if (state) {
                state.count++;
            } else {
                if (el.disabled === true) return noop;   // someone else's lock — see the rules above
                state = el.__busy = { count: 1, html: null, markup: null, minWidth: el.style.minWidth };
                if (DISABLEABLE.test(el.tagName)) el.disabled = true;
                el.setAttribute('aria-busy', 'true');
                el.classList.add('is-busy');
                var wantLabel = !(opts && opts.label === false);
                if (el.tagName === 'BUTTON' && wantLabel) {
                    injectBusyStyles();
                    var width = el.getBoundingClientRect().width;
                    if (width > 0) el.style.minWidth = Math.ceil(width) + 'px';
                    var compact = el.classList.contains('btn-xs') || el.hasAttribute('data-busy-compact');
                    var text = busyText(kind || el.getAttribute('data-busy-kind'));
                    state.html = el.innerHTML;
                    el.innerHTML = '<span class="busy-spin" aria-hidden="true"></span><span class="'
                        + (compact ? 'sr-only' : 'busy-label') + '"></span>';
                    el.lastChild.textContent = text;   // textContent: a translated string is never markup
                    if (compact) el.classList.add('is-busy-compact');
                    state.markup = el.innerHTML;
                }
            }
            var released = false;
            return function release() {
                if (released) return;
                released = true;
                try {
                    var st = el.__busy;
                    if (!st || --st.count > 0) return;
                    delete el.__busy;
                    if (st.html !== null && el.innerHTML === st.markup) el.innerHTML = st.html;
                    el.style.minWidth = st.minWidth;
                    el.classList.remove('is-busy', 'is-busy-compact');
                    el.removeAttribute('aria-busy');
                    if (DISABLEABLE.test(el.tagName)) el.disabled = false;
                } catch (e) { /* a control removed from the page meanwhile has nothing left to restore */ }
            };
        } catch (e) {
            return noop;
        }
    }

    global.BusyControl = {
        hold: hold,
        isBusy: function (target) { var el = busyElement(target); return !!(el && el.__busy); }
    };

    /*
     * LAYER 2b + 2c wiring. The target is STATED, never guessed from "the last thing clicked": a background write
     * inside that window would label the wrong control, and a confirm dialog's OK button is already gone by the
     * time its request is sent.
     *
     *   1. settings.busyControl (+ settings.busyKind) — the call site names its control;
     *   2. the open modal's submit (layer 2b's original selection) — labelled when nothing was named, and only
     *      DISABLED when a different control was, so "Save" and "Save & Add Another" never both say "Saving…".
     *
     * `$(button).callAjax(...)` supplies (1) for every generic save (main.js), so the non-modal forms — the school
     * fee form above all, which had no lock of any kind — are held without a per-form edit.
     */
    /**
     * Requests holding a control, until their release. The registry exists for the LAST-RESORT sweep below.
     */
    var held = [];

    function runReleases(entry) {
        for (var i = 0; i < entry.releases.length; i++) {
            try { entry.releases[i](); } catch (e) { /* keep releasing the rest */ }
        }
    }

    function forget(entry) {
        var i = held.indexOf(entry);
        if (i < 0) return false;            // already released — the other path got there first
        held.splice(i, 1);
        return true;
    }

    $(document).ajaxSend(function (evt, jqXHR, settings) {
        try {
            if (!isWriteMethod(settings && settings.type)) return;
            var releases = [];
            var named = busyElement(settings && settings.busyControl);
            if (named) releases.push(hold(named, settings.busyKind));
            var $modalBtn = openModalSubmit();
            var modalEl = $modalBtn ? $modalBtn[0] : null;
            if (modalEl && modalEl !== named) {
                releases.push(hold(modalEl, settings && settings.busyKind, named ? { label: false } : null));
            }
            if (releases.length) {
                var entry = { jqXHR: jqXHR, releases: releases };
                held.push(entry);
                jqXHR.__busyEntry = entry;
            }
        } catch (e) { /* never throw from ajaxSend */ }
    });

    $(document).ajaxComplete(function (evt, jqXHR) {
        var entry = jqXHR && jqXHR.__busyEntry;
        if (!entry) return;
        delete jqXHR.__busyEntry;
        if (forget(entry)) runReleases(entry);
    });

    /*
     * ⚠ THE LAST-RESORT RELEASE — found by the BLK-2 gate, not by reading.
     *
     * jQuery 3.3.1 runs a request's success/error callbacks with NO try/catch and triggers ajaxComplete only AFTER
     * them (jquery-3.3.1.js: readyState=4 at 9244, resolveWith at 9305, ajaxComplete at 9323, --jQuery.active at
     * 9326). So an app handler that THROWS skips ajaxComplete entirely, and without this the control it held would
     * stay disabled on "Posting…" for the rest of the session. The gate hit exactly that: a sale-return success
     * handler threw on a grid that did not exist.
     *
     * Once a second, any held request that has FINISHED — its promise is no longer pending (covers a status-0
     * failure too) or its readyState is 4 — but never reached ajaxComplete is released here. A timer cannot run in
     * the middle of jQuery's synchronous completion, so this can never beat a normal release to a request, and
     * `forget()` makes the two paths mutually exclusive anyway.
     *
     * Independent of jQuery.active on purpose: a thrown handler also skips `--jQuery.active`, so a sweep keyed on
     * "nothing in flight" (ajax-overlay.js's) would never fire again on that page.
     */
    global.setInterval(function () {
        if (!held.length) return;
        for (var i = held.length - 1; i >= 0; i--) {
            var x = held[i].jqXHR;
            var finished = !x
                || (typeof x.state === 'function' && x.state() !== 'pending')
                || x.readyState === 4;
            if (finished) {
                var entry = held[i];
                held.splice(i, 1);
                if (x) delete x.__busyEntry;
                runReleases(entry);
            }
        }
    }, 1000);

    /** For the gate: how many requests are still holding a control. */
    global.BusyControl.heldCount = function () { return held.length; };


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
