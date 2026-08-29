/**
 * SER-4 — tell the cashier what the unit in their hand actually is.
 *
 * <h3>The gap this closes</h3>
 * SER-2 records a condition grade when stock is received: NEW, USED, REFURBISHED. Until now that grade lived
 * only in the database. A cashier selling a second-hand handset saw the same screen as one selling a new one,
 * so the shop owned the fact and could not act on it — and the moment it matters is *before* the money is
 * taken, not in a report afterwards.
 *
 * <h3>It also answers "is this thing even here?"</h3>
 * The server refuses a serial that is not in stock, but only when the sale is submitted — after the basket is
 * built and the customer is waiting. Looking it up as it is typed turns that refusal into a note the cashier
 * can act on while it is still cheap to fix, without weakening the server check, which remains the control.
 *
 * <h3>Deliberately advisory</h3>
 * Nothing here blocks a sale, and a failed lookup shows nothing at all rather than a scary error. The register
 * is the authority and `SagaSellService` enforces it; this is a courtesy in front of that. A network hiccup
 * must never stop a shop selling.
 */
(function (global) {
    'use strict';

    var $ = global.jQuery;
    if (!$) return;

    /** How long to wait after typing stops. An IMEI is 15 digits — firing per keystroke would be 15 lookups. */
    var DEBOUNCE_MS = 350;

    /** Grades worth calling out. NEW is the default and needs no announcement; the others change the sale. */
    var NOTABLE = { USED: 'warn', REFURBISHED: 'warn', NEW: 'ok' };

    var timer = null;
    var lastQueried = null;

    function t(key, fallback) {
        return (global.t && global.tHas && global.tHas(key)) ? global.t(key) : fallback;
    }

    function hide() {
        $('#sellSerialInfo').hide().empty();
    }

    function show(html, kind) {
        $('#sellSerialInfo')
            .removeClass('serial-info-ok serial-info-warn serial-info-miss')
            .addClass('serial-info-' + kind)
            .html(html)
            .show();
    }

    /** Render what the register knows. `unit` is null when nothing is in stock under that serial. */
    function render(serial, unit) {
        if (!unit) {
            // NOT an error state in the UI sense: the cashier may simply be mid-typing a valid IMEI. It is a
            // note, and the server still refuses the sale if it is wrong at submit time.
            show(escapeHtml(t('ui.js.serialNotInStock', 'Not in stock')), 'miss');
            return;
        }
        var grade = String(unit.conditionGrade || 'NEW').toUpperCase();
        var label = t('ui.js.condition' + grade.charAt(0) + grade.slice(1).toLowerCase(), grade);
        show(escapeHtml(label), NOTABLE[grade] === 'warn' ? 'warn' : 'ok');
    }

    /** The app's shared escaper where present — this writes into innerHTML. */
    function escapeHtml(s) {
        if (global.escHtml) return global.escHtml(s);
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function lookup(serial) {
        if (serial === lastQueried) return;      // no repeat call on blur after the same value was typed
        lastQueried = serial;

        $.get(global.serverContext + 'serialHistory', { serial: serial })
            .done(function (resp) {
                var rows = (resp && resp.collection) || [];
                // The LIVE row, not merely the newest: a serial that was sold and bought back has history, and
                // the one being sold now is the one on the shelf.
                var live = null;
                for (var i = 0; i < rows.length; i++) {
                    if (String(rows[i].status) === 'IN_STOCK') { live = rows[i]; break; }
                }
                render(serial, live);
            })
            .fail(function () {
                // Silence, deliberately. The lookup is a courtesy; a failed one must not put an error in front
                // of a cashier mid-sale, and the server still refuses an invalid serial at submit.
                hide();
            });
    }

    $(function () {
        var $input = $('#sellSerials');
        if ($input.length === 0) return;          // the tenant has no serial tracking — nothing to do

        $input.on('input', function () {
            var serial = $.trim($(this).val() || '');
            clearTimeout(timer);
            if (serial === '') { lastQueried = null; hide(); return; }
            timer = setTimeout(function () { lookup(serial.toUpperCase()); }, DEBOUNCE_MS);
        });

        // A scanner types fast and then fires change/blur — look up immediately rather than waiting out the
        // debounce, so a scanned handset shows its condition without a visible pause.
        $input.on('change blur', function () {
            var serial = $.trim($(this).val() || '');
            clearTimeout(timer);
            if (serial === '') { lastQueried = null; hide(); return; }
            lookup(serial.toUpperCase());
        });

        // The line has been added to the cart and the form reset — the note belongs to a serial that is no
        // longer on screen, so leaving it up would describe the wrong unit.
        $(global.document).on('serial:clear', function () { lastQueried = null; hide(); });
    });
})(window);
