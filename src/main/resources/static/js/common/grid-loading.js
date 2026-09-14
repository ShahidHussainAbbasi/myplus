/**
 * grid-loading.js — BLK-3: a grid that is loading LOOKS like it is loading, and never says "no records".
 *
 * Design: microservices/docs/blocking-ui-and-backend-guards-design.md §4.3.2
 *
 * <h3>What was wrong, measured against the library this app actually serves (DataTables 1.10.19)</h3>
 * DataTables picks the text of its placeholder row like this:
 *
 *     text = zeroRecords
 *     if (iDraw == 1 && dataSource == "ajax") text = loadingRecords      // "Loading..."
 *     else if (emptyTable && recordsTotal == 0) text = emptyTable         // "No data available in table"
 *
 * so every module grid opened on one English "Loading..." line — the grid collapsed to a single row and the page
 * jumped when the real rows arrived. And ANY second draw before the data arrived (the "Toggle column" links do
 * exactly that) failed the `iDraw == 1` test and printed "No data available in table" over a grid that was still
 * loading: an empty list stated as fact while the truth was "not here yet" — the same class of lie as a silent
 * truncation (PS-1).
 *
 * <h3>How this works without editing any grid</h3>
 *   1. `loadingRecords` defaults to skeleton rows, and `emptyTable` to the translated "No data yet".
 *   2. On every draw: if the table's request is still in flight, a placeholder row is a skeleton — whatever
 *      DataTables' draw counter says.
 *   3. When the request SETTLES, a table still showing a skeleton is given the answer: the empty text on
 *      success, a failure message on error.
 *
 * Step 3 is what makes this safe on grids that override `ajax.success` (all four module loadDataTable()s do).
 * DataTables fires its `xhr` event only from inside its OWN success handler, which those grids replace — so the
 * event never fires for them. But DataTables always keeps the request on `settings.jqXHR`, so this attaches to
 * the request itself. It also covers the case the old business.js hack hid: `columns([0]).visible(false)` on an
 * already-hidden column does not redraw, so the placeholder would otherwise have stayed a skeleton for ever.
 *
 * <h3>Plain tables</h3>
 * `GridLoading.fill('#tableX')` puts the same skeleton in a plain table's body BEFORE its fetch. Without it a
 * re-fetch left the previous rows — or the previous "No stores yet" — on screen as if they were the answer.
 *
 * Loads after jquery.dataTables + datatable-defaults.js (it extends defaults, which are read at construction)
 * and after i18n.js (t()), and before any module script builds a grid.
 */
(function (global, $) {
    'use strict';
    if (!$) return;

    /** Placeholder rows per skeleton: enough to hold a grid's height, few enough not to push the pager away. */
    var ROWS = 5;

    function tr(key, fallback) {
        return (typeof global.t === 'function' && (typeof global.tHas !== 'function' || global.tHas(key)))
            ? global.t(key) : fallback;
    }

    function esc(s) {
        return String(s).replace(/[&<>"]/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
        });
    }

    /**
     * The skeleton markup. A status region carrying REAL text (visually hidden), so a screen reader hears
     * "Loading…" rather than silence: an aria-label on a role=status element with no content is not reliably
     * announced, which is how the first cut of this shipped.
     */
    function skeletonHtml(rows) {
        var n = rows || ROWS;
        var html = '<div class="grid-skeleton" role="status">'
            + '<span class="sr-only">' + esc(tr('ui.js.loading', 'Loading…')) + '</span>';
        for (var i = 0; i < n; i++) html += '<div class="grid-skeleton-row"></div>';
        return html + '</div>';
    }

    function headerColumns($table) {
        return Math.max(1, $table.find('thead tr').first().children('th,td').length);
    }

    /**
     * Plain table: show the skeleton in its body NOW, before the fetch starts. The caller's success handler
     * replaces the body as it always did, so nothing else at the call site changes.
     */
    function fill(tableSelector, rows) {
        var $table = $(tableSelector);
        if (!$table.length) return;
        var $body = $table.children('tbody');
        if (!$body.length) $body = $('<tbody>').appendTo($table);
        $body.html('<tr class="grid-loading-row"><td colspan="' + headerColumns($table) + '">'
            + skeletonHtml(rows) + '</td></tr>');
    }

    global.GridLoading = { fill: fill, skeletonHtml: skeletonHtml };

    var DT = $.fn && $.fn.dataTable;
    if (!DT || !DT.defaults) return;   // no DataTables on this page (login, landing) — plain tables still work

    /*
     * Hungarian keys, deliberately: `oLanguage` is the defaults object DataTables itself reads, so there is no
     * camelCase mapping step to trust. Any grid stating its own language still wins, as with every default.
     */
    DT.defaults.oLanguage = $.extend({}, DT.defaults.oLanguage, {
        sLoadingRecords: skeletonHtml(),
        sEmptyTable: tr('ui.js.noDataYet', 'No data yet'),
        // The server-paged Product grid's floating box. It was the one piece of loading chrome still in English.
        sProcessing: tr('ui.js.loading', 'Loading…')
    });

    function inFlight(settings) {
        var x = settings && settings.jqXHR;
        return !!(x && typeof x.readyState === 'number' && x.readyState !== 4);
    }

    /** The placeholder cell, if the table is showing one rather than data. */
    function placeholder(settings) {
        return $(settings.nTBody).find('td.dataTables_empty').first();
    }

    function showSkeleton(settings) {
        var $cell = placeholder(settings);
        if ($cell.length && !$cell.children('.grid-skeleton').length) $cell.html(skeletonHtml());
    }

    /** The request answered and nothing replaced the skeleton: say what the answer was. */
    function settle(settings, text) {
        var $cell = placeholder(settings);
        if ($cell.length && $cell.children('.grid-skeleton').length) $cell.text(text);
    }

    // 2. A draw while the request is in flight shows a skeleton, never "No data available".
    $(document).on('draw.dt', function (e, settings) {
        if (settings && inFlight(settings)) showSkeleton(settings);
    });

    $(document).on('preXhr.dt', function (e, settings) {
        if (!settings || !settings.nTBody) return;

        /*
         * A SERVER-PAGED grid (the Product grid) draws nothing until its first answer, so the body is empty
         * rather than a placeholder. Give it skeleton rows — but only when it shows no data: replacing a page
         * the user is reading with a skeleton on every page change would be worse than the processing box.
         */
        var $body = $(settings.nTBody);
        var dataRows = $body.children('tr').filter(function () {
            return !$(this).find('td.dataTables_empty').length && !$(this).hasClass('grid-loading-row');
        });
        if (!dataRows.length) {
            var cols = 0;
            $.each(settings.aoColumns || [], function (i, c) { if (c.bVisible) cols++; });
            $body.html('<tr class="grid-loading-row"><td class="dataTables_empty" colspan="' + Math.max(1, cols)
                + '">' + skeletonHtml() + '</td></tr>');
        }

        // 3. Attach to the request itself. DataTables assigns settings.jqXHR synchronously right after this
        //    event, hence the zero delay; a request that has already settled still runs these at once.
        global.setTimeout(function () {
            var x = settings.jqXHR;
            if (!x || typeof x.done !== 'function') return;   // a server-paged grid's ajax FUNCTION returns none
            x.done(function () { settle(settings, tr('ui.js.noDataYet', 'No data yet')); })
             .fail(function (xhr, status) {
                 if (status === 'abort') return;              // superseded, not failed
                 settle(settings, tr('ui.js.anErrorOccurredPleaseTryAgain', 'Could not load. Please try again.'));
             });
        }, 0);
    });
})(window, window.jQuery);
