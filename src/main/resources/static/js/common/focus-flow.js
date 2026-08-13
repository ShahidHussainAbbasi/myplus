/**
 * Focus flow — put the user where the work is, on screens too tall to see at once.
 *
 * The dashboards are long: switching to a section, opening a record, or failing validation could all leave the
 * relevant thing off-screen, so the user had to hunt and scroll. These three helpers are the whole feature:
 *
 *   revealSection(el)   scroll a just-shown section under the sticky header, then focus its first field
 *   focusFirstField(el) focus the first thing worth typing into inside a container (used by modals)
 *   focusInvalid(el)    scroll to a rejected field and put the cursor in it (used by validateForm)
 *
 * Deliberate restraint:
 *  - NO auto-focus on touch / narrow screens. Focusing an input there yanks up the on-screen keyboard and hides
 *    half the page — worse than the scrolling we're avoiding. Those devices still get the scroll.
 *  - Scrolling accounts for the sticky header, otherwise the thing we scroll to sits underneath it.
 *  - `.no-autofocus` on a field (or a container) opts out, for screens where landing in a box is wrong —
 *    the POS sell screen, say, where the barcode box has its own focus rules.
 *  - Respects prefers-reduced-motion: those users get an instant jump, not a smooth glide.
 */
(function () {
    'use strict';

    var HEADER_OFFSET = 84;                 // sticky topbar + a little breathing room
    var TYPEABLE = 'input, select, textarea';

    function reducedMotion() {
        return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    }

    /** Auto-focus is desktop-only: on a phone it opens the keyboard over the content the user wants to see. */
    function mayAutoFocus() {
        return window.innerWidth >= 992 && !('ontouchstart' in window);
    }

    /**
     * Is the control the USER sees on screen?
     *
     * For a bootstrap-select that is not the <select> itself. The plugin hides the original and renders
     * a button in its place, so measuring the <select> always answers "invisible" — which silently
     * removed every picker from any walk built on this. Measure what the plugin actually shows.
     *
     * This also un-breaks focusFirstField(): a modal whose first field is a picker previously got no
     * auto-focus at all, because the only candidate looked hidden.
     */
    function visualEl(el) {
        if (el && el.classList && el.classList.contains('selectpicker')) {
            var wrap = el.nextElementSibling;
            if (wrap && wrap.classList && wrap.classList.contains('bootstrap-select')) return wrap;
        }
        return el;
    }

    function visible(el) {
        var v = visualEl(el);
        return !!(v && (v.offsetWidth || v.offsetHeight || v.getClientRects().length));
    }

    /**
     * Is this element NOT worth putting a cursor in?
     *
     * P7: this is now the ONE definition of that question for the whole app. enter-chain.js used to
     * carry its own copy for the Enter chains, and the two drifted — a picker ended up listed in the
     * sale chain while the handler that moved off it did not know about it, so Enter walked the
     * cashier onto a control nothing could walk them off. Same rule, two homes, one dead end.
     *
     * `data-kbd-skip` is the markup opt-out. It exists because "editable" and "worth stopping on" are
     * different questions: a computed total is a real <input> that submits its value, but stopping the
     * keyboard on it costs a keystroke and offers nothing to type. readOnly would also remove it from
     * the chain, but readOnly changes what the FORM means, and a display decision should not.
     */
    function skip(el) {
        if (!el || el.disabled || el.readOnly) return true;
        if (el.type === 'hidden' || el.type === 'submit' || el.type === 'button' || el.type === 'reset') return true;
        if (el.hasAttribute && el.hasAttribute('data-kbd-skip')) return true;
        if (el.closest('.no-autofocus') || el.classList.contains('no-autofocus')) return true;
        return !visible(el);
    }

    /** bootstrap-select hides the real <select> behind a button — focusing the select itself does nothing. */
    function focusTarget(el) {
        if (el.classList && el.classList.contains('selectpicker')) {
            var wrap = el.nextElementSibling;
            var btn = wrap && wrap.classList.contains('bootstrap-select') ? wrap.querySelector('button') : null;
            return btn || el;
        }
        return el;
    }

    function scrollTo(el, block) {
        if (!el) return;
        try {
            el.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: block || 'center' });
        } catch (e) {
            el.scrollIntoView();                            // older browsers ignore the options object
        }
    }

    /** Scroll so the element sits below the sticky header rather than under it. */
    function scrollToTopOf(el) {
        if (!el) return;
        var y = window.pageYOffset + el.getBoundingClientRect().top - HEADER_OFFSET;
        try {
            window.scrollTo({ top: Math.max(0, y), behavior: reducedMotion() ? 'auto' : 'smooth' });
        } catch (e) {
            window.scrollTo(0, Math.max(0, y));
        }
    }

    /** Focus the first field worth typing into. Returns the element it focused, or null. */
    window.focusFirstField = function (container) {
        if (!container || !mayAutoFocus()) return null;
        var fields = container.querySelectorAll(TYPEABLE);
        for (var i = 0; i < fields.length; i++) {
            if (skip(fields[i])) continue;
            var t = focusTarget(fields[i]);
            try { t.focus({ preventScroll: true }); } catch (e) { t.focus(); }
            return fields[i];
        }
        return null;
    };

    /**
     * A section (a .formDiv, a report pane) just became visible: bring it into view and land the cursor in it.
     * The scroll happens even when auto-focus doesn't, so phones still jump to the right place.
     */
    window.revealSection = function (el) {
        if (!el) return;
        // Let the browser finish showing it (display:none → block) before measuring where it is.
        window.requestAnimationFrame(function () {
            scrollToTopOf(el);
            window.focusFirstField(el);
        });
    };

    /** A field was rejected: scroll it into the middle of the screen and put the cursor in it. */
    window.focusInvalid = function (el) {
        if (!el) return;
        var t = focusTarget(el);
        scrollTo(el, 'center');
        if (mayAutoFocus()) { try { t.focus({ preventScroll: true }); } catch (e) { t.focus(); } }
    };

    /**
     * P7 — the shared field model.
     *
     * Published so enter-chain.js can ask THIS module "is this a stop, and what do I actually focus?"
     * instead of keeping a second copy of the answer. Anything that walks fields — the Enter chains,
     * modal auto-focus, validation focus — now agrees by construction rather than by review.
     */
    window.FocusFlow = {
        TYPEABLE: TYPEABLE,
        skip: skip,
        visible: visible,
        focusTarget: focusTarget,
        mayAutoFocus: mayAutoFocus,

        /**
         * The ordered list of fields a keyboard walk should visit inside `container`.
         *
         * DOM order IS the layout, so the walk follows what the eye follows. P6 learned this the hard
         * way: its first chain was grouped by meaning (header fields, then line fields) while the form
         * was laid out differently, and Enter jumped row 1 -> row 8 -> back to row 3. A chain the eye
         * cannot follow is worse than no chain, because the operator must look up after every press.
         *
         * `data-kbd-order` overrides position for the rare form whose visual order genuinely differs
         * from its DOM order (a CSS-reordered grid). Absent, everything keeps document order — a
         * stable sort, so declaring one field's position does not scramble the rest.
         */
        fields: function (container) {
            if (!container) return [];
            var out = [];
            Array.prototype.forEach.call(container.querySelectorAll(TYPEABLE), function (el, i) {
                if (skip(el)) return;
                var o = el.getAttribute('data-kbd-order');
                out.push({ el: el, ord: (o === null ? null : parseInt(o, 10)), doc: i });
            });
            out.sort(function (a, b) {
                if (a.ord !== null && b.ord !== null && a.ord !== b.ord) return a.ord - b.ord;
                if (a.ord !== null && b.ord === null) return a.ord - b.doc || -1;
                if (a.ord === null && b.ord !== null) return a.doc - b.ord || 1;
                return a.doc - b.doc;
            });
            return out.map(function (r) { return r.el; });
        }
    };
})();
