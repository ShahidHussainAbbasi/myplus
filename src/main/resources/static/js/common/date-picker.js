/**
 * Date / datetime picker — ONE self-contained calendar for the whole app. No library, no vendored file, no
 * jQuery plugin: it works the moment the page loads.
 *
 * WHY IT EXISTS
 *
 * 1. A bug. #purchaseDate and #purchaseExpiry were bound to TWO plugins at once (the shared `.datePicker`
 *    bootstrap-datetimepicker AND a per-id bootstrap-datepicker). Two pickers on one input fight over the value:
 *    each re-parses it with its own format on blur/hide, and whichever can't parse the other's output writes back
 *    an empty string — so tabbing out wiped the field. Binding happens HERE and nowhere else, so it can't recur.
 *
 * 2. A calendar worth using: big tap targets, month/year jump, Today/Clear, inline time, keyboard support, and
 *    a month-grid mode for MM-yyyy fields (picking a month from a day grid was always silly).
 *
 * THE WIRE FORMAT IS A CONTRACT. The monolith and business/education/welfare/agriculture AppUtil parse these
 * exact strings; emitting anything else silently breaks saves. Do not change them without changing AppUtil on
 * both sides first.
 *
 *   class                      value written                  server pattern
 *   .datePicker                18-07-2026                     dd-MM-yyyy
 *   .datetimepicker            18-07-2026 14:30:00            dd-MM-yyyy HH:mm:ss
 *   .datePickerWithMonthName   18-Jul-2026                    dd-MMM-yyyy
 *   .monthYearDatePicker       07-2026                        MM-yyyy
 *
 * The input stays a plain text box, so typing still works for fast data entry; the calendar only ever writes a
 * correctly formatted value back.
 */
