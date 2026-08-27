/**
 * C5 — the dashboard grid, as a declared set of widgets rather than a fixed block of markup.
 *
 * <h3>What this adds, and what it deliberately does NOT do</h3>
 * Hiding is already solved: `capabilities.js` (C3) removes any `[data-capability]` element the tenant does not
 * have, and a widget carries that attribute like any other section. Re-implementing hiding here would be a
 * second mechanism for one job — the exact duplication the capability design argues against.
 *
 * So this file owns the three things that were genuinely missing:
 *
 *   1. **An inventory.** One place that says what the dashboard contains. Before this, the answer was "read
 *      3,500 lines of template and hope you found them all".
 *   2. **Order.** A tenant that deliberately switched a capability on cares about it more than a generic
 *      count, so its widgets lead. A mobile shop should not find "On terms" seventh, behind Companies.
 *   3. **An extension point.** `register()` lets a widget be added without editing the template, which is what
 *      makes per-vertical dashboards possible later without another 3,500-line file.
 *
 * <h3>It reorders EXISTING nodes; it does not render them</h3>
 * Labels stay server-rendered through Thymeleaf, so the i18n bundles remain the single source of truth for
 * wording. A registry that rendered its own labels in JavaScript would quietly create a second one — and the
 * six bundles carrying 2,000+ keys are not a thing to fork by accident.
 *
 * <h3>Order is keyed on CAPABILITIES, never on a shape name</h3>
 * `if (shape === 'distribution')` is the same mistake as `if (organizationId === 24)`, one level of
 * indirection away. A distributor is recognised by HAVING field sales and collections, not by being called
 * one. Everything below sorts on what the tenant can do.
 */
(function (global) {
    'use strict';

    /**
     * The dashboard's widgets, in their natural order.
     *
     * `capability` mirrors the element's own `data-capability` — stated here too so the inventory is readable
     * on its own, and so ranking can consider it without touching the DOM. `capabilities.js` remains the thing
     * that actually hides; this never sets or clears `.cap-off`.
     */
    var WIDGETS = [
        // KPI tiles
        { name: 'companies',      order: 10 },
        { name: 'venders',        order: 20 },
        { name: 'customers',      order: 30 },
        { name: 'products',       order: 40 },
        { name: 'monthlySales',   order: 50 },
        { name: 'monthlyRevenue', order: 60 },
        { name: 'installmentsDue', order: 70, capability: 'installments' },
        // Charts
        { name: 'chartTrend',     order: 110 },
        { name: 'chartTopItems',  order: 120 },
        { name: 'chartDaily',     order: 130 },
        { name: 'chartCustSales', order: 140 }
    ];

    /**
     * How far a capability-specific widget jumps ahead of the generic ones.
     *
     * Large enough to clear the whole generic band in one step, so relative order WITHIN each band is still the
     * order declared above. A smaller nudge would interleave the two and make the result depend on the exact
     * numbers, which is how an ordering rule stops being explicable.
     */
    var PROMOTION = 1000;

    /** Every registered widget, by name. Later registrations win, so a module can override a definition. */
    var registry = {};
    WIDGETS.forEach(function (w) { registry[w.name] = w; });

    /**
     * Rank a widget: lower sorts first.
     *
     * A widget tied to a capability the tenant HAS is promoted; one with no capability keeps its declared
     * order. A widget whose capability is OFF is not ranked at all — it is hidden, and moving something
     * invisible is wasted work that also makes the DOM order lie about what is on screen.
     */
    function rank(def) {
        if (!def.capability) return def.order;
        return global.hasCapability(def.capability) ? def.order - PROMOTION : def.order;
    }

    /**
     * Reorder widgets within each row they belong to.
     *
     * <p>Per PARENT, deliberately. The KPI tiles and the chart panels live in different `.row` containers with
     * different column widths; sorting them into one sequence would move a chart into the tile row and break
     * the grid. Sorting inside each parent keeps the layout intact and still gives each row its own order.
     */
    function apply(root) {
        var scope = root || global.document;
        var byParent = new Map();

        scope.querySelectorAll('[data-widget]').forEach(function (el) {
            var def = registry[el.getAttribute('data-widget')];
            if (!def) return;                       // markup the registry does not know: leave it exactly where it is
            if (el.classList.contains('cap-off')) return;   // hidden by capabilities.js — nothing to order
            if (!byParent.has(el.parentNode)) byParent.set(el.parentNode, []);
            byParent.get(el.parentNode).push({ el: el, r: rank(def) });
        });

        byParent.forEach(function (items, parent) {
            items.sort(function (a, b) { return a.r - b.r; });
            // appendChild MOVES an existing node, so this reorders in place without re-creating anything —
            // no listener is lost and no chart canvas is re-instantiated. Re-rendering the tiles would destroy
            // the Chart.js instances bound to those canvases.
            items.forEach(function (item) { parent.appendChild(item.el); });
        });
    }

    global.DashboardWidgets = {
        /**
         * Add or replace a widget definition.
         *
         * @param def {name, order, capability?} — `name` must match a `[data-widget]` in the markup. A
         *        definition with no matching element is harmless: it simply never ranks anything.
         */
        register: function (def) {
            if (!def || !def.name) return;
            registry[def.name] = def;
        },
        /** The current inventory, for tests and for anything that wants to know what the dashboard holds. */
        all: function () { return Object.keys(registry).map(function (k) { return registry[k]; }); },
        apply: apply
    };

    // Ordering depends on the capability answer, so wait for it. capabilities.js fires this once the map has
    // been applied; if the call FAILED it never fires and every widget simply keeps its declared order — the
    // same fail-open stance the rest of the capability chain takes.
    if (global.jQuery) {
        global.jQuery(global.document).on('capabilities:ready', function () { apply(global.document); });
    }
})(window);
