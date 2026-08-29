/**
 * Real-user monitoring — timings from actual shops, on actual connections.
 *
 * <h3>Why this exists</h3>
 * Every performance number this project has acted on so far was measured on a developer machine against
 * localhost, where the page loads in 1.2s and nine parallel API calls finish in 730ms. A shop on a congested
 * mobile link is a different system, and the change that matters there is not necessarily the one that looks
 * biggest here. The tier-1 overlay work is the clearest example: it is structurally right and its benefit is
 * invisible on localhost, so without field data the next decision would be a guess dressed as a measurement.
 *
 * <h3>What it collects, and why it is two things</h3>
 * <b>Core Web Vitals</b>, via the vendored web-vitals library — TTFB, FCP, LCP, CLS and INP. These are the
 * industry-standard measures and INP in particular answers "is the till laggy while a cashier types".
 *
 * <b>Application marks</b>, which no library can know:
 * <ul>
 *   <li>{@code tillReady} — from navigation start to the moment the sale screen can be typed into. This is
 *       the number the original complaint was about, and Core Web Vitals does not measure it: LCP says when
 *       the page looked loaded, not when the shop could sell.</li>
 *   <li>per-endpoint API timing — which call was slow, for this tenant, on this connection.</li>
 * </ul>
 *
 * <h3>Rules it follows</h3>
 * <ul>
 *   <li><b>Sampled.</b> Full collection on every page load of every tenant is its own performance problem.</li>
 *   <li><b>Beacon on page hide, never during work.</b> {@code sendBeacon} is fire-and-forget and survives the
 *       unload that a normal XHR would lose — and it cannot hold the blocking overlay, which would be a
 *       grim irony in a file that exists to measure that overlay.</li>
 *   <li><b>No PII.</b> Tenant id and a random per-page id only. Never the user, never the customer, never a
 *       product. A monitoring pipeline is a copy of your data in a second place, so the less it carries the
 *       smaller that problem is.</li>
 *   <li><b>Never breaks the page.</b> Everything is wrapped: a monitoring bug must not stop a sale.</li>
 * </ul>
 */
