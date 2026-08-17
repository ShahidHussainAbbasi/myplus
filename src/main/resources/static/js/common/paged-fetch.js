/* ==========================================================================================
 * paged-fetch.js — read a COMPLETE Spring Data page set, instead of guessing a big page size.
 *
 * THE PROBLEM THIS REPLACES.
 * Callers asked for one enormous page (`?size=2000`, `?size=1000`) and treated the result as
 * "all of them". Two things are wrong with that:
 *
 *   1. It is a SILENT truncation. Spring Boot clamps `size` to
 *      `spring.data.web.pageable.max-page-size` (2000 by default, and this project never
 *      overrides it). A tenant with more products than the cap gets a short list with no error
 *      and no warning — the missing products simply cannot be picked, sold, or counted. The
 *      failure looks like "that product isn't in the system", which is the most expensive kind
 *      of bug to diagnose because nothing anywhere reports a problem.
 *   2. Raising the number does not fix it. It moves the cliff and makes every caller pay for a
 *      2000-row transfer whether the tenant has 12 products or 12,000.
 *
 * WHAT THIS DOES INSTEAD.
 * Read page 0, learn `totalPages` from the envelope the server already returns, then fetch any
 * remaining pages CONCURRENTLY and concatenate in page order. Correct for any size of catalogue,
 * and a small tenant pays for exactly one request.
 *
 * The response envelope is PageResponse: { content, pageNo, pageSize, totalElements, totalPages,
 * last }, wrapped by ApiResponse as { success, data: <PageResponse> }. Both shapes are unwrapped
 * here so callers keep receiving a plain array.
 * ========================================================================================== */
(function (global, $) {
    'use strict';

    /* Page size for the underlying requests. Deliberately well under the framework cap: the point
     * is no longer to fit everything in one response, so a smaller page starts rendering sooner
     * and keeps a single failed request cheap to retry. */
    var PAGE_SIZE = 500;

    /* A ceiling on how many pages we will pull, so a runaway/mis-scoped query cannot spin forever
     * against a browser. 200 x 500 = 100,000 rows — far beyond any real picker, and if a tenant
     * ever exceeds it the console says so instead of failing silently, which is the whole point. */
    var MAX_PAGES = 200;

    /** Unwrap ApiResponse/PageResponse/bare-array into { list, totalPages }. */
    function unwrap(resp) {
        var page = (resp && resp.data) ? resp.data : resp;
        if (Array.isArray(page)) return { list: page, totalPages: 1 };
        if (!page) return { list: [], totalPages: 1 };
        return {
            list: page.content || [],
            totalPages: (typeof page.totalPages === 'number') ? page.totalPages : 1
        };
    }

    /** Build "path?a=b&page=N&size=M", preserving any query the caller already put on `path`. */
    function withPaging(path, page, size) {
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + 'page=' + page + '&size=' + size;
    }

    /**
     * Fetch EVERY page of `path` and hand the caller one array.
     *
     *   fetchAll('catalogProducts', function (products) { ... });
     *
     * `path` is relative to serverContext and may carry its own query string.
     * `onDone(list)` runs once, with the complete list in page order.
     * `onFail(err)`  optional; if omitted a failure logs and yields an empty list, matching the
     *                 defensive behaviour the existing callers already had in their .fail() paths.
     */
    function fetchAll(path, onDone, onFail) {
        var base = (typeof serverContext !== 'undefined' ? serverContext : '/');

        $.get(base + withPaging(path, 0, PAGE_SIZE))
            .done(function (first) {
                var head = unwrap(first);
                var total = Math.min(head.totalPages || 1, MAX_PAGES);

                if ((head.totalPages || 1) > MAX_PAGES) {
                    // Loud, not silent — the exact failure mode this module exists to remove.
                    console.warn('paged-fetch: ' + path + ' has ' + head.totalPages
                        + ' pages; reading the first ' + MAX_PAGES + '. Raise MAX_PAGES or filter server-side.');
                }
                if (total <= 1) { onDone(head.list); return; }

                // Remaining pages in PARALLEL. Sequential would make a big catalogue feel slow for
                // no benefit; the server is already page-scoped so the requests are independent.
                var rest = [];
                for (var p = 1; p < total; p++) rest.push($.get(base + withPaging(path, p, PAGE_SIZE)));

                $.when.apply($, rest)
                    .done(function () {
                        // jQuery hands one [data, status, jqXHR] triple per request — except with a
                        // SINGLE deferred, where the arguments ARE that triple. Normalise both.
                        var results = (rest.length === 1) ? [arguments] : Array.prototype.slice.call(arguments);
                        var out = head.list.slice();
                        results.forEach(function (r) { out = out.concat(unwrap(r[0]).list); });
                        onDone(out);
                    })
                    .fail(function (err) {
                        if (onFail) { onFail(err); return; }
                        console.error('paged-fetch: a later page of ' + path + ' failed', err);
                        onDone(head.list);   // partial beats nothing, and the console says why
                    });
            })
            .fail(function (err) {
                if (onFail) { onFail(err); return; }
                console.error('paged-fetch: ' + path + ' failed', err);
                onDone([]);
            });
    }

    global.PagedFetch = { all: fetchAll, PAGE_SIZE: PAGE_SIZE };
})(window, jQuery);
