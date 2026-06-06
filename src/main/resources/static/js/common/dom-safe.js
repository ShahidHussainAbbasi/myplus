/*
 * dom-safe.js — application-wide DOM safety helpers.
 *
 * Loaded by every dashboard (business / education / welfare / agriculture) BEFORE the
 * page-specific JS. The dashboards build DataTables rows / dropdowns / innerHTML by
 * concatenating server values into HTML strings; any user-supplied field must be passed
 * through escHtml() first, otherwise a stored value such as
 *     "<img src=x onerror=alert(1)>"   or   "<script>alert(1)</script>"
 * executes in the browser (stored XSS).
 *
 * Keep this file dependency-free (no jQuery) so it is safe to load first.
 */
(function (global) {
    'use strict';

    var MAP = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
    };

    /**
     * HTML-escape a value for safe insertion into an HTML string.
     * null / undefined become '' so callers can use it unconditionally.
     */
    function escHtml(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value).replace(/[&<>"']/g, function (ch) {
            return MAP[ch];
        });
    }

    global.escHtml = escHtml;
})(window);
