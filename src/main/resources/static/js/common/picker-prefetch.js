/*
 * picker-prefetch.js — fill the till's pickers BEFORE the cashier asks for them.
 *
 * ── THE PROBLEM ─────────────────────────────────────────────────────────────────────────────────
 * Nothing fetched until New Sale was clicked. Then two cold requests went out — the product picker and
 * the customer picker — and until both landed the item list said "Loading…" and the customer list was
 * empty. With barcode scanning off (the default for every tenant) the item picker IS the entry point,
 * so there was nothing to type into and the sale could not start.
 *
 * Reported from a shop with FOUR products and THREE customers, which is the tell: the wait was never
 * the payload, it was the round trip — paid at the worst possible moment, with a customer at the
 * counter. Work that is going to be needed anyway should not be started at the instant it is needed.
 *
 * So: fetch during the idle time after the dashboard has painted. By the time New Sale is pressed the
 * lists are in memory and the pickers fill from cache.
 *
 * ⚠ THIS IS AN OPTIMISATION AND MUST BEHAVE LIKE ONE. It never blocks, never retries, never reports an
 * error, and never changes what a screen shows. If it is skipped or fails, section open fetches exactly
 * as it always did — the caches simply stay cold, which is today's behaviour.
 *
 * ── THE LIMITS, AND WHY THESE ONES ──────────────────────────────────────────────────────────────
 * Speculative work spends someone else's battery and data allowance, so it is bounded three ways:
 *
 *  1. ONE PAGE, NEVER MORE.  product-picker.js measured its own row: "a lean row is ~92 bytes, so
 *     2,000 of them is ~180 KB". One page is therefore the natural ceiling — around 180 KB, which is a
 *     reasonable speculative budget, and it needs no invented number because PAGE_SIZE already is one.
 *     A tenant that does not fit in a page is the signal to move that picker to server-side type-ahead,
 *     not to speculatively download more. Those tenants keep today's on-demand behaviour.
 *
 *  2. NOT WHEN THE PERSON ASKED TO SAVE DATA.  `navigator.connection.saveData` is the Save-Data client
 *     hint — a request from the person using the device to stop spending their data on things they did
 *     not ask for, and prefetch is exactly that. (2g/slow-2g USED to be skipped as well; removed
 *     2026-09-15 by the user's ruling — Chrome's speed estimate flipped slow-2g ↔ 4g within seconds on a
 *     busy till PC, and a slow link is where the preload helps most. See shouldPrefetch().)
 *
 *  3. NOT ON A HIDDEN TAB.  A background tab may never be looked at again; warming it is pure waste.
 *
 * `requestIdleCallback` carries a timeout so this still runs on a permanently busy page, and falls back
 * to a plain timer where it is unavailable (Safari).
 */
(function (global) {
    'use strict';

    var done = false;                 // once per page: the caches live as long as the document
    var IDLE_TIMEOUT_MS = 3000;       // run even if the page never goes properly idle
    var FALLBACK_DELAY_MS = 1200;     // no requestIdleCallback: wait for first paint to settle

    /** Save-Data asked for, or a tab nobody is watching — the two reasons not to prefetch (a SLOW link is not one). */
    function shouldPrefetch() {
        try {
            if (global.document && global.document.visibilityState === 'hidden') return false;
            var c = global.navigator && (global.navigator.connection
                    || global.navigator.mozConnection || global.navigator.webkitConnection);
            /*
             * Save-Data is the ONLY connection opt-out (the user's ruling, 2026-09-15).
             *
             * Chrome's effectiveType used to be a second one, and it was wrong for a till. It is an ESTIMATE, and on
             * a busy shop PC it flipped between slow-2g and 4g within seconds (monolith RUM on the same build, and
             * picker-prefetch.cy.js failing with `network=slow-2g`) — so the till skipped the preload exactly when
             * its link was slowest, which is when having the lists already in memory saves the most. Save-Data is
             * different in kind: it is the person asking, not the browser guessing.
             */
            if (c && c.saveData === true) return false;
        } catch (e) {
            // A capability probe must never be the thing that breaks a page. Unknown => go ahead.
        }
        return true;
    }

    function warmProducts() {
        if (!global.ProductPicker || typeof global.ProductPicker.load !== 'function') return;
        // load() populates the module's own cache; the callback is deliberately empty because there is
        // no screen to fill yet. onFail is a no-op for the same reason - a failed prefetch is a
        // non-event, and the section-open read will try again when the list is actually needed.
        try { global.ProductPicker.load(function () {}, function () {}); } catch (e) {}
    }

    function warmCustomers() {
        if (!global.CustomerPicker || typeof global.CustomerPicker.load !== 'function') return;
        try { global.CustomerPicker.load(function () {}, function () {}); } catch (e) {}
    }

    /**
     * Warm both pickers once, during idle time.
     *
     * Exposed so a screen can call it explicitly, and so the gate can assert the caches are warm
     * BEFORE New Sale is opened - which is the whole claim this file makes.
     */
    function warmAll() {
        if (done) return;
        done = true;
        if (!shouldPrefetch()) return;
        warmProducts();
        warmCustomers();
    }

    function schedule() {
        if (typeof global.requestIdleCallback === 'function') {
            global.requestIdleCallback(warmAll, { timeout: IDLE_TIMEOUT_MS });
        } else {
            global.setTimeout(warmAll, FALLBACK_DELAY_MS);
        }
    }

    /*
     * After LOAD, not after DOMContentLoaded: the point is to use time the page is not using, and
     * DOMContentLoaded still has stylesheets, fonts and the dashboard's own first reads in flight.
     * Starting there would put this in competition with first paint, which is the opposite of the aim.
     */
    if (global.document && global.document.readyState === 'complete') schedule();
    else global.addEventListener('load', schedule);

    global.PickerPrefetch = { warmAll: warmAll, shouldPrefetch: shouldPrefetch };
})(window);
