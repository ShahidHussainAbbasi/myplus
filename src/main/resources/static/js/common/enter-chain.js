/**
 * enter-chain.js — the reusable Enter-to-advance engine.
 *
 * P1-P3 built keyboard-first entry for the SALE screen inside pos-keyboard.js. The mechanics there —
 * "is this field usable?", "what is the next usable field?", "focus it, allowing for bootstrap-select" —
 * are not sale-specific at all; only the POLICY is (skip-ahead rules, F-keys, quick-pick, cart).
 *
 * This file owns the mechanics so a second keyboard-first form does not restate them. pos-keyboard.js
 * delegates to it and keeps its own policy; the purchase form (P6) uses bindEnterChain() directly.
 *
 * Deliberately NOT a jQuery plugin and NOT auto-binding: a form opts in by naming its own ordered field
 * list, so nothing changes for a screen that has not asked for it.
 */
(function (global, $) {
    'use strict';

    /**
     * A field participates in a chain only if it is present, visible and editable.
     *
     * This is what makes a chain follow configuration for free: a field the tenant switched off is
     * hidden, so Enter flows past it instead of landing in a control nobody can see.
     *
     * bootstrap-select HIDES the original <select> and renders a button in its place, so asking the
     * <select> whether it is :visible always answers NO — which silently skips every picker. Judge a
     * managed select by the WRAPPER the plugin actually shows.
     */
    function usable(id) {
        var el = document.getElementById(id);
        if (!el) return false;

        // P7: the rule itself belongs to focus-flow.js, which is the one place that answers "is this
        // worth putting a cursor in?". This module used to keep its own copy; the two drifted, and a
        // picker that WAS in the sale chain had no way out of it. Ask the owner instead of restating.
        // Picker visibility is handled inside skip() -> visible(), which measures the wrapper the
        // plugin renders rather than the <select> it hides. Nothing to special-case here any more.
        return !global.FocusFlow.skip(el);
    }

    /** The next USABLE id in `list` after `from`, walking `dir` (+1 forward, -1 back).
     *  Returns null past the forward end (the caller decides what "past the end" means) and stays
     *  put past the start — going back from the first field should not leave the form. */
    function walk(list, from, dir) {
        var i = list.indexOf(from);
        if (i < 0) return null;
        for (var j = i + dir; j >= 0 && j < list.length; j += dir) {
            if (!usable(list[j])) continue;
            return list[j];
        }
        return (dir > 0) ? null : from;
    }

    /** Focus a field. bootstrap-select hides the real <select> behind a button, so focusing the
     *  select itself would silently do nothing. Selecting the text means typing REPLACES rather than
     *  appends — on a pre-filled rate that is the difference between "12" and "1012". */
    function focusField(id) {
        var el = document.getElementById(id);
        if (!el) return false;
        if ($(el).hasClass('selectpicker')) {
            var $btn = $(el).next('.bootstrap-select').find('button').first();
            if ($btn.length) { $btn.trigger('focus'); return true; }
        }
        try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
        if (el.select) { try { el.select(); } catch (e2) {} }
        return true;
    }

    /** The first usable id in `list`, or null. Used as a chain's entry point. */
    function firstUsable(list) {
        for (var i = 0; i < list.length; i++) if (usable(list[i])) return list[i];
        return null;
    }

    /**
     * Bind an Enter-chain to a form.
     *
     * opts:
     *   chain   [String]   ordered field ids
     *   (a bootstrap-select needs no declaring — isPicker() asks the DOM)
     *   active  Function   -> true when the chain should respond at all (e.g. "my modal is open")
     *   onEnd   Function   Enter past the last field. Return true if handled.
     *   onCtrlEnter Fn     optional: Ctrl+Enter anywhere in the form
     *   onEscape    Fn     optional: Escape anywhere in the form
     *
     * Handlers are delegated on `document` so they survive the form being re-rendered, and are bound
     * ONCE per chain name.
     */
    function bindEnterChain(name, opts) {
        if (bindEnterChain[name]) return;         // idempotent: a second call is a no-op
        bindEnterChain[name] = true;

        // The chain is resolved PER KEYSTROKE, never captured at bind time.
        //
        // `container` derives it from the DOM (preferred — see derivedChain). `chain` takes an explicit
        // list, which the sale screen needs: that one form carries TWO deliberate chains, line entry
        // and checkout, so "every field in the form" is the wrong answer there.
        //
        // Either way it is re-read on every press, because a chain captured at bind time describes the
        // screen as it was when the page loaded — before the tenant's settings hid a field, before the
        // responsive layout moved one off the row, before an async load added one.
        var chainOf = (typeof opts.chain === 'function') ? opts.chain
                    : opts.container ? function () { return derivedChain(opts.container); }
                    : function () { return opts.chain || []; };
        var active  = opts.active || function () { return true; };

        // A picker whose menu is OPEN has already consumed one Enter. Tracking the menu state rather
        // than using a timer is what makes double-Enter reliable — a timer races the user.
        function menuOpen(id) {
            return $('#' + id).next('.bootstrap-select').hasClass('open');
        }

        /** Is this field a bootstrap-select? Asked of the DOM, not of a list the caller maintains —
         *  a picker list is one more copy of the form, and copies drift. The wrapper's existence IS
         *  the definition, so a select that gets enhanced later is picked up with no registration. */
        function isPicker(id) {
            return $('#' + id).next('.bootstrap-select').length > 0;
        }

        function hasValue(id) {
            var v = $('#' + id).val();
            return !(v === null || v === '' || v === 'default' || (Array.isArray(v) && !v.length));
        }

        /**
         * The stall this closes.
         *
         * When the menu is open, Enter belongs to the plugin: it selects the highlighted row and fires
         * changed.bs.select, which advances the chain. But re-picking the value that was ALREADY
         * selected fires no change event at all — so the menu closes, nothing advances, and the
         * operator is left pressing Enter at a field that will not move. That is the original
         * "Enter is not taking control to the next field" report, and it was never really about
         * opening: it was about this silent dead end.
         *
         * So arm a fallback: if the menu has closed and no change carried us onward, move on anyway.
         */
        var pendingPicker = null;
        function armAdvanceFallback(id) {
            pendingPicker = id;
            global.setTimeout(function () {
                if (pendingPicker !== id) return;       // a real selection already advanced us
                pendingPicker = null;
                if (!menuOpen(id)) advance(id, 1);
            }, 150);
        }

        function advance(from, dir) {
            var next = walk(chainOf(), from, dir);
            if (next) { focusField(next); return true; }
            if (dir > 0 && typeof opts.onEnd === 'function') return opts.onEnd() === true;
            return false;
        }

        // bootstrap-select fires this when a value is CHOSEN — the natural moment to move on, so
        // picking with the keyboard and picking with the mouse both advance the same way.
        $(document).on('changed.bs.select', function (e) {
            if (!active()) return;
            var id = e.target && e.target.id;
            if (!id || chainOf().indexOf(id) < 0) return;
            // A real selection carries us onward, so disarm the fallback — otherwise both fire and
            // the chain jumps two fields for one keystroke.
            pendingPicker = null;

            // P7.3 — a MULTIPLE select is not answered by one click.
            //
            // bootstrap-select fires this event on every individual toggle and leaves the menu open,
            // because that is what choosing several things looks like. Advancing on the first one took
            // the cursor away mid-choice: on education's School form, `schoolOwnerDD` is a REQUIRED
            // multi-select AND the first field, so naming a second owner was impossible without the
            // mouse the moment the chain was switched on.
            //
            // A multi-select is answered by CLOSING it, and Enter on a closed, answered picker already
            // advances like any other field — so the way out is unchanged, it is just no longer taken
            // for the operator. Single selects are untouched.
            if (e.target.multiple) return;

            // Let the change handlers (price pre-fill, stock lookup) run before moving focus.
            global.setTimeout(function () { advance(id, 1); }, 0);
        });

        /**
         * Is something smaller than the form currently listening for Escape?
         *
         * The innermost open thing owns Escape. Without this the chain's Escape — registered on
         * `document` in the CAPTURE phase at page load — fires BEFORE the handler that a calendar
         * registers when it OPENS (same target, same phase, so registration order decides), and
         * "close the calendar" became "close the whole modal and throw the part-typed record away".
         * Education's Student form has four date fields and eleven dropdowns, so this was reachable
         * on almost every record.
         *
         * `.dp-active` marks the input whose calendar is up (date-picker.js), `.bootstrap-select.open`
         * an open dropdown menu. Both plugins already close themselves on Escape; the only thing
         * needed here is to get out of their way.
         *
         * Scoped to this chain's own container where there is one. A modal that was closed while a
         * picker was still open leaves that `.open` class behind, and an unscoped query would let a
         * dead dropdown three sections away veto Escape on the form actually in front of the user.
         */
        function transientOwnsEscape() {
            var box = (opts.container && document.querySelector(opts.container)) || document;
            return !!box.querySelector('.bootstrap-select.open, .dp-active');
        }

        // CAPTURE phase, deliberately. bootstrap-select binds its own keydown to the button it renders,
        // and a bubbling listener runs AFTER that — so Enter on a picker would open the plugin's menu
        // and advance the chain, doing both at once. Capturing lets stopPropagation() decide which of
        // the two happens.
        document.addEventListener('keydown', function (e) {
            if (!active()) return;

            var id = e.target && e.target.id;
            // The focused element inside a bootstrap-select is its BUTTON, not the <select>.
            if (!id || chainOf().indexOf(id) < 0) {
                var $owner = $(e.target).closest('.bootstrap-select').prev('select');
                id = $owner.length ? $owner.attr('id') : null;
            }

            if (e.key === 'Escape' && typeof opts.onEscape === 'function') {
                // Defer to an open calendar / dropdown — see transientOwnsEscape(). Returning without
                // preventDefault lets the plugin's own (later, bubbling) handler close just itself.
                if (transientOwnsEscape()) return;
                if (opts.onEscape() === true) { e.preventDefault(); }
                return;
            }
            if (e.key !== 'Enter') return;

            // Ctrl+Enter FIRST, so the "I am finished" key works from every field including a
            // textarea — otherwise the textarea guard below would swallow the one escape hatch out
            // of a textarea and the operator would be stuck typing newlines.
            if ((e.ctrlKey || e.metaKey) && typeof opts.onCtrlEnter === 'function') {
                e.preventDefault();
                opts.onCtrlEnter();
                return;
            }

            // A <textarea> keeps its plain Enter. Enter inserts a NEWLINE there — that is what the
            // control is for, and stealing it makes every address and description box in the app
            // unable to hold a second line. Leave one with Tab or Ctrl+Enter (above).
            if (e.target && e.target.tagName === 'TEXTAREA') return;

            // P7.3 — §3: touch and narrow screens are excluded from the WALK.
            //
            // A soft keyboard already labels its Enter "Next"/"Go" and the browser already decides what
            // that means; taking it over there is worse than doing nothing, which is why focus-flow has
            // refused to auto-focus below 992px since it was written. Same predicate, so "where the app
            // puts the cursor" and "where Enter moves the cursor" cannot disagree by screen size.
            //
            // Ctrl+Enter and Escape are handled ABOVE this line and stay live. They are explicit intent
            // rather than navigation — nobody presses Ctrl+Enter on a phone by accident (or at all), and
            // disabling them would only take Esc-closes-the-form away from someone on a narrow desktop
            // window, which is a loss with nothing bought for it.
            if (!global.FocusFlow.mayAutoFocus()) return;

            if (!id) return;

            // ── Dropdowns ─────────────────────────────────────────────────────────────────────
            //
            // What Enter does depends on whether the field has been ANSWERED, because the operator
            // means two different things by the same key:
            //
            //   menu open        the plugin owns it — Enter selects the highlighted row, and
            //                    changed.bs.select advances. armAdvanceFallback covers re-picking
            //                    the same value, which fires no change event.
            //   closed, empty    "I still have to choose" -> let the button's own click OPEN the menu.
            //                    Returning WITHOUT preventDefault is what allows that: preventing the
            //                    default is precisely what used to stop the menu from ever opening.
            //   closed, answered "already set" -> advance like any other field.
            //
            // The previous rule advanced in every closed case. It made a required dropdown impossible
            // to fill from the keyboard: Enter walked straight past 71 companies without showing one,
            // leaving a required field empty and no way to set it without the mouse.
            // Shift+Enter is "go back" and never opens anything — it must reach the walk below even
            // on an unanswered picker, or the chain cannot be reversed out of an empty dropdown.
            if (isPicker(id) && !e.shiftKey) {
                if (menuOpen(id)) { armAdvanceFallback(id); return; }
                if (!hasValue(id)) {
                    // Open it with a NATIVE click on the button the plugin renders — exactly what a
                    // mouse user does, so every delegated handler (bootstrap's dropdown data-api and
                    // bootstrap-select's own) runs the same way.
                    //
                    // Not selectpicker('toggle'): bootstrap-select 1.6.2 has no such method, and an
                    // unknown method there is a SILENT no-op rather than an error — so a try/catch
                    // around it catches nothing and the fallback never runs. A guard only helps
                    // against failures that announce themselves.
                    e.preventDefault();
                    e.stopPropagation();
                    var btn = $('#' + id).next('.bootstrap-select').find('button')[0];
                    if (btn) btn.click();
                    return;
                }
            }

            e.preventDefault();
            e.stopPropagation();
            advance(id, e.shiftKey ? -1 : 1);
        }, true);
    }

    /**
     * P7 — the DERIVED chain: the ordered field ids inside `selector`, computed from the DOM.
     *
     * This is the form to prefer. A hand-written id list is a second copy of the form, and the copy
     * drifts — twice already in this codebase: a picker listed in the sale chain that the movement
     * handler did not know about (a keyboard dead end on the busiest screen), and a chain grouped by
     * meaning while the form was laid out differently (Enter jumping row 1 -> row 8 -> row 3).
     * Computing it means the list cannot disagree with the form, because it IS the form.
     *
     * Recomputed on every keystroke, not cached: fields appear and disappear as the tenant's settings
     * and the responsive layout change, and a chain captured at bind time would be describing a screen
     * that no longer exists.
     */
    function derivedChain(selector) {
        var box = document.querySelector(selector);
        if (!box) return [];
        return global.FocusFlow.fields(box)
            .filter(function (el) { return el.id; })
            .map(function (el) { return el.id; });
    }

    global.EnterChain = {
        usable: usable,
        walk: walk,
        focusField: focusField,
        firstUsable: firstUsable,
        fieldsIn: derivedChain,
        bind: bindEnterChain
    };
})(window, jQuery);
