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
 *  2. NOT ON A METERED OR SLOW CONNECTION.  `navigator.connection.saveData` is the Save-Data client
 *     hint — a request from the person using the device to stop spending their data on things they did
 *     not ask for, and prefetch is exactly that. 2g/slow-2g is skipped for the same reason from the
 *     other direction: on a link that slow the speculative fetch would compete with the real one.
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

    /** Metered, slow, or unwatched — three reasons not to spend somebody's connection speculatively. */
    function shouldPrefetch() {
        try {
            if (global.document && global.document.visibilityState === 'hidden') return false;
            var c = global.navigator && (global.navigator.connection
                    || global.navigator.mozConnection || global.navigator.webkitConnection);
            if (c) {
                if (c.saveData === true) return false;
                if (/^(slow-2g|2g)$/.test(String(c.effectiveType || ''))) return false;
            }
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