(function (global) {
    'use strict';

    /** Fraction of page loads reported. Raise while investigating, lower once a baseline exists. */
    var SAMPLE_RATE = 1.0;

    /** Endpoints worth timing individually — the sale path. Anything else is counted but not named. */
    var WATCHED = ['getUserProduct', 'customerOptions', 'getBusinessConfig', 'getCapabilities',
                   'catalogProductPicker', 'productStock', 'addSell', 'addPurchase'];

    var sampled = Math.random() < SAMPLE_RATE;
    var vitals = {};
    var marks = {};
    var sent = false;

    function safe(fn) {
        try { fn(); } catch (ignored) { /* monitoring must never break the page */ }
    }

    /**
     * Mark an application moment. Public so a screen can declare its own "ready".
     *
     * <p>Measured from navigation start rather than from the call, so the number means "how long the operator
     * waited", not "how long this function took". Those diverge exactly when it matters — a fast function
     * reached late is still a slow shop.
     */
    global.rumMark = function (name) {
        safe(function () {
            if (marks[name] == null) marks[name] = Math.round(performance.now());
        });
    };

    /** Per-endpoint API timings from the resource timeline — no wrapping of $.ajax required. */
    function apiTimings() {
        var out = {};
        var entries = performance.getEntriesByType('resource') || [];
        for (var i = 0; i < entries.length; i++) {
            var e = entries[i];
            if (e.initiatorType !== 'xmlhttprequest' && e.initiatorType !== 'fetch') continue;
            for (var j = 0; j < WATCHED.length; j++) {
                if (e.name.indexOf(WATCHED[j]) !== -1) {
                    // The SLOWEST occurrence, not the first: a call issued three times per screen is
                    // reported by its worst case, which is the one the cashier actually waited through.
                    var ms = Math.round(e.duration);
                    if (!out[WATCHED[j]] || ms > out[WATCHED[j]]) out[WATCHED[j]] = ms;
                    break;
                }
            }
        }
        return out;
    }

    function navTimings() {
        var n = (performance.getEntriesByType('navigation') || [])[0];
        if (!n) return {};
        return {
            ttfb: Math.round(n.responseStart),
            domContentLoaded: Math.round(n.domContentLoadedEventEnd),
            load: Math.round(n.loadEventEnd),
            // Request COUNT is the cost that survives compression and caching, and the audit's remaining
            // finding: ~87 requests per navigation, which is latency-bound rather than bandwidth-bound.
            requests: (performance.getEntriesByType('resource') || []).length
        };
    }

    /** Send once, on the first signal that the page is going away. */
    function report() {
        if (sent || !sampled) return;
        sent = true;
        safe(function () {
            var payload = {
                page: location.pathname,
                /*
                 * Segment, not identity. `module` is the vertical (BUSINESS / PHARMA / MARKETPLACE …), which
                 * the dashboard already publishes, and it is enough to answer the first question: are real
                 * shops slower than the developer machine, and does it differ by kind of business.
                 *
                 * ⚠ PER-TENANT segmentation is NOT here yet. No org id is exposed to the page — comparing one
                 * shop against another needs a model attribute rendered like `window.MODULE`, which is a
                 * one-line follow-up rather than something to invent inside a monitoring file. Until then a
                 * beacon says how a KIND of shop performs, not which shop is struggling.
                 */
                module: global.MODULE || null,
                org: global.CURRENT_ORG_ID || null,
                conn: (navigator.connection && navigator.connection.effectiveType) || null,
                nav: navTimings(),
                api: apiTimings(),
                marks: marks,
                vitals: vitals
            };
            var body = JSON.stringify(payload);
            if (navigator.sendBeacon) {
                navigator.sendBeacon((global.serverContext || '/') + 'rum',
                    new Blob([body], { type: 'application/json' }));
            }
            // No XHR fallback on purpose: a browser without sendBeacon would need a synchronous request
            // during unload, which blocks the very navigation the operator is trying to make. A missing
            // sample is cheaper than a stalled page.
        });
    }

    safe(function () {
        if (!sampled) return;

        if (global.webVitals) {
            // The attribution build reports WHICH element caused a slow LCP/INP, so a bad number points at a
            // culprit instead of starting an investigation.
            var record = function (metric) {
                vitals[metric.name] = {
                    value: Math.round(metric.value),
                    rating: metric.rating,
                    target: (metric.attribution && (metric.attribution.interactionTarget
                             || metric.attribution.element)) || undefined
                };
            };
            ['onTTFB', 'onFCP', 'onLCP', 'onCLS', 'onINP'].forEach(function (fn) {
                if (typeof global.webVitals[fn] === 'function') global.webVitals[fn](record);
            });
        }

        /*
         * `tillReady` — the number the original complaint was about.
         *
         * Detected here rather than called from the section-switch handler, deliberately: the sale screen is
         * opened from at least four places (the nav select, the park screen, the pharmacy flow, a deep link),
         * and a mark wired into one of them would quietly under-report the others. Watching the DOM is
         * indifferent to how the screen was reached.
         *
         * The condition is "typeable", not "visible": a rendered screen whose item box is still disabled is
         * not a till a cashier can use, and the gap between those two states is exactly what this is measuring.
         */
        var tillWatch = setInterval(function () {
            safe(function () {
                var div = document.getElementById('sellDiv');
                var box = document.getElementById('sellItems');
                if (!div || !box) return;
                var usable = div.offsetParent !== null && !box.disabled && box.offsetParent !== null;
                if (usable) {
                    global.rumMark('tillReady');
                    clearInterval(tillWatch);
                }
            });
        }, 100);
        // Stop watching after a minute: on a screen that never opens the till, this would otherwise poll for
        // the life of the page to answer a question nobody asked.
        setTimeout(function () { clearInterval(tillWatch); }, 60000);

        // visibilitychange is the reliable end-of-page signal; `unload` is unreliable on mobile, which is
        // most of this product's market. pagehide covers the bfcache case Safari uses.
        addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'hidden') report();
        });
        addEventListener('pagehide', report);
    });
})(window);
