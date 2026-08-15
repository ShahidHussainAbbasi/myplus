/*
 * lazy-export.js — DataTables PDF/Excel export buttons that fetch their library on first click.
 *
 * Design: microservices/docs/perf4-lazy-export-design.md (PERF-4b)
 *
 * WHY THIS EXISTS
 * pdfmake.min.js + vfs_fonts.js are 2,019,663 bytes raw / 902,723 gzipped, and they exist to serve ONE
 * button: the DataTables "PDF" export. jszip.min.js (29,904 gz) serves the "Excel" button. csv, copy and
 * print need no library at all. Loading all of that on every dashboard, for every user, to service a
 * button most sessions never click, was ~70% of the compressed JS payload after PERF-1.
 *
 * ⚠️ WHY THE OBVIOUS IMPLEMENTATION DOES NOT WORK
 * You cannot simply drop the <script> tags and load pdfmake in a click handler. DataTables Buttons gates
 * every built-in export on an availability probe (buttons.html5.min.js):
 *
 *     available: function(){ return g.FileReader !== w && (t || g.pdfMake) }      // pdfHtml5
 *     available: function(){ return g.FileReader !== w && (z || g.JSZip) !== w }  // excelHtml5
 *
 * and it runs that probe WHEN THE TABLE INITIALISES. With pdfmake absent, DataTables does not draw a
 * disabled button — it omits the button entirely. There would be nothing left to click.
 *
 * So each export is declared here as a CUSTOM button (custom buttons have no availability gate, so they
 * always render), which on click loads the library and then delegates to the built-in action with the
 * caller's own config. The UI is unchanged; only the moment of payment moves.
 *
 * Load order matters and is a CORRECTNESS requirement, not a preference: vfs_fonts.js assigns into
 * pdfMake.vfs, so it must land AFTER pdfmake.min.js. Reversed, the font table is lost and every generated
 * PDF renders with no glyphs — a failure that looks like a styling bug, not a loading bug.
 *
 * Depends on jQuery + DataTables Buttons (loaded by fragments/header.html before this file).
 */
(function (global, $) {
    'use strict';

    var PDFMAKE_URLS = ['/jQExp/pdfmake.min.js', '/jQExp/vfs_fonts.js'];
    var JSZIP_URLS   = ['/jQExp/jszip.min.js'];

    /* Memoised per URL. Keyed on the URL so two buttons wanting pdfmake share one download. */
    var scriptCache = {};

    /**
     * Load a script once, ever. Returns a Promise that resolves when it has executed.
     *
     * The PROMISE is cached, not a "loaded" boolean. A boolean would only flip after the download
     * finished, so a second click during the ~900KB fetch would start a second identical download. Caching
     * the in-flight promise makes concurrent clicks share one request.
     */
    function loadScriptOnce(url) {
        if (scriptCache[url]) return scriptCache[url];

        scriptCache[url] = new Promise(function (resolve, reject) {
            var el = document.createElement('script');
            el.src = (global.serverContext ? global.serverContext.replace(/\/$/, '') : '') + url;
            el.async = false;   // preserve execution order between the two pdfmake files
            el.onload = function () { resolve(url); };
            el.onerror = function () {
                // Drop the rejected promise so a later click can retry (the user may have reconnected).
                delete scriptCache[url];
                reject(new Error('Failed to load ' + url));
            };
            document.head.appendChild(el);
        });
        return scriptCache[url];
    }

    /** Load a list of scripts strictly in sequence (order is load-bearing for pdfmake + vfs_fonts). */
    function loadInOrder(urls) {
        return urls.reduce(function (chain, url) {
            return chain.then(function () { return loadScriptOnce(url); });
        }, Promise.resolve());
    }

    function ensurePdfMake() {
        if (global.pdfMake && global.pdfMake.vfs) return Promise.resolve();
        return loadInOrder(PDFMAKE_URLS);
    }

    function ensureJsZip() {
        if (global.JSZip) return Promise.resolve();
        return loadInOrder(JSZIP_URLS);
    }

    /**
     * Build a custom DataTables button that loads `ensureFn`'s library, then runs the built-in
     * `builtinName` action with the caller's config.
     *
     * @param builtinName  key in $.fn.dataTable.ext.buttons ('pdfHtml5' | 'excelHtml5')
     * @param ensureFn     returns a Promise resolving once the library is present
     * @param defaultText  button label when the caller does not supply one
     * @param config       the caller's own options (orientation, pageSize, title, footer, …)
     */
    function lazyButton(builtinName, ensureFn, defaultText, config) {
        var cfg = $.extend({}, config || {});
        var label = cfg.text || defaultText;

        return $.extend({}, cfg, {
            text: label,
            action: function (e, dt, node, btnConfig) {
                var self = this;
                var $node = $(node);

                // On a slow link this is a multi-second wait AFTER a click. Saying nothing reads as a
                // broken button and invites repeat clicking.
                var busyLabel = (typeof global.t === 'function' ? global.t('ui.js.preparing') : null);
                if (!busyLabel || busyLabel === 'ui.js.preparing') busyLabel = 'Preparing…';
                dt.button(node).text(busyLabel);
                $node.prop('disabled', true).addClass('disabled');

                var restore = function () {
                    dt.button(node).text(label);
                    $node.prop('disabled', false).removeClass('disabled');
                };

                ensureFn()
                    .then(function () {
                        var builtin = $.fn.dataTable.ext.buttons[builtinName];
                        if (!builtin || typeof builtin.action !== 'function') {
                            throw new Error('DataTables button "' + builtinName + '" is unavailable');
                        }
                        restore();
                        // Delegate with the caller's ORIGINAL config merged over the built-in defaults, so
                        // orientation / pageSize / title / footer behave exactly as before this change.
                        var merged = $.extend({}, builtin, cfg, btnConfig, { text: label });
                        return builtin.action.call(self, e, dt, node, merged);
                    })
                    .catch(function (err) {
                        restore();
                        var msg = 'The export tools could not be loaded. Check your connection and try again.';
                        if (typeof global.uiAlert === 'function') {
                            global.uiAlert({ title: 'Export unavailable', message: msg });
                        } else {
                            console.error('[lazy-export]', err);
                        }
                    });
            }
        });
    }

    global.lazyPdfButton = function (config) {
        return lazyButton('pdfHtml5', ensurePdfMake, 'PDF', config);
    };

    global.lazyExcelButton = function (config) {
        return lazyButton('excelHtml5', ensureJsZip, 'Excel', config);
    };

    /* Exposed for the Cypress gate and for any future caller that needs the library directly. */
    global.LazyExport = {
        ensurePdfMake: ensurePdfMake,
        ensureJsZip: ensureJsZip,
        loadScriptOnce: loadScriptOnce
    };
})(window, jQuery);
