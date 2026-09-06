/**
 * One place that says how many rows a grid shows before anyone scrolls.
 *
 * <h3>Why this file exists rather than an edit per table</h3>
 * DataTables' own default is 10. On a mobile shop's stock list, a distributor's outlet list or a pharmacy's
 * batch list, ten rows means the operator pages before they have finished reading — and the request that
 * prompted this was exactly that: "default rows 50".
 *
 * Setting it on each table would mean finding every `.DataTable({…})` call in the product and remembering the
 * next one. `$.fn.dataTable.defaults` is the framework's own hook for this, so a table added tomorrow inherits
 * it without anybody having to know.
 *
 * <h3>An explicit pageLength still wins</h3>
 * These are DEFAULTS: any table that states its own keeps it. `installment.js` asks for 25 deliberately, and
 * that is untouched. This changes only the tables that never had an opinion — which is the set that was
 * silently getting 10.
 *
 * <h3>Why 50 and not "show all"</h3>
 * `-1` renders every row into the DOM. On the product grid that is ~1,300 rows on a phone browser, which is
 * slow to lay out and slower to scroll. 50 fills any screen, and "Show all" stays one click away in the
 * length menu for anyone who wants it.
 *
 * <h3>Load order matters</h3>
 * Defaults are read when a table is CONSTRUCTED, so this must run after jquery.dataTables and before any
 * module script builds a grid. The header loads it in that gap; moving it after the module scripts would
 * leave it correct-looking and inert.
 */
(function (global) {
    'use strict';

    var $ = global.jQuery;
    if (!$ || !$.fn || !$.fn.dataTable) {
        // DataTables is not on this page — a perfectly normal state for the login and landing pages.
        return;
    }

    /** The rows-per-page choices every grid offers, and the one it opens on. */
    var DEFAULT_PAGE_LENGTH = 50;

    $.extend(true, $.fn.dataTable.defaults, {
        pageLength: DEFAULT_PAGE_LENGTH,
        // Kept in step with the default: a menu that does not contain the current value renders a blank
        // selector, which looks broken even though the table is correct.
        lengthMenu: [
            [10, 25, 50, 100, -1],
            ['10 rows', '25 rows', '50 rows', '100 rows', 'Show all']
        ]
    });

    /**
     * ⭐ A sort type for dd-MM-yyyy dates (slice SR-1).
     *
     * <h3>The defect</h3>
     * Our reports render dates as `dd-MM-yyyy` — that is what `AppUtil.getDateStr` produces and what every
     * grid receives. DataTables types such a column as a STRING, so `order: [[0,'desc']]` sorts by
     * day-of-month first. Measured on the Sale Detail Report against a live tenant:
     *
     *     true newest   06-09-2026 · 01-09-2026 · 30-08-2026
     *     grid showed   30-08-2026 · 29-08-2026 · 28-08-2026
     *
     * Today's sale sorted BELOW one from last month. It survived because the report defaulted to a single
     * month, and inside one month every row shares the month and year — so the order looked right. The two
     * defects hid each other.
     *
     * <h3>Why here, and why a type rather than a render</h3>
     * `$.fn.dataTable.ext.type.order` is DataTables' own extension point for exactly this, so the cell keeps
     * its plain human text and nothing about the markup changes — a render function would have meant every
     * caller carrying a second copy of the date in an attribute. This file already exists to configure
     * DataTables once and already loads in the gap after the library and before any module builds a grid
     * (see the load-order note above), which is the only window in which a type can be registered.
     *
     * <h3>Opt-in, deliberately</h3>
     * Registering a type changes NOTHING on its own — a column asks for it with `type: 'date-dmy'`. Other
     * dd-MM-yyyy columns in the product very likely have the same defect, but each needs its own trace
     * before its ordering is changed underneath whoever reads it.
     *
     * Sorts as a number (yyyymmdd). An unparseable or empty cell sorts LAST in a descending order rather
     * than jumping to the top, because a row with no date is not the newest thing in the shop.
     */
    if ($.fn.dataTable.ext && $.fn.dataTable.ext.type && $.fn.dataTable.ext.type.order) {
        $.fn.dataTable.ext.type.order['date-dmy-pre'] = function (value) {
            var text = $('<div>').html(value == null ? '' : value).text().trim();
            var m = /^(\d{1,2})[-\/](\d{1,2})[-\/](\d{4})/.exec(text);
            if (!m) return -1;   // sorts last descending, first ascending — an undated row is never "newest"
            return (Number(m[3]) * 10000) + (Number(m[2]) * 100) + Number(m[1]);
        };
    }

    // Exposed so a caller can ask rather than restate the number.
    global.DT_DEFAULT_PAGE_LENGTH = DEFAULT_PAGE_LENGTH;
})(window);
