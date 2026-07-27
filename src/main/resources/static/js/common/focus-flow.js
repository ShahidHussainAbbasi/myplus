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

    function visible(el) {
        return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
    }

    function skip(el) {
        if (!el || el.disabled || el.readOnly) return true;
        if (el.type === 'hidden' || el.type === 'submit' || el.type === 'button' || el.type === 'reset') return true;
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
})();
