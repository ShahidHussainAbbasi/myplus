/* ==========================================================================================
 * product-picker.js — PERF-8. One cached, lean read behind every product <select>.
 *
 * WHAT THIS REPLACES.
 * Five callers each did `PagedFetch.all('catalogProducts', …)`, which downloads the tenant's
 * WHOLE product master — all 23 fields of every product, deactivated ones included — and then
 * filtered and reshaped it in JavaScript. Measured on the demo tenant (1,249 products):
 *
 *     3 requests · ~670 KB · 83% of each product discarded on arrival
 *
 * and it ran again on EVERY section open and every cart-line edit.
 *
 * WHAT IT DOES INSTEAD.
 *   1. Reads /catalogProductPicker — three fields, active rows only, filtered and projected in SQL.
 *   2. Caches the result for the life of the page, so the second and later callers issue NOTHING.
 *
 * WHY THE CACHE IS INVALIDATED EXPLICITLY AND NOT ON A TIMER.
 * A stale picker on a till is worse than a slow one: an operator who adds a product and then cannot
 * sell it concludes the system lost it. A TTL would make the picker *sometimes* right, which is
 * harder to reason about than always-right-after-a-write. So every path that changes the catalogue
 * calls invalidate() — create, update, activate, deactivate, and CSV import.
 *
 * CONCURRENT CALLERS SHARE ONE REQUEST. loadUserItems() and loadCartLineIntoForm() can both fire
 * while a section is opening; the in-flight promise is reused so they cannot start two fetches of
 * the same catalogue.
 * ========================================================================================== */
