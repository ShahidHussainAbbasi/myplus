/*
 * customer-picker.js — one cached, lean read behind every customer <select>.
 *
 * The deliberate twin of product-picker.js (PERF-8), and it exists for the same reason: three call
 * sites each fetched `customerOptions` independently, so opening the till, opening the receipts screen
 * and opening a quote each paid for the same list again. The endpoint is already the LEAN read — six
 * fields, not the 22 `getUserCustomer` returns — so the waste was not the payload, it was the trip.
 *
 * ── WHY A CACHE AND NOT A TTL ────────────────────────────────────────────────────────────────────
 * Copied from product-picker.js, because the reasoning is identical and worth restating rather than
 * cross-referencing: a customer added on this screen and missing from the next list does not look like
 * a stale cache to a cashier, it looks like the system lost them. A TTL would make the picker
 * *sometimes* right, which is worse than always-stale because nobody can predict it. So the cache is
 * dropped on WRITES instead — invalidate() is called by anything that adds or edits a customer, and by
 * nothing else.
 *
 * ⚠ Only SUCCESSFUL writes invalidate. A refused write changed no customer, and dropping the list for
 * it would re-fetch on every validation error.
 */
(function (global, $) {
    'use strict';

    var cache = null;        // the resolved list, or null when cold
    var inflight = null;     // the jqXHR while a read is running, so N callers share ONE trip

    function ctx() { return (typeof serverContext === 'string') ? serverContext : '/'; }

    /**
     * The customer list, from cache when warm.
     *
     * @param onDone called with the array (possibly empty)
     * @param onFail called on a failed read — the caller decides what the picker should say
     */
    function load(onDone, onFail) {
        if (cache) { onDone(cache); return; }

        if (inflight) {
            // A second caller while the first read is still out: queue on it rather than open another.
            // The till opening two pickers at once is the ordinary case, not an edge one.
            inflight.done(function () { onDone(cache || []); });
            if (onFail) inflight.fail(onFail);
            return;
        }

        // global:false — populating a picker is background work and must not hold the blocking overlay
        // over a counter. Same reasoning as the bgJson call this replaced.
        inflight = $.ajax({ url: ctx() + 'customerOptions', dataType: 'json', global: false })
            .done(function (res) {
                cache = (res && res.collection) ? res.collection : [];
                onDone(cache);
            })
            .fail(function () { if (onFail) onFail(); })
            .always(function () { inflight = null; });
    }

    /** One <option> per customer, carrying the data- attributes the till reads. */
    function optionsHtml(list) {
        var esc = (typeof escHtml === 'function') ? escHtml : function (s) { return s; };
        var html = '<option value=""> Select Customer </option>';
        (list || []).forEach(function (c) {
            html += '<option value="' + c.customerId + '"'
                 + ' data-contact="' + esc(c.contact || '') + '"'
                 + ' data-due="' + (c.dueAmount != null ? c.dueAmount : 0) + '"'
                 + ' data-credit-limit="' + (c.creditLimit != null ? c.creditLimit : '') + '"'
                 + ' data-customer-type="' + esc(c.customerType || '') + '">'
                 + esc(c.name) + '</option>';
        });
        return html;
    }

    function invalidate() { cache = null; }

    /** True when the list is already in memory — what the prefetch checks before doing any work. */
    function isWarm() { return cache !== null; }

    /*
     * Drop the cache when a customer changes, the way product-picker.js hooks product writes.
     *
     * ⚠ Only on SUCCESS, and only for the routes that actually change a customer. `addCustomer` serves
     * both create and edit (it keys on customerId), so one hook covers both.
     */
    $(document).ajaxComplete(function (evt, xhr, settings) {
        var url = (settings && settings.url) || '';
        if (!/\/(addCustomer|deleteCustomer|updateCustomer)\b/.test(url)) return;
        var body = xhr && xhr.responseJSON;
        var ok = body ? (body.status === 'SUCCESS' || body.success === true) : (xhr && xhr.status === 200);
        if (ok) invalidate();
    });

    global.CustomerPicker = {
        load: load,
        optionsHtml: optionsHtml,
        invalidate: invalidate,
        isWarm: isWarm
    };
})(window, jQuery);
