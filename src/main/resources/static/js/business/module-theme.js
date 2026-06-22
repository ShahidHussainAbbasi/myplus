/*
 * Module theming (slice 33) — one shared trade dashboard, white-labelled per vertical.
 *
 * The business/trade dashboard is reused as-is for other commerce verticals (pharmacy first). This script
 * relabels a curated set of UI strings + the page title/brand based on window.MODULE, so we adapt the
 * terminology without forking the template or touching the engine (main.js/business.js).
 *
 * To add a vertical: add an entry to TERMS. To adjust wording: edit its `labels` map. Matching is exact
 * (trimmed) against known section headings, nav options and subnav buttons — never a blind text scan.
 */
(function () {
    var TERMS = {
        PHARMACY: {
            title: 'Pharmacy Dashboard — MyPlus',
            // section headings (.dash-page-title), nav <option>s and .snav-btn labels — keyed by their
            // current (business) text, valued by the pharmacy wording.
            labels: {
                'Business Overview': 'Pharmacy Overview',
                'Company Registration': 'Distributor Registration',
                'Vendor Registration': 'Supplier Registration',
                'Item Registration': 'Medicine Registration',
                'Customer Registration': 'Patient Registration',
                'Company': 'Distributor',
                'Vender/Supplier': 'Supplier',
                'Customer': 'Patient',
                'Item': 'Medicine',
                'Register': 'Register',
                'Purchase': 'Purchase',
                'New Purchase': 'New Purchase',
                'Sale': 'Dispense',
                'New Sale': 'New Dispense',
                'Sale Detail Report': 'Dispense Report'
            }
        }
    };

    function relabel(el, dict) {
        if (!el) return;
        var raw = el.textContent;
        var key = raw.trim();
        if (dict[key] && dict[key] !== key) {
            el.textContent = raw.replace(key, dict[key]); // preserve surrounding spaces/icons
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        var mod = (window.MODULE || 'BUSINESS').toUpperCase();
        var theme = TERMS[mod];
        if (!theme) return; // BUSINESS (or unknown) → leave the default wording untouched

        if (theme.title) document.title = theme.title;

        var dict = theme.labels || {};
        // Section headings, the off-screen nav <option>s, and the visible subnav buttons/items.
        var selector = '.dash-page-title, #registrationType option, #purchaseType option, '
            + '#sellType option, .snav-btn, .snav-item, [data-term]';
        document.querySelectorAll(selector).forEach(function (el) {
            relabel(el, dict);
        });
    });
})();