(function (global, $) {
    'use strict';

    /* A lean row is ~92 bytes, so 2,000 of them is ~180 KB — one request for any realistic tenant.
     * PagedFetch still handles a bigger catalogue correctly; this only decides where the fan-out
     * starts, and keeping it beyond every real tenant is what removes the multi-wave request
     * pattern that left the app looking idle mid-load. */
    var PAGE_SIZE = 2000;

    var cache = null;        // the resolved list, or null when cold
    var inFlight = null;     // the promise while a fetch is running

    function ctx() {
        return (typeof serverContext !== 'undefined' ? serverContext : '/');
    }

    /** Unwrap ApiResponse -> PageResponse -> content, tolerating a bare array. */
    function unwrap(resp) {
        var page = (resp && resp.data) ? resp.data : resp;
        if (Array.isArray(page)) return { list: page, totalPages: 1 };
        if (!page) return { list: [], totalPages: 1 };
        return {
            list: page.content || [],
            totalPages: (typeof page.totalPages === 'number') ? page.totalPages : 1
        };
    }

    function url(page) {
        return ctx() + 'catalogProductPicker?page=' + page + '&size=' + PAGE_SIZE;
    }

    /**
     * The catalogue read, WITHOUT the blocking overlay.
     *
     * <p>Populating a picker is background work: the screen should appear and fill in, not hold a spinner
     * over a till while a cashier waits. This was the largest single contributor left on the sale screen —
     * jQuery raises the overlay on the first request and drops it only when the LAST one finishes, so the
     * whole screen was gated on the slowest catalogue page.
     *
     * <p>{@code global: false} also excludes these from `ajaxComplete`, so callers MUST refresh their own
     * picker — {@link loadUserItems} and {@link loadCartLineIntoForm} both do.
     */
    function get(page) {
        return $.ajax({ url: url(page), global: false });
    }

    /**
     * The active products for this tenant, as [{id, name, sellingPrice}].
     *
     * @param onDone  called with the list — from cache when warm, so possibly synchronously
     * @param onFail  optional; without it a failure logs and yields an empty list, matching the
     *                defensive behaviour the callers this replaces already had
     */
    function load(onDone, onFail) {
        if (cache) { onDone(cache.slice()); return; }

        if (!inFlight) {
            inFlight = get(0).then(function (first) {
                var head = unwrap(first);
                if ((head.totalPages || 1) <= 1) return head.list;

                // A catalogue past one page: fetch the rest IN PARALLEL, same as paged-fetch does.
                // Rare by design — PAGE_SIZE is set beyond any realistic tenant — but correct when
                // it happens, because silently serving the first page is how a product becomes
                // unsellable with nothing anywhere reporting a problem.
                var rest = [];
                for (var p = 1; p < head.totalPages; p++) rest.push(get(p));
                return $.when.apply($, rest).then(function () {
                    var results = (rest.length === 1) ? [arguments]
                        : Array.prototype.slice.call(arguments);
                    var out = head.list.slice();
                    results.forEach(function (r) { out = out.concat(unwrap(r[0]).list); });
                    return out;
                });
            });
        }

        inFlight.done(function (list) {
            cache = list || [];
            inFlight = null;
            onDone(cache.slice());
        }).fail(function (err) {
            inFlight = null;          // never cache a failure — the next caller retries
            if (onFail) { onFail(err); return; }
            console.error('product-picker: load failed', err);
            onDone([]);
        });
    }

    /** Drop the cache. Call after ANY write that changes which products exist or are sellable. */
    function invalidate() {
        cache = null;
        inFlight = null;
    }

    /**
     * Build the <option> markup every picker uses.
     *
     * Here rather than in each caller because all five built the identical string, and a divergence
     * would mean the sale screen and the booking screen disagreeing about what a product is called.
     * `data-price` feeds the sale screen's price prefill; `data-product` is the productId the cart
     * submits.
     */
    function optionsHtml(list, placeholder) {
        var html = "<option value=''>" + (placeholder || 'Nothing Selected') + "</option>";
        var esc = (typeof global.escHtml === 'function') ? global.escHtml : function (v) { return v; };
        list.forEach(function (p) {
            html += "<option value='" + p.id + "' data-product='" + p.id + "'"
                 + " data-price='" + (p.sellingPrice != null ? p.sellingPrice : '') + "'>"
                 + esc(p.name || ('Product #' + p.id)) + "</option>";
        });
        return html;
    }

    /* ── Invalidation: ONE global hook, not a call in each success handler ────────────────────
     *
     * Every path that changes which products exist or are sellable must drop the cache, and the
     * failure mode of missing one is the worst this slice can produce: an operator adds a product,
     * the picker still shows the old list, and they conclude the system lost it.
     *
     * Editing each success handler would work today and rot tomorrow — the next write path added
     * would simply forget, and nothing would fail loudly. A global ajaxComplete hook cannot be
     * forgotten, because it keys on the URL rather than on someone remembering. Same mechanism
     * searchable-selects.js already uses to refresh pickers after AJAX.
     *
     * Answers PERF-8 Q1: /import/product/commit is included, so a CSV import of several hundred
     * products refreshes the picker like any other write.
     *
     * Only SUCCESSFUL calls invalidate: a failed write changed nothing, and dropping the cache for
     * it would mean re-fetching the catalogue every time a validation error came back. */
    var MUTATES = /\/(addProduct|updateProduct|activateProduct|deactivateProduct|import\/product\/commit)(\?|$)/;

    $(document).ajaxComplete(function (evt, jqXHR, settings) {
        if (!settings || !settings.url || !MUTATES.test(settings.url)) return;
        if (jqXHR.status < 200 || jqXHR.status >= 300) return;

        // The monolith answers 200 with {success:false} / {status:'FAILED'} on a refusal, so the HTTP
        // status alone is not proof anything changed. Read the envelope where there is one.
        var body = jqXHR.responseJSON;
        if (body && (body.success === false || body.status === 'FAILED' || body.status === 'ERROR')) return;

        invalidate();
    });

    global.ProductPicker = {
        load: load,
        invalidate: invalidate,
        optionsHtml: optionsHtml,
        PAGE_SIZE: PAGE_SIZE
    };
})(window, jQuery);
