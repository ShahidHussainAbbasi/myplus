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

    // Exposed so a caller can ask rather than restate the number.
    global.DT_DEFAULT_PAGE_LENGTH = DEFAULT_PAGE_LENGTH;
})(window);
