/*
 * Vertical profile (slice 33 → 36) — ONE shared commerce dashboard (businessDashboard.html), white-labelled
 * per logged-in user type. The user's type sets window.MODULE (BUSINESS=POS, PHARMA=Pharmacy, ECOMMERCE=Store)
 * and this script applies that vertical's PROFILE without forking the template or touching the engine
 * (main.js/business.js):
 *
 *   - labels   : relabel a curated set of headings / nav options / subnav buttons (exact trimmed match — never
 *                a blind text scan), so terminology fits the vertical.
 *   - features : show/hide vertical-specific bits via [data-feature] / [data-vertical-only] (default POS shows
 *                everything that exists today; pharma/ecommerce features light up as their backends land).
 *   - theme    : a body class hook (v-pos / v-pharma / v-store) for per-vertical accenting (CSS-only, optional).
 *   - title/brand: document title + any [data-brand] element.
 *
 * To add/adjust a vertical: edit its entry in VERTICALS. BUSINESS is the baseline (no relabeling).
 */
(function () {
    var VERTICALS = {
        BUSINESS: {
            title: null,                      // keep the template default
            brand: 'MyPlus POS',
            receiptTitle: 'SALES RECEIPT',
            themeClass: 'v-pos',
            labels: {},                       // baseline — POS wording is the template default
            features: {}                      // POS shows all of today's trade features
        },
        // Internal id PHARMA (matches pharma-service); user-facing display is "Pharmacy".
        PHARMA: {
            title: 'Pharmacy Dashboard — MyPlus',
            brand: 'MyPlus Pharmacy',
            receiptTitle: 'DISPENSE RECEIPT',
            themeClass: 'v-pharma',
            labels: {
                'Business Overview': 'Pharmacy Overview',
                'Company Registration': 'Distributor Registration',
                'Vendor Registration': 'Supplier Registration',
                'Item Registration': 'Medicine Registration',
                'Customer Registration': 'Patient Registration',
                'Company': 'Distributor',
                'Vender/Supplier': 'Supplier',
                'Vender / Supplier': 'Supplier',
                'Customer': 'Patient',
                'Item': 'Medicine',
                // Slice 74: the Product master is the creation path — relabel it (and its heading) per vertical.
                'Product': 'Medicine',
                'Product (Catalog Master)': 'Medicine (Catalog Master)',
                'Register': 'Register',
                'Purchase': 'Purchase',
                'New Purchase': 'New Purchase',
                'Sale': 'Dispense',
                'New Sale': 'New Dispense',
                'Sale Detail Report': 'Dispense Report'
            },
            features: { rx: true, batchExpiry: true }
        },
        MARKETPLACE: {
            title: 'Store Dashboard — MyPlus',
            brand: 'MyPlus Store',
            receiptTitle: 'ORDER RECEIPT',
            themeClass: 'v-store',
            labels: {
                'Business Overview': 'Store Overview',
                'Company Registration': 'Brand Registration',
                'Vendor Registration': 'Supplier Registration',
                'Item Registration': 'Product Registration',
                'Customer Registration': 'Buyer Registration',
                'Company': 'Brand',
                'Vender/Supplier': 'Supplier',
                'Vender / Supplier': 'Supplier',
                'Customer': 'Buyer',
                'Item': 'Product',
                'Sale': 'Order',
                'New Sale': 'New Order',
                'Sale Detail Report': 'Order Report'
            },
            features: { orders: true, storefront: true }
        }
    };

    // Relabel by rewriting matching TEXT NODES only, so we never wipe out child icon <span>s (the nav buttons
    // and menu items hold a glyphicon span + the label text + sometimes a caret span).
    function relabel(el, dict) {
        if (!el) return;
        var changed = false;
        el.childNodes.forEach(function (n) {
            if (n.nodeType === 3) {                 // TEXT_NODE
                var key = n.nodeValue.trim();
                if (key && dict[key] && dict[key] !== key) {
                    n.nodeValue = n.nodeValue.replace(key, dict[key]);
                    changed = true;
                }
            }
        });
        if (!changed && el.children.length === 0) {  // plain element (option / heading)
            var raw = el.textContent, k = raw.trim();
            if (dict[k] && dict[k] !== k) el.textContent = raw.replace(k, dict[k]);
        }
    }

    // Hide elements that belong only to OTHER verticals, or to a feature this vertical doesn't enable.
    function applyFeatures(mod, features) {
        // [data-vertical-only="PHARMA,ECOMMERCE"] — visible only for the listed modules.
        document.querySelectorAll('[data-vertical-only]').forEach(function (el) {
            var allowed = (el.getAttribute('data-vertical-only') || '')
                .split(',').map(function (s) { return s.trim().toUpperCase(); });
            el.style.display = allowed.indexOf(mod) !== -1 ? '' : 'none';
        });
        // [data-feature="rx"] — visible only when the active profile enables that feature.
        document.querySelectorAll('[data-feature]').forEach(function (el) {
            var f = el.getAttribute('data-feature');
            el.style.display = features && features[f] ? '' : 'none';
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var mod = (window.MODULE || 'BUSINESS').toUpperCase();
        var profile = VERTICALS[mod] || VERTICALS.BUSINESS;
        // Expose for other scripts (e.g. receipt.js) that need the active vertical's brand / wording.
        window.VERTICAL = mod;
        window.VERTICAL_PROFILE = profile;

        if (profile.title) document.title = profile.title;
        if (profile.themeClass) document.body.classList.add(profile.themeClass);
        if (profile.brand) {
            document.querySelectorAll('[data-brand]').forEach(function (el) { el.textContent = profile.brand; });
        }

        var dict = profile.labels || {};
        if (Object.keys(dict).length) {
            // Headings, the off-screen nav <option>s, the visible subnav button labels and menu items.
            var selector = '.dash-page-title, #registrationType option, #purchaseType option, '
                + '#sellType option, .snav-btn span, .snav-menu a, [data-term]';
            document.querySelectorAll(selector).forEach(function (el) { relabel(el, dict); });
        }

        applyFeatures(mod, profile.features || {});
    });
})();