(function () {
    'use strict';

    var MONTHS = ['January','February','March','April','May','June','July','August','September','October','November','December'];
    var MON = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    var DOW = ['Mo','Tu','We','Th','Fr','Sa','Su'];

    // mode: 'date' | 'datetime' | 'monthname' | 'month'
    //
    // A field can join by CLASS (the four legacy ones below) or, for anything new, by attribute:
    //     data-dp="date"                  → which calendar to show
    //     data-dp-iso="#someHiddenField"  → ALSO mirror the pick into that field as ISO (yyyy-MM-dd)
    // The attribute form is how #dueDateTemp works: the visible box shows dd-MM-yyyy while the hidden #dueDate
    // it feeds keeps the ISO value the app reads. Prefer it over adding another magic class.
    var MODES = ['date', 'datetime', 'monthname', 'month'];
    var SPECS = [
        { sel: '.datePicker',              mode: 'date' },
        { sel: '.datetimepicker',          mode: 'datetime' },
        { sel: '.datePickerWithMonthName', mode: 'monthname' },
        { sel: '.monthYearDatePicker',     mode: 'month' }
    ];

    /** yyyy-MM-dd (or yyyy-MM-ddTHH:mm:ss for datetime) — what a data-dp-iso companion field receives. */
    function isoOf(d, mode) {
        var s = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
        return mode === 'datetime' ? s + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) : s;
    }

    /** The companion field named by data-dp-iso, if any. */
    function isoTarget(input) {
        var sel = input.getAttribute('data-dp-iso');
        return sel ? document.querySelector(sel) : null;
    }

    var pad = function (n) { return (n < 10 ? '0' : '') + n; };

    /** Format a Date exactly as the server expects for this mode. */
    function format(d, mode) {
        var day = pad(d.getDate()), m = pad(d.getMonth() + 1), y = d.getFullYear();
        if (mode === 'month') return m + '-' + y;
        if (mode === 'monthname') return day + '-' + MON[d.getMonth()] + '-' + y;
        if (mode === 'datetime') {
            return day + '-' + m + '-' + y + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
        }
        return day + '-' + m + '-' + y;
    }

    /** Read whatever is already in the box. Accepts the mode's own format, plus ISO, so edit screens work. */
    function parse(value, mode) {
        var s = (value || '').trim();
        if (!s) return null;
        var m;
        if ((m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?$/))) {      // ISO
            return new Date(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0));
        }
        if ((m = s.match(/^(\d{2})-(\d{2})-(\d{4})(?:\s+(\d{2}):(\d{2})(?::(\d{2}))?)?$/))) {       // dd-MM-yyyy [HH:mm:ss]
            return new Date(+m[3], +m[2] - 1, +m[1], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0));
        }
        if ((m = s.match(/^(\d{1,2})-([A-Za-z]{3})-(\d{4})$/))) {                                   // dd-MMM-yyyy
            var i = MON.indexOf(m[2].charAt(0).toUpperCase() + m[2].slice(1, 3).toLowerCase());
            if (i >= 0) return new Date(+m[3], i, +m[1]);
        }
        if ((m = s.match(/^(\d{2})-(\d{4})$/))) return new Date(+m[2], +m[1] - 1, 1);               // MM-yyyy
        return null;
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    var open = null;   // { input, pop, mode, view, onDoc, onKey }

    function close() {
        if (!open) return;
        document.removeEventListener('mousedown', open.onDoc, true);
        document.removeEventListener('keydown', open.onKey, true);
        window.removeEventListener('resize', close);
        window.removeEventListener('scroll', close, true);
        if (open.pop.parentNode) open.pop.parentNode.removeChild(open.pop);
        open.input.classList.remove('dp-active');
        open = null;
    }

    function place(pop, input) {
        var r = input.getBoundingClientRect();
        var h = pop.offsetHeight, w = pop.offsetWidth;
        var below = window.innerHeight - r.bottom;
        var top = (below < h + 12 && r.top > h + 12) ? (r.top - h - 6) : (r.bottom + 6);
        var left = Math.min(Math.max(8, r.left), window.innerWidth - w - 8);
        pop.style.top = top + 'px';
        pop.style.left = left + 'px';
    }

    function build(input, mode) {
        var current = parse(input.value, mode) || new Date();
        var selected = parse(input.value, mode);
        var view = { y: current.getFullYear(), m: current.getMonth() };
        var time = {
            h: selected ? selected.getHours() : 0,
            i: selected ? selected.getMinutes() : 0,
            s: selected ? selected.getSeconds() : 0
        };

        var pop = el('div', 'dp-pop');
        pop.setAttribute('role', 'dialog');

        function commit(d) {
            if (mode === 'datetime') d.setHours(time.h, time.i, time.s, 0);
            input.value = format(d, mode);
            input.style.removeProperty('border-color');
            var iso = isoTarget(input);                       // e.g. #dueDateTemp → hidden #dueDate
            if (iso) {
                iso.value = isoOf(d, mode);
                iso.dispatchEvent(new Event('change', { bubbles: true }));
            }
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
        }

        function render() {
            pop.textContent = '';

            // ── header: ‹ month year › ────────────────────────────────────────────────────────────────────
            var head = el('div', 'dp-head');
            var prev = el('button', 'dp-nav', '‹'); prev.type = 'button'; prev.setAttribute('aria-label', 'Previous');
            var next = el('button', 'dp-nav', '›'); next.type = 'button'; next.setAttribute('aria-label', 'Next');
            var title = el('div', 'dp-title');

            if (mode === 'month') {
                title.textContent = view.y;
                prev.onclick = function () { view.y--; render(); };
                next.onclick = function () { view.y++; render(); };
            } else {
                var msel = el('select', 'dp-sel');
                MONTHS.forEach(function (name, i) {
                    var o = el('option', null, name); o.value = i; if (i === view.m) o.selected = true; msel.appendChild(o);
                });
                msel.onchange = function () { view.m = +msel.value; render(); };
                var ysel = el('select', 'dp-sel');
                for (var y = view.y - 80; y <= view.y + 20; y++) {
                    var o2 = el('option', null, y); o2.value = y; if (y === view.y) o2.selected = true; ysel.appendChild(o2);
                }
                ysel.onchange = function () { view.y = +ysel.value; render(); };
                title.appendChild(msel); title.appendChild(ysel);
                prev.onclick = function () { if (--view.m < 0) { view.m = 11; view.y--; } render(); };
                next.onclick = function () { if (++view.m > 11) { view.m = 0; view.y++; } render(); };
            }
            head.appendChild(prev); head.appendChild(title); head.appendChild(next);
            pop.appendChild(head);

            // ── body: month grid (MM-yyyy fields) or day grid ─────────────────────────────────────────────
            if (mode === 'month') {
                var grid = el('div', 'dp-months');
                MON.forEach(function (name, i) {
                    var b = el('button', 'dp-month', name); b.type = 'button';
                    if (selected && selected.getFullYear() === view.y && selected.getMonth() === i) b.classList.add('is-sel');
                    b.onclick = function () { commit(new Date(view.y, i, 1)); close(); };
                    grid.appendChild(b);
                });
                pop.appendChild(grid);
            } else {
                var dows = el('div', 'dp-dow');
                DOW.forEach(function (d) { dows.appendChild(el('span', null, d)); });
                pop.appendChild(dows);

                var days = el('div', 'dp-days');
                var first = new Date(view.y, view.m, 1);
                var lead = (first.getDay() + 6) % 7;                       // Monday-first
                var count = new Date(view.y, view.m + 1, 0).getDate();
                var prevCount = new Date(view.y, view.m, 0).getDate();
                var today = new Date();

                for (var i = 0; i < lead; i++) {
                    days.appendChild(el('button', 'dp-day is-out', prevCount - lead + 1 + i)).type = 'button';
                }
                for (var d = 1; d <= count; d++) {
                    (function (day) {
                        var b = el('button', 'dp-day', day); b.type = 'button';
                        if (today.getFullYear() === view.y && today.getMonth() === view.m && today.getDate() === day) b.classList.add('is-today');
                        if (selected && selected.getFullYear() === view.y && selected.getMonth() === view.m && selected.getDate() === day) b.classList.add('is-sel');
                        b.onclick = function () { commit(new Date(view.y, view.m, day)); if (mode !== 'datetime') close(); else { selected = new Date(view.y, view.m, day); render(); } };
                        days.appendChild(b);
                    })(d);
                }
                var trail = (7 - ((lead + count) % 7)) % 7;
                for (var t = 1; t <= trail; t++) days.appendChild(el('button', 'dp-day is-out', t)).type = 'button';
                pop.appendChild(days);

                // ── time row (datetime fields only) ───────────────────────────────────────────────────────
                if (mode === 'datetime') {
                    var row = el('div', 'dp-time');
                    row.appendChild(el('span', 'dp-time-label', 'Time'));
                    ['h', 'i', 's'].forEach(function (key, idx) {
                        if (idx) row.appendChild(el('span', 'dp-colon', ':'));
                        var n = el('input', 'dp-num');
                        n.type = 'number'; n.min = 0; n.max = idx === 0 ? 23 : 59; n.value = pad(time[key]);
                        n.onchange = n.oninput = function () {
                            var v = Math.max(0, Math.min(+n.max, parseInt(n.value, 10) || 0));
                            time[key] = v;
                            if (selected) commit(new Date(selected.getFullYear(), selected.getMonth(), selected.getDate()));
                        };
                        row.appendChild(n);
                    });
                    pop.appendChild(row);
                }
            }

            // ── footer ────────────────────────────────────────────────────────────────────────────────────
            var foot = el('div', 'dp-foot');
            var todayBtn = el('button', 'dp-btn', mode === 'month' ? 'This month' : 'Today'); todayBtn.type = 'button';
            todayBtn.onclick = function () { var n = new Date(); commit(mode === 'month' ? new Date(n.getFullYear(), n.getMonth(), 1) : n); close(); };
            var clearBtn = el('button', 'dp-btn dp-clear', 'Clear'); clearBtn.type = 'button';
            clearBtn.onclick = function () {
                input.value = '';
                var isoEl = isoTarget(input);                 // clear the companion too, or it keeps a stale date
                if (isoEl) { isoEl.value = ''; isoEl.dispatchEvent(new Event('change', { bubbles: true })); }
                input.dispatchEvent(new Event('change', { bubbles: true }));
                close();
            };
            foot.appendChild(clearBtn); foot.appendChild(todayBtn);
            pop.appendChild(foot);

            place(pop, input);
        }

        document.body.appendChild(pop);
        render();
        return pop;
    }

    function openFor(input, mode) {
        if (open && open.input === input) return;
        close();
        if (input.readOnly || input.disabled) return;
        var pop = build(input, mode);
        input.classList.add('dp-active');
        open = {
            input: input, pop: pop, mode: mode,
            onDoc: function (e) { if (!pop.contains(e.target) && e.target !== input) close(); },
            onKey: function (e) { if (e.key === 'Escape') { e.preventDefault(); close(); input.focus(); } }
        };
        document.addEventListener('mousedown', open.onDoc, true);
        document.addEventListener('keydown', open.onKey, true);
        window.addEventListener('resize', close);
        window.addEventListener('scroll', close, true);
        place(pop, input);
    }

    /**
     * Type-to-enter masking for the dd-MM-yyyy modes.
     *
     * The calendar is faster than typing for "some day next month" and slower than typing for "the date
     * I already know", which is what receiving a delivery is — the date is printed on the paperwork in
     * front of you. Keyboard-first entry (P6) needs the second case to work without touching the mouse.
     *
     * Separators are inserted as digits arrive, so the operator types 11082026 and the box reads
     * 11-08-2026. The wire format is a CONTRACT parsed by AppUtil on both sides, so masking emits
     * exactly that and nothing else.
     *
     * Only reformats while the caret is at the END. Rewriting the value under a caret parked in the
     * middle makes it jump, and a date box people edit mid-string is worse than one that only appends.
     * An unparseable half-typed value is LEFT ALONE on blur — this field has a history of being cleared
     * on blur by a picker that could not read its own output, and clearing what someone typed is the
     * one behaviour it must never have again.
     */
    var MASKS = { date: [2, 2, 4], datetime: [2, 2, 4], monthname: [2, 2, 4], month: [2, 4] };

    function maskDigits(digits, groups, mode) {
        var out = [], at = 0;
        for (var g = 0; g < groups.length && at < digits.length; g++) {
            out.push(digits.substr(at, groups[g]));
            at += groups[g];
        }
        // dd-MMM-yyyy is the only format whose middle group is not the number the operator typed.
        // Nobody wants to type "Jul", so the mask accepts 07 and renders Jul — the same digits as every
        // other date box, the contract's own spelling on the wire.
        if (mode === 'monthname' && out.length >= 2 && out[1].length === 2) {
            var m = parseInt(out[1], 10);
            if (m < 1 || m > 12) return null;          // impossible month: leave what they typed alone
            out[1] = MON[m - 1];
        }
        return out.join('-');
    }

    function bindMask(input, mode) {
        var groups = MASKS[mode];
        if (!groups) return;                       // dd-MMM-yyyy has a text month; leave it to the calendar
        var max = groups.reduce(function (a, b) { return a + b; }, 0);

        input.addEventListener('input', function () {
            var atEnd = input.selectionStart === input.value.length;
            if (!atEnd) return;
            var raw = input.value;
            // In datetime mode the time half is typed after a space — mask only the date half.
            var head = mode === 'datetime' ? raw.split(' ')[0] : raw;
            var tail = mode === 'datetime' && raw.indexOf(' ') >= 0 ? raw.slice(raw.indexOf(' ')) : '';
            // Turn the month NAME back into its digits first. Without this the mask eats its own
            // output: "11-Jul-2026" strips to "112026", which re-masks as "11-20-26" — every further
            // keystroke corrupting the field a little more.
            if (mode === 'monthname') {
                head = head.replace(/[A-Za-z]{3}/, function (name) {
                    var i = MON.indexOf(name.charAt(0).toUpperCase() + name.slice(1, 3).toLowerCase());
                    return i >= 0 ? ('0' + (i + 1)).slice(-2) : name;
                });
            }
            var digits = head.replace(/\D/g, '').slice(0, max);
            var body = maskDigits(digits, groups, mode);
            if (body === null) return;                 // mask refused it; do not fight the typist
            var masked = body + tail;
            if (masked !== raw) {
                input.value = masked;
                try { input.setSelectionRange(masked.length, masked.length); } catch (e) {}
            }
        });
    }

    function bind(input, mode) {
        if (input._dpBound) return;
        input._dpBound = true;
        input.setAttribute('autocomplete', 'off');
        bindMask(input, mode);
        input.addEventListener('focus', function () { openFor(input, mode); });
        input.addEventListener('click', function () { openFor(input, mode); });
        // Keyboard entry moves focus WITHOUT a mousedown, so the outside-click listener never fires and
        // the calendar was left hanging over whatever field the chain advanced to. Close on the way out
        // — but only when focus actually left the popup, or clicking a day would dismiss the calendar
        // before it could register the click.
        input.addEventListener('blur', function (e) {
            var to = e.relatedTarget;
            if (open && open.input === input && !(to && open.pop.contains(to))) close();
        });
    }

    /** Bind every date input on the page. Safe to call again after injecting markup — bound inputs are skipped. */
    window.initDatePickers = function () {
        SPECS.forEach(function (spec) {
            Array.prototype.forEach.call(document.querySelectorAll(spec.sel), function (input) {
                bind(input, spec.mode);
            });
        });
        Array.prototype.forEach.call(document.querySelectorAll('[data-dp]'), function (input) {
            var mode = input.getAttribute('data-dp');
            bind(input, MODES.indexOf(mode) >= 0 ? mode : 'date');
        });
    };

    // Late-rendered forms (CRUD modals, tab switches) get picked up without every caller remembering to re-init.
    // DEBOUNCED on purpose: DataTables redraws fire hundreds of mutations, and re-scanning the document on each
    // one would be a real cost on the grid-heavy screens. One scan per idle frame is plenty — binding is cheap
    // and already skips inputs it has seen.
    if (window.MutationObserver) {
        var pending = null;
        new MutationObserver(function () {
            if (pending) return;
            pending = setTimeout(function () { pending = null; window.initDatePickers(); }, 120);
        }).observe(document.documentElement, { childList: true, subtree: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', window.initDatePickers);
    } else {
        window.initDatePickers();
    }
})();
