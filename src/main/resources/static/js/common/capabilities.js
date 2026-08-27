/**
 * C3 — render only what this tenant's capabilities actually include.
 *
 * <h3>What this replaces, and why</h3>
 * The dashboard decided visibility from `[data-vertical-only]` and `[data-feature]`, both resolved in the
 * browser against a hardcoded `VERTICALS` map in `module-theme.js`. Two problems with that, one cosmetic and
 * one not:
 *
 *   1. The list ships to every client and `window.MODULE` is editable in devtools, so it was never a control.
 *   2. It keys on a VERTICAL — one hardcoded name per business type. But a distributor that sells on terms and
 *      one that does not are the same vertical with different capabilities, and no amount of relabelling a
 *      vertical produces the difference. That is the two-axis argument from the capability design.
 *
 * This asks the SERVER what the tenant may do. The tenant comes from the JWT, so the answer is not something
 * the person it is about can edit.
 *
 * <h3>This is the rendering half. It is not the security half.</h3>
 * Hiding a section removes it from the menu. It does not stop a request. The half that refuses lives on the
 * write paths (`CapabilityService.assertEnabled`), and every capability that guards stock, ledger or tax must
 * have one — a screen hidden here with an endpoint still answering is precisely the defect C1 was written to
 * close. Treat this file as cosmetics with a server-shaped source.
 *
 * <h3>Fails OPEN, deliberately</h3>
 * If the call fails, every section stays visible. A tenant losing a screen it used yesterday because settings
 * hiccuped is a support call; showing a section whose endpoints will refuse anyway is not a leak. Same rule
 * `CapabilityService.isEnabled` follows server-side, for the same reason.
 */
(function (global) {
    'use strict';

    var $ = global.jQuery;

    /** Marks an element the tenant's capabilities exclude. See `applyTo` for why this is a class. */
    var OFF_CLASS = 'cap-off';

    /**
     * Read one element's requirement.
     *
     * `data-capability="fieldSales"`            — needs that capability.
     * `data-capability="fieldSales,collections"` — needs ANY of them (an OR).
     *
     * OR rather than AND because the attribute answers "does this tenant have any reason to see this?". A
     * driver settlement screen is worth showing to a shop with collections OR field sales; requiring both
     * would hide it from a shop that genuinely uses one.
     */
    function requirementOf(el) {
        return (el.getAttribute('data-capability') || '')
            .split(',')
            .map(function (s) { return s.trim(); })
            .filter(function (s) { return s.length > 0; });
    }

    /**
     * Hide everything the map excludes.
     *
     * <p>A CLASS, not `el.style.display`, and that matters: `module-theme.js` walks `[data-vertical-only]` and
     * writes `el.style.display = ''` on anything its vertical allows. An inline hide here would be silently
     * undone for any element carrying both attributes — visible, with no error, in exactly the case where two
     * mechanisms disagree. A class backed by `!important` wins that argument, so the server's answer is the
     * one that survives.
     */
    function applyTo(root, caps) {
        var scope = root || global.document;
        var hidden = 0;

        scope.querySelectorAll('[data-capability]').forEach(function (el) {
            var needs = requirementOf(el);
            if (needs.length === 0) return;                 // empty attribute = no requirement stated

            // Unknown code => true. A capability this build does not know about must not blank a section on a
            // tenant that is mid-upgrade; the server is the authority on the list, and it may be ahead of us.
            var allowed = needs.some(function (code) {
                return caps[code] === undefined ? true : caps[code] === true;
            });

            el.classList.toggle(OFF_CLASS, !allowed);
            if (!allowed) hidden++;
        });

        collapseEmptyGroups(scope);
        return hidden;
    }

    /**
     * Hide a sidebar group whose every entry is gone.
     *
     * <p>Without this a shop that has no pharmacy capabilities still sees a "Pharmacy" heading that opens onto
     * nothing — which reads as a broken menu rather than an absent feature, and is the kind of detail that
     * makes a tenant think the product is half-installed.
     */
    function collapseEmptyGroups(scope) {
        scope.querySelectorAll('.snav-dd').forEach(function (group) {
            var items = group.querySelectorAll('.snav-menu > li');
            if (items.length === 0) return;                 // not a grouped menu; leave it alone

            var anyVisible = Array.prototype.some.call(items, function (li) {
                return !li.classList.contains(OFF_CLASS);
            });
            // Never un-hide a group the group itself was told to hide (it may carry its own requirement).
            if (!anyVisible) group.classList.add(OFF_CLASS);
        });
    }

    /**
     * Fetch the map and apply it.
     *
     * <p>One request for all ~33 sections. The server side is a map lookup against a per-tenant Caffeine cache,
     * not a query, so this is cheap enough to do on every dashboard load rather than cache in the page.
     */
    function load() {
        if (!$) return;

        $.get('/getCapabilities')
            .done(function (res) {
                // The shared wrapper puts the payload under `data`; tolerate a bare map too, so a change in the
                // wrapper does not blank every section on every screen at once.
                var caps = (res && res.data) || (res && res.status === undefined ? res : null);
                if (!caps || typeof caps !== 'object') return;   // fail OPEN — see the file header

                global.CAPS = caps;
                applyTo(global.document, caps);
                // Lets a screen that renders its own markup later re-apply without re-fetching.
                $(global.document).trigger('capabilities:ready', [caps]);
            })
            .fail(function () {
                // Fail OPEN. Nothing is hidden, and every guarded endpoint still refuses on its own.
                global.CAPS = null;
            });
    }

    /** Ask about one capability. Returns true when unknown, matching the fail-open rule above. */
    global.hasCapability = function (code) {
        if (!global.CAPS) return true;
        return global.CAPS[code] === undefined ? true : global.CAPS[code] === true;
    };

    /** Re-apply to markup added after load (a screen that builds its own sections). */
    global.applyCapabilities = function (root) {
        if (!global.CAPS) return 0;
        return applyTo(root || global.document, global.CAPS);
    };

    if (global.document.readyState === 'loading') {
        global.document.addEventListener('DOMContentLoaded', load);
    } else {
        load();
    }
})(window);
