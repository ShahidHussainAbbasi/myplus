/*
 * Catalog Product master (slice 42, M1; UI-parity refactor) — register/list/edit Products in catalog-service
 * (the single product master shared by POS, pharmacy, e-commerce). The list now renders through the SHARED
 * loadDataTable() DataTable path (same as Customer): sort/search/paging/export, a hidden id + checkbox column,
 * row-click edit, and a Delete that DEACTIVATES (products referenced by sales/inventory stay intact, just drop
 * off the list). Per-row Add-stock (addstkbtn_<id>) is preserved.
 *
 * PROXIES: /getProductPage (the grid AND the "already registered" panel), /productStockLevels?ids=,
 * /productCount, /manufacturers, /productNameCheck, /productSkuCheck, /addProduct, /updateProduct,
 * /deactivateProduct, /addProductStock, /productStock, /getCatalogProduct.
 *
 * ⭐ PS-1 — THIS SCREEN LOADS ONLY WHAT IS ON IT. It used to also fetch the whole catalogue
 * (/getUserProduct, 384 KB) and every product's stock levels (/productStockLevels, 69.5 KB) on every open:
 * 474 KB to render a 50-row grid that needs 19.8 KB. Worse, the catalogue fetch was capped at 1,000 rows and
 * the cap was SILENT — on a 1,632-product tenant, 632 products were missing from the duplicate-SKU check,
 * the registered panel and the manufacturer dropdown. Every one of those now asks the server, which sees
 * every row.
 *
 * The form also shows what is ALREADY REGISTERED while a new product is being entered — the list has always
 * been on the screen (#tableProduct) but the form opens in a fixed full-viewport overlay that covers it. The
 * panel narrows as Name / SKU / Barcode are typed and offers "edit this one" on each row, and leaving the Name
 * field asks the server whether that name is already taken ANYWHERE IN THE ORG, not just among this user's own
 * products (see CatalogController.productNameCheck → catalog /products/name-check).
 */
(function (global) {
    'use strict';

    // Numeric coercion uses the shared s2n() from main.js (was a duplicate local s2n()).

    // ── What the form knows about the rest of the catalogue (PS-1) ────────────────────
    //
    // This was ONE fetch of the whole catalogue (/getUserProduct?includeInactive=true) held in memory and
    // queried on the client for three things: the duplicate-SKU check, the "already registered" panel, and
    // the manufacturer dropdown.
    //
    // ⚠ THAT FETCH WAS CAPPED AT 1,000 ROWS AND THE CAP WAS SILENT. Measured, not theorised: on a
    // 1,632-product tenant the five oldest products by id were absent from the index while the grid paged
    // to them perfectly well — 632 products (39%) missing from all three consumers. So a taken SKU was
    // reported free, a registered product was reported unregistered, and manufacturers used only on older
    // products vanished from the dropdown.
    //
    // renderExisting() below already argued that an empty list must never be shown as "nothing is
    // registered", because it is the opposite of the truth and the one reassurance the operator must not be
    // given. Truncation produced exactly that reassurance, and the guard could not see it: it distinguished
    // a FAILED fetch from a successful one, and this fetch succeeded.
    //
    // Each consumer now asks the server, which sees every row. It also drops 384 KB from every screen open.

    // Rows painted into the panel. Now also the page SIZE asked of the server — a type-ahead that paints
    // hundreds of rows per keystroke is slower than the round-trip it replaced.
    var EXISTING_MAX_ROWS = 40;

    function normSku(s) { return (s == null ? '' : String(s)).trim().toLowerCase(); }

    /** Distinct manufacturers for the dropdown — GET /manufacturers. */
    var manufacturerList = [];

    /** Products in this tenant, for the "N registered" badge — GET /productCount. Null until known. */
    var productTotal = null;

    /** The panel's rows and state. 'loaded' means THE SERVER ANSWERED — never merely "we tried". */
    var existingRows = [];
    var existingTotal = 0;        // matches the server found, which may exceed what we painted
    var panelState = 'empty';     // 'empty' | 'loaded' | 'failed'

    /**
     * The last AUTHORITATIVE answer about ONE SKU, from GET /productSkuCheck.
     *
     * ⚠ Deliberately not a cache of many SKUs — a client-side map of the catalogue is precisely what was
     * just removed. It holds only the value the server was last asked about, so saveProduct() can block a
     * duplicate it has already been told about without becoming async.
     *
     * Anything not covered here simply goes to the server on save, which refuses a duplicate SKU
     * authoritatively and always did. <b>The client check is an optimisation, never the enforcement</b> —
     * which is why losing the old index costs correctness nothing and gains it a great deal.
     */
    var skuVerdict = { sku: null, ownerId: null };

    function clearSkuVerdict() { skuVerdict = { sku: null, ownerId: null }; }

    /**
     * Ask the server whether a SKU is taken, and remember the answer.
     *
     * @param sku       what to check — blank is never a duplicate, and costs no request
     * @param currentId the product being edited; keeping your own SKU is not a duplicate
     * @param done      called with true when the SKU is taken by a DIFFERENT product
     */
    function checkSkuOnServer(sku, currentId, done) {
        var k = normSku(sku);
        if (!k) { clearSkuVerdict(); if (done) done(false); return; }
        var params = { sku: String(sku).trim() };
        if (currentId) params.excludeId = currentId;
        $.get(serverContext + 'productSkuCheck', params)
            .done(function (resp) {
                var taken = !!(resp && resp.success && resp.exists);
                skuVerdict = { sku: k, ownerId: taken ? String(resp.id) : null };
                if (done) done(taken);
            })
            .fail(function () {
                // Unknown is NOT "free": leave no verdict, so save falls through to the server rather than
                // the client inventing an all-clear it was never given.
                clearSkuVerdict();
                if (done) done(false);
            });
    }

    /**
     * True only when the server has ALREADY told us this exact SKU belongs to a different product.
     *
     * Unknown returns false on purpose — the save then proceeds and the server refuses it with a message.
     * The old version answered from a 1,000-row index and returned false for 632 products' SKUs while
     * sounding just as certain.
     */
    function isDuplicateSku(sku, currentId) {
        var k = normSku(sku);
        if (!k) return false;                       // SKU is optional — blank never blocks on the client
        if (skuVerdict.sku !== k) return false;     // not the value we have an answer for
        return skuVerdict.ownerId != null && skuVerdict.ownerId !== String(currentId || '');
    }

    /**
     * Refresh everything the form shows about the rest of the catalogue: the badge, the manufacturer
     * dropdown, and the panel. Three small reads in place of one large one, none of them blocking.
     */
    function refreshProductPanel() {
        $.get(serverContext + 'productCount', function (resp) {
            if (resp && resp.success) productTotal = Number(resp.count);
            renderExisting();
        }, 'json');

        $.get(serverContext + 'manufacturers', function (resp) {
            manufacturerList = (resp && resp.success && resp.manufacturers) ? resp.manufacturers : [];
            // Keep whatever is currently selected — it matters while a product is open for editing.
            loadManufacturers($('#prodManufacturer').val());
        }, 'json');

        refreshExistingRows();
    }

    /**
     * Fetch the panel's rows from the server, debounced.
     *
     * ⚠ A SEQUENCE GUARD, not just a debounce. Keystrokes produce overlapping requests and they do not
     * come back in order; without the guard a slower earlier response repaints over a newer one and the
     * panel shows results for a prefix of what is in the box. Same hazard formEpoch covers for name-check.
     */
    var existingTimer = null;
    var existingSeq = 0;
    function refreshExistingRows() {
        if (existingTimer) clearTimeout(existingTimer);
        existingTimer = setTimeout(function () {
            var terms = existingTerms();
            var seq = ++existingSeq;
            var params = { page: 0, size: EXISTING_MAX_ROWS, sort: 'id,desc', includeInactive: true };
            /*
             * ONE term, where the old client filter matched ANY of name/sku/barcode.
             *
             * The server's q is a single LIKE across name, sku, barcode and manufacturer, so the first
             * non-empty field still searches all four columns — what narrows is that a name AND a sku typed
             * together now filter by the name rather than by either. A deliberate trade: one request per
             * keystroke instead of three, against results that are complete rather than drawn from the
             * newest thousand products.
             */
            if (terms.length) params.q = terms[0];
            $.get(serverContext + 'getProductPage', params)
                .done(function (resp) {
                    if (seq !== existingSeq) return;          // a later keystroke already asked
                    existingRows = (resp && resp.collection) ? resp.collection : [];
                    existingTotal = (resp && resp.page && resp.page.totalElements != null)
                        ? Number(resp.page.totalElements) : existingRows.length;
                    panelState = 'loaded';
                    renderExisting();
                })
                .fail(function () {
                    if (seq !== existingSeq) return;
                    existingRows = [];
                    existingTotal = 0;
                    panelState = 'failed';                    // NOT an empty list — see renderExisting
                    renderExisting();
                });
        }, 200);
    }

    // ── "Already registered" panel ──────────────────────────────────────────────
    // The registered products, listed inside the form itself. The list has always existed (#tableProduct) but
    // the form opens in a fixed full-viewport .crud-overlay, so it sat behind the scrim exactly while the
    // operator needed it. Typing in Name / SKU / Barcode narrows the panel, which makes it a type-ahead over
    // what already exists rather than a static dump.

    /** The form's identity generation. Bumped whenever the form is reset or re-loaded, so an in-flight
     *  name-check response can tell that it now belongs to a different product and drop itself. */
    var formEpoch = 0;

    /** Id of the namesake the server-side check reported, or null. Survives repaints — see applyFlag(). */
    var flaggedId = null;

    function existingTerms() {
        return [$('#prodName').val(), $('#prodSku').val(), $('#prodBarcode').val()]
            .map(function (v) { return (v == null ? '' : String(v)).trim().toLowerCase(); })
            .filter(function (v) { return v.length > 0; });
    }

    /**
     * The rows to paint. The SERVER has already filtered by what is typed (see refreshExistingRows), so the
     * only thing left to exclude is the product being edited — it must never be offered against itself.
     */
    function existingMatches() {
        var editingId = String($('#productId').val() || '');
        return existingRows.filter(function (p) {
            return !editingId || String(p.id) !== editingId;
        });
    }

    function existingRowHtml(p) {
        var inactive = p.isActive === false;
        var price = (p.sellingPrice == null || p.sellingPrice === '')
            ? '' : (typeof srMoney === 'function' ? srMoney(p.sellingPrice) : String(p.sellingPrice));
        return '<div class="crud-existing-row' + (inactive ? ' is-inactive' : '') + '" data-id="' + escHtml(String(p.id)) + '">'
            + '<span class="ce-name">' + escHtml(p.name || '') + '</span>'
            + (p.sku ? '<span class="ce-tag">' + escHtml(p.sku) + '</span>' : '')
            + (p.categoryName ? '<span class="ce-cat">' + escHtml(p.categoryName) + '</span>' : '')
            + (price ? '<span class="ce-price">' + escHtml(price) + '</span>' : '')
            + (inactive ? '<span class="ce-badge">' + escHtml(t('ui.js.inactive')) + '</span>' : '')
            + '<button type="button" class="ce-edit js-edit-existing">' + escHtml(t('ui.js.editThisOne')) + '</button>'
            + '</div>';
    }

    function renderExisting() {
        var $list = $('#prodExistingList'), $msg = $('#prodExistingMsg'), $count = $('#prodExistingCount');
        if (!$list.length) return;                       // panel not on this page

        if (panelState === 'failed') {
            $list.empty();
            $count.text('');
            $msg.text(t('ui.js.couldNotLoadTheRegisteredProducts')).removeClass('text-muted').addClass('text-danger').show();
            return;
        }
        // Not fetched yet (the form is painted before the response lands). Say NOTHING — an empty list here
        // would read as "nothing is registered", which is the same false all-clear the failure branch avoids.
        if (panelState !== 'loaded') {
            $list.empty();
            $count.text('');
            $msg.hide();
            return;
        }
        $msg.removeClass('text-danger').addClass('text-muted');

        var matches = existingMatches();

        /*
         * ⭐ THE BADGE COUNTS THE TENANT, NOT THE PANEL.
         *
         * It used to render the length of the client's catalogue array, which the 1,000-row cap made WRONG
         * ON SCREEN: a tenant with 1,632 products was shown "1000 registered". It cannot come from the
         * panel's own total either — that is FILTERED by whatever is typed, so the number would change
         * meaning with every keystroke. /productCount answers the question the badge actually asks.
         *
         * Blank until known, rather than 0: "0 registered" is a claim, and an unanswered request is not.
         */
        $count.text(productTotal == null ? '' : t('ui.js.nRegistered', productTotal));

        if (productTotal === 0) {
            $list.empty();
            $msg.text(t('ui.js.noProductsRegisteredYet')).show();
            return;
        }
        if (!matches.length) {
            $list.empty();
            $msg.text(t('ui.js.noRegisteredProductMatches')).show();
            return;
        }

        $list.html(matches.map(existingRowHtml).join(''));
        applyFlag();                                     // repaint dropped the highlight — put it back
        if (existingTotal > matches.length) {
            $msg.text(t('ui.js.showingFirstNKeepTyping', matches.length, existingTotal)).show();
        } else {
            $msg.hide();
        }
    }

    /**
     * Highlight the namesake the server reported, so the warning points AT a row instead of describing one.
     * The id is REMEMBERED, not just painted: typing in SKU or Barcode repaints the list, and a highlight that
     * vanished there would leave the Name field flagged with nothing to point at.
     */
    function markExistingRow(id) {
        flaggedId = (id == null) ? null : String(id);
        applyFlag();
    }

    /** Paint the remembered flag onto the current rows. Called by markExistingRow and after every repaint. */
    function applyFlag() {
        var $rows = $('#prodExistingList .crud-existing-row').removeClass('is-flagged');
        if (flaggedId == null) return;
        var $row = $rows.filter('[data-id="' + flaggedId + '"]').addClass('is-flagged');
        if ($row.length && $row[0].scrollIntoView) $row[0].scrollIntoView({ block: 'nearest' });
    }

    global.showProducts = function () {
        $('.formDiv').hide();
        $('#ProductDiv').show();
        resetProductForm();
        loadCategories();   // populate the Category dropdown
        // Render #tableProduct through the shared DataTable path (like #tableCustomer).
        tableV = 'Product'; getAll = 'Product'; buttonV = 'Product'; deleteV = 'Product';
        loadDataTable();
        refreshProductPanel();   // keeps the duplicate-SKU check and the "already registered" panel current
        // Per-row "Edit" button is injected by the global DataTables drawCallback (main.js) — no per-table wiring.

        // Row interactions (mirror the generic modal screens):
        //   • checkbox → bulk-select (update the action bar)
        //   • per-row "Edit" button (injected by ensureRowEditButtons) → open the edit modal
        $('#tableProduct').off('change', "input[type='checkbox']")
            .on('change', "input[type='checkbox']", function () {
                if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
            });
        $('#tableProduct').off('click', '.js-edit-row').on('click', '.js-edit-row', function (e) {
            e.stopPropagation();
            var rowData = datatable.row($(this).closest('tr')).data();
            if (!rowData) return;
            var id = $(rowData[0]).text();   // rowData[0] = "<div id=productId>123</div>"
            if (id) editProduct(id);
        });
    };

    // Toolbar "+ New Product" → open the form modal fresh.
    /**
     * PUR-INLINE — who asked for this product, and what to do with it once it exists.
     *
     * Null for the ordinary Products-screen case, which behaves exactly as before. Set when another form
     * opened this one (the purchase form's "+ New product"), so the new product can be handed straight back
     * to the picker the operator was standing in.
     *
     * A CALLBACK rather than a string context, deliberately: this file must not learn the purchase form's
     * field ids. The same hook serves the sale screen later with nothing changed here.
     */
    var productCreatedCb = null;

    global.newProduct = function (onCreated) {
        // Set on EVERY open, so a callback left behind by a form that was closed with Esc (which runs no
        // reset) can never be inherited by the next product somebody registers from the Products screen.
        productCreatedCb = (typeof onCreated === 'function') ? onCreated : null;
        resetProductForm();
        loadCategories();
        loadTaxCodes('');        // multi-rate tax: fresh dropdown (defaults to "Custom rate…")
        loadManufacturers('');   // draw from the CURRENT index immediately; refresh below repaints it
        refreshProductPanel();   // re-read the catalogue each time the form opens, then paint the panel
        $('#ProductModalTitle').text('New Product');
        openModal('ProductModal');
    };

    /**
     * U1 — show the loose fields only when the unit actually holds more than one piece.
     *
     * Asking what one tablet is called, on a product nobody sells by the tablet, is four boxes of noise on
     * the most-used screen in the app. A shop that never breaks a pack sees the form it has always seen.
     */
    /*
     * U7 — the shop's own sticker codes for this product.
     *
     * A sticker needs a product to point at, so the panel appears only on an EXISTING product. On a new one
     * the fields would have nowhere to save to, and an input that silently discards what you type is worse
     * than one that is not there.
     */
    global.loadProductStickers = function (productId) {
        var $wrap = $('#prodStickerWrap');
        if (!productId) { $wrap.hide(); $('#prodStickerList').empty(); return; }
        $wrap.show();
        $.get(serverContext + 'productBarcodes', { productId: productId })
            .done(function (resp) {
                var rows = (typeof apiList === 'function') ? apiList(resp)
                    : (resp && resp.data) || resp || [];
                if (!rows.length) {
                    $('#prodStickerList').html('<div class="help-block" style="margin:0">'
                        + t('ui.js.noStickersYet') + '</div>');
                    return;
                }
                var html = '<table class="table table-condensed" style="margin:0"><tbody>';
                rows.forEach(function (b) {
                    // escHtml: the code is operator-authored text reaching the DOM.
                    html += '<tr><td>' + escHtml(String(b.barcode || '')) + '</td>'
                        + '<td>' + escHtml(String(b.quantity || 1)) + ' '
                        + escHtml(String(b.soldUnit || '')) + '</td>'
                        + '<td style="text-align:right"><button type="button" class="btn btn-xs btn-warning" '
                        + 'onclick="removeProductSticker(' + Number(b.id) + ')">&times;</button></td></tr>';
                });
                $('#prodStickerList').html(html + '</tbody></table>');
            })
            .fail(function () { $('#prodStickerList').empty(); });
    };

    global.addProductSticker = function () {
        var productId = $('#productId').val();
        if (!productId) return;
        var code = ($('#prodStickerCode').val() || '').trim();
        if (!code) { showFormError(t('ui.js.enterStickerCode')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addProductBarcode', contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify({
                productId: Number(productId), barcode: code,
                soldUnit: $('#prodStickerUnit').val(),
                quantity: Number($('#prodStickerQty').val()) || 1
            }),
            success: function (resp) {
                // The refusals that keep a sticker from shadowing a real barcode come back as a MESSAGE from
                // catalog-service. Showing our own sentence instead would hide the one that explains why.
                if (typeof apiOk === 'function' && !apiOk(resp)) {
                    showFormError(apiMessage(resp, t('ui.js.couldNotAddSticker')));
                    return;
                }
                $('#prodStickerCode').val('');
                $('#prodStickerQty').val(1);
                loadProductStickers(productId);
            },
            error: function (xhr) {
                showFormError(typeof apiFailMessage === 'function'
                    ? apiFailMessage(xhr, t('ui.js.couldNotAddSticker'))
                    : t('ui.js.couldNotAddSticker'));
            }
        });
    };

    global.removeProductSticker = function (id) {
        var productId = $('#productId').val();
        $.ajax({
            type: 'POST', url: serverContext + 'removeProductBarcode', contentType: 'application/json',
            dataType: 'json', data: JSON.stringify({ id: id }),
            success: function () { loadProductStickers(productId); }
        });
    };

    global.prodPackSizeChanged = function () {
        var n = Number($('#prodPackSize').val());
        var divisible = n > 1;
        $('#prodLooseWrap').toggle(divisible);
        if (!divisible) {
            // Cleared with the pack size, not left behind: a product that is no longer divisible must not
            // keep a permission to split it, and "may be sold by the piece" on a pack of one is nonsense
            // the server would then have to refuse.
            $('#prodAllowLoose').prop('checked', false);
            $('#prodDefaultSellUnit').val('PACK');
        }
    };

    /**
     * C6 — save the per-product tracking policy (serial/IMEI, batch).
     *
     * <h3>Only sends a flag the tenant is actually allowed to set</h3>
     * A capability the tenant does not have hides its checkbox (`[data-capability]` → `.cap-off`), and an
     * unchecked hidden box would otherwise be sent as an explicit `false` — quietly clearing a policy the
     * operator never saw, on every ordinary product edit. Omitting it means "leave alone", which is what the
     * server reads a missing parameter as.
     *
     * <h3>Separate call, deliberately</h3>
     * Same shape as the clinical flags: this is POLICY, and it is ADMIN- and capability-gated server-side.
     * Folding it into the general product save would make every product edit refusable for a reason unrelated
     * to what the operator changed.
     *
     * <p>Failures surface rather than being swallowed: the product HAS been saved by this point, so silence
     * would leave the operator believing a policy was applied when it was not.
     */
    function saveProductTracking(productId) {
        if (!productId) return;
        var body = { id: productId };
        // hasCapability() is defined by /js/common/capabilities.js and returns TRUE when the map is unknown,
        // so a page that could not load capabilities behaves as it did before C6 rather than silently
        // dropping the flags.
        if (global.hasCapability('serialTracking')) {
            body.requiresSerial = $('#prodRequiresSerial').is(':checked');
        }
        if (global.hasCapability('batchTracking')) {
            body.tracksBatch = $('#prodTracksBatch').is(':checked');
        }
        // Nothing this tenant may set — no call worth making.
        if (body.requiresSerial === undefined && body.tracksBatch === undefined) return;

        $.ajax({
            type: 'POST', url: serverContext + 'setProductTracking', dataType: 'json', data: body,
            success: function (resp) {
                // A refusal arrives as 200 with success:false — the app's envelope carries the reason rather
                // than flattening it into a status code, so a status-only check would miss it entirely.
                if (resp && resp.success === false && global.showFormError) {
                    global.showFormError(resp.message || 'The tracking policy could not be saved.');
                }
            },
            error: function () {
                if (global.showFormError) {
                    global.showFormError('The tracking policy could not be saved.');
                }
            }
        });
    }

    /*
     * DUP-1 — the product form's idempotency key, read through FormKeys (common/submit-once.js).
     *
     * WRAPPED RATHER THAN CALLED DIRECTLY, and the reason is specific to this project: static assets have been
     * served stale from target/classes before, under a correct-looking hash. If submit-once.js ever fails to
     * load, a direct `global.FormKeys.get(...)` would throw INSIDE saveProduct and the product form would stop
     * saving altogether — a guard against duplicates taking out the very thing it guards. Degrading to "no key"
     * just puts the form back to how it behaved before this slice.
     */
    function productKey() { return global.FormKeys ? global.FormKeys.get('product') : null; }
    function retireProductKey() { if (global.FormKeys) global.FormKeys.retire('product'); }

    function resetProductForm() {
        var f = document.getElementById('Product');
        if (f) f.reset();
        $('#productId').val('');
        // DUP-1 — a cleared form is a new intent. Belt-and-braces beside the retire-on-success in saveProduct:
        // a key that somehow outlived its save must never reach the NEXT product, because the server would
        // correctly replay the old one and the new product would silently not exist.
        retireProductKey();
        // PUR-INLINE — and a cleared form is nobody's errand any more. Cancel runs this, so a purchase that
        // was abandoned cannot have its callback fire on an unrelated product saved later.
        productCreatedCb = null;
        // form.reset() does not fire change, so the loose row would stay open from the last product edited.
        prodPackSizeChanged();
        $('#prodCategory').val('');
        $('#prodCategoryNew').val('');
        // form.reset() restores a <select> to its FIRST option, not to blank, so clear it explicitly —
        // otherwise a new product would silently inherit whichever manufacturer sorts first.
        $('#prodManufacturer').val('');
        $('#prodManufacturerNew').val('');
        refreshPicker('#prodManufacturer');   // else the button keeps showing the cleared brand
        $('#prodSku').removeClass('alert-danger');
        $('#prodName').removeClass('alert-danger');
        formEpoch++;             // any name-check still in flight now belongs to a form that no longer exists
        clearSkuVerdict();       // … and so does any SKU verdict: it was about the product just closed
        markExistingRow(null);
        /*
         * RE-ASK, don't just repaint. With the rows held in memory, clearing the fields could go straight
         * back to "showing everything" by re-filtering. The rows now come from the server filtered by what
         * was typed, so repainting alone would leave the panel showing the LAST SEARCH under an empty form.
         */
        refreshExistingRows();
        if (typeof clearFormError === 'function') clearFormError();
    }
    global.resetProductForm = resetProductForm;

    // Populate the Category dropdown from the catalog Category master; optionally pre-select one.
    function loadCategories(selectId) {
        $.get(serverContext + 'getUserCategories', function (resp) {
            var cats = (resp && resp.categories) ? resp.categories : [];
            var $sel = $('#prodCategory').empty();
            $sel.append($('<option>').val('').text('— none —'));
            cats.forEach(function (c) { $sel.append($('<option>').val(c.id).text(c.name)); });
            if (selectId != null) $sel.val(selectId);
        });
    }
    global.loadCategories = loadCategories;

    /**
     * Push a <select>'s CURRENT options and value into its visible control.
     *
     * WHY THIS IS NEEDED AT ALL: /js/common/searchable-selects.js turns every eligible <select> into a
     * bootstrap-select — a button plus a rendered menu — and HIDES the real element. Appending an <option>
     * or calling .val() therefore updates something invisible; the button keeps showing the old text until
     * the plugin is told to re-read.
     *
     * This is why addCategoryInline() appears to work and the manufacturer one did not: the category add
     * POSTs to /addCategory, and searchable-selects refreshes every picker on jQuery's ajaxComplete. The
     * manufacturer add is deliberately local (no master table, so no endpoint), so nothing ever refreshed it.
     *
     * Only refresh when an instance actually exists — see the "destroy trap" in searchable-selects.js:
     * driving the plugin on a plain <select> can construct and immediately destroy it, taking the original
     * element with it.
     */
    function refreshPicker(sel) {
        var $s = $(sel);
        if ($s.data('selectpicker')) {
            try { $s.selectpicker('refresh'); } catch (e) { /* plain select — nothing to refresh */ }
        }
    }

    /**
     * Populate the Manufacturer dropdown from the manufacturers ALREADY IN USE on this org's products.
     *
     * There is no manufacturer master table and this deliberately does not create one: the field stays a
     * plain String on the product, so `ProductRef`, the sale report and every other consumer are untouched.
     * The "list of registered manufacturers" is the distinct values across the tenant's products, computed
     * SERVER-side (GET /manufacturers). It used to be derived on the client from a whole-catalogue fetch
     * capped at 1,000 rows — so on a larger tenant every manufacturer that appeared only on older products
     * silently vanished from the dropdown, and the list looked complete.
     *
     * ⚠ TWO WAYS A <select> SILENTLY CORRUPTS DATA, both guarded here:
     *  1. Editing a product whose manufacturer is NOT among the options — `.val(x)` fails quietly, the select
     *     falls back to its first option, and saving would CHANGE the manufacturer without the user touching
     *     it. So `selected` is injected as an option when it is missing.
     *  2. The list failed to load — rendering an empty picker would blank the field on the next save. In
     *     that case the current value is kept as the only option rather than offering nothing.
     *
     * @param selected the value to pre-select (the product being edited), or '' for a new product
     */
    function loadManufacturers(selected) {
        var $sel = $('#prodManufacturer');
        if (!$sel.length) return;
        var cur = (selected == null) ? '' : String(selected);

        // Distinct, case-insensitively de-duplicated for the LIST only — the stored value is never rewritten,
        // so an existing "Nestle" and "nestlé" both survive on their products. This picker stops NEW
        // divergence; it does not silently merge what is already there.
        var seen = {}, names = [];
        manufacturerList.forEach(function (m) {
            var v = (m == null) ? '' : String(m).trim();
            if (!v) return;
            var k = v.toLowerCase();
            if (seen[k]) return;
            seen[k] = 1;
            names.push(v);
        });
        // Alphabetical, case-insensitive and locale-aware — a long brand list is scanned, not ranked.
        names.sort(function (a, b) { return a.localeCompare(b, undefined, { sensitivity: 'base' }); });

        // Guard 1 + 2: the product's own value must always be selectable.
        if (cur && !seen[cur.toLowerCase()]) { names.push(cur); }

        $sel.empty().append($('<option>').val('').text(t('ui.js.noneDash')));
        names.forEach(function (n) { $sel.append($('<option>').val(n).text(n)); });
        $sel.val(cur);
        refreshPicker($sel);   // the visible control is a bootstrap-select — see refreshPicker()
    }
    global.loadManufacturers = loadManufacturers;

    /**
     * Inline "add" for a manufacturer.
     *
     * DIFFERENT FROM addCategoryInline() ON PURPOSE: a Category is a real entity, so that one POSTs to
     * /addCategory. A manufacturer has no master, so this only adds the option locally and selects it — the
     * value becomes "registered", and visible to everyone, when the product is saved. Do not "fix" this by
     * pointing it at an endpoint; there isn't one, and adding one would mean the schema change this design
     * deliberately avoids.
     */
    global.addManufacturerInline = function () {
        var name = $('#prodManufacturerNew').val().trim();
        if (!name) { showFormError(t('ui.js.enterAManufacturerName')); return; }
        var $sel = $('#prodManufacturer');
        // Already listed (any casing)? Select the EXISTING spelling rather than adding a near-duplicate —
        // that is the whole point of offering the list.
        var match = null;
        $sel.find('option').each(function () {
            if (this.value && this.value.toLowerCase() === name.toLowerCase()) { match = this.value; return false; }
        });
        if (!match) {
            $sel.append($('<option>').val(name).text(name));
            match = name;
        }
        $sel.val(match);
        // Without this the new brand lands in the hidden <select> and the button still reads "— none —":
        // the value would be correct and invisible, which is worse than not adding it.
        refreshPicker($sel);
        $('#prodManufacturerNew').val('');
    };

    // Inline quick-add a category, then reload the dropdown with the new one selected.
    global.addCategoryInline = function () {
        var name = $('#prodCategoryNew').val().trim();
        if (!name) { showFormError(t('ui.js.enterACategoryName')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addCategory', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ name: name }),
            success: function (resp) {
                if (resp && resp.success && resp.data) {
                    showSaleSuccess(t('ui.js.categoryAdded'));
                    $('#prodCategoryNew').val('');
                    loadCategories(resp.data.id);
                } else { showFormError(apiMessage(resp, 'Could not add the category.')); }
            },
            error: function () { showFormError(t('ui.js.couldNotAddTheCategory')); }
        });
    };

    // Read a product's current on-hand from inventory and show it in its row.
    function refreshStock(productId) {
        $.get(serverContext + 'productStock?productId=' + productId, function (resp) {
            var v = (resp && resp.success) ? Number(resp.stock) : NaN;
            $('#stk_' + productId).text(isNaN(v) ? '0' : v);
        }).fail(function () { $('#stk_' + productId).text('—'); });
    }
    global.refreshStock = refreshStock;

    /**
     * ⭐ PERF-9 — show the on-hand the WRITE reported, and only read it back if it did not report one.
     *
     * <p>Both stock writes used to be followed by GET /productStock purely to learn the figure that had just
     * been computed on the server — two browser round trips to change one number in one cell. Over a shop's
     * own LAN that is invisible; over a real connection it is twice the latency of the operation, every time.
     *
     * <p>⚠ Falls back rather than assuming. A response with no `stock` means an older server (or a path
     * that does not report one), and rendering nothing — or worse, 0 — would tell a shopkeeper their stock
     * had vanished. Absent means "ask", never "zero".
     */
    function applyStock(productId, resp) {
        var v = resp ? Number(resp.stock) : NaN;
        if (resp && resp.stock != null && !isNaN(v)) {
            $('#stk_' + productId).text(v);
            return;
        }
        refreshStock(productId);
    }
    global.applyStock = applyStock;

    // Add opening stock for a product — feeds the inventory the storefront/POS reservation saga draws down.
    global.addProductStock = function (productId) {
        var qty = s2n($('#addstk_' + productId).val());
        if (qty <= 0) { showFormError(t('ui.js.enterAQuantityGreaterThan0To')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addProductStock', contentType: 'application/json', dataType: 'json',
            // BLK-2: the row button carries the wait (a compact spinner — btn-xs). Keeps the veil: no server key (BLK-5).
            busyControl: '#addstkbtn_' + productId, busyKind: 'post',
            data: JSON.stringify({ productId: productId, quantity: qty }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t('ui.js.added') + qty + ' to stock.');
                    $('#addstk_' + productId).val('');
                    // ⭐ PERF-9 — the write ALREADY told us the new on-hand; showing it costs no round trip.
                    applyStock(productId, resp);
                } else { showFormError(apiMessage(resp, 'Could not add stock.')); }
            },
            error: function () { showFormError(t('ui.js.couldNotAddStock')); }
            // (no `complete`: BusyControl releases the button on this request's ajaxComplete — BLK-2)
        });
    };

    // Correct on-hand — reduce (a mistaken over-add) by the entered quantity. Uses inventory's audited DECREASE
    // adjustment, which refuses to go below zero ("Insufficient stock"). Pass 'INCREASE' to add via the same path.
    global.adjustProductStock = function (productId, type) {
        var qty = s2n($('#addstk_' + productId).val());
        if (qty <= 0) { showFormError(t('ui.js.enterAQuantityToCorrectTheOn')); return; }
        var t = type || 'DECREASE';
        $.ajax({
            type: 'POST', url: serverContext + 'adjustProductStock', contentType: 'application/json', dataType: 'json',
            // BLK-2: the row button carries the wait. Keeps the veil: stock adjustment has no server key yet (BLK-5).
            busyControl: '#lessstkbtn_' + productId, busyKind: 'post',
            data: JSON.stringify({ productId: productId, adjustmentType: t, quantity: qty, reason: 'Manual stock correction' }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess((t === 'DECREASE' ? 'Removed ' : 'Added ') + qty + (t === 'DECREASE' ? ' from' : ' to') + ' stock.');
                    $('#addstk_' + productId).val('');
                    applyStock(productId, resp);   // PERF-9 — see addProductStock
                } else { showFormError(apiMessage(resp, 'Could not correct stock (not enough on hand?).')); }
            },
            error: function () { showFormError(t('ui.js.couldNotCorrectStock')); }
            // (no `complete`: BusyControl releases the button on this request's ajaxComplete — BLK-2)
        });
    };

    // Multi-rate tax: the org's tax codes, loaded once for the product-form dropdown. id -> {name, rate}.
    var taxCodes = [];
    function loadTaxCodes(selectedId, cb) {
        $.get(serverContext + 'catalogTaxCodes', function (resp) {
            taxCodes = Array.isArray(resp) ? resp : (typeof resp === 'string' ? (JSON.parse(resp) || []) : []);
            var $sel = $('#prodTaxCode');
            $sel.find('option:gt(0)').remove();   // keep the "Custom rate…" first option
            taxCodes.filter(function (c) { return c.active !== false; }).forEach(function (c) {
                $sel.append('<option value="' + c.id + '">' + escHtml(c.name + ' — ' + Number(c.rate || 0) + '%') + '</option>');
            });
            $sel.val(selectedId != null ? String(selectedId) : '');
            onProdTaxCodeChange();
            if (cb) cb();
        }, 'json').fail(function () { if (cb) cb(); });
    }
    // A chosen code supplies the rate → hide the custom % input; "Custom rate…" shows it.
    global.onProdTaxCodeChange = function () {
        $('#prodTax').toggle(!$('#prodTaxCode').val());
    };

    // Load a product into the form for editing — from a table row, or from "edit this one" on the
    // already-registered panel (which is how a would-be duplicate gets corrected instead of created).
    function editProduct(id) {
        $.get(serverContext + 'getCatalogProduct?id=' + id, function (resp) {
            var p = (resp && resp.data) ? resp.data : null;
            if (!p) { showFormError(t('ui.js.couldNotLoadTheProduct')); return; }
            // Coming from the panel the form may still carry the abandoned entry's duplicate flags. They belong
            // to a name/SKU that is about to be overwritten, so clear them before the new values land.
            $('#prodName, #prodSku').removeClass('alert-danger');
            markExistingRow(null);
            // DUP-1 — an edit must never carry a CREATE key. The reachable case is real: the operator starts a
            // new product, abandons it, and clicks "edit this one" on the already-registered panel. Saving an
            // update ignores the key, but leaving it in place would hand it to whatever is created next.
            retireProductKey();
            productCreatedCb = null;   // PUR-INLINE: an edit is not the create somebody was waiting for
            // BLK-4 — remember WHICH product this version belongs to, so saveProduct never sends one product's
            // version on another's save. Read fresh on every open (this $.get), so it is never a stale grid copy.
            editingVersion = { id: p.id, version: p.version };
            $('#productId').val(p.id);
            $('#prodName').val(p.name || '');
            $('#prodSku').val(p.sku || '');
            $('#prodBarcode').val(p.barcode || '');
            $('#prodPrice').val(p.sellingPrice != null ? p.sellingPrice : '');
            $('#prodTax').val(p.taxRate != null ? p.taxRate : '');
            loadTaxCodes(p.taxCodeId != null ? p.taxCodeId : '');
            $('#prodUnit').val(p.unit || '');
            // U1 — the pack rules. Read back so the form ROUND-TRIPS: without this an edit would post null
            // for every one of them, and only the server's "null means not supplied" guard would stand
            // between saving a phone number and wiping the product's pack configuration.
            $('#prodPackSize').val(p.packSize != null ? p.packSize : '');
            $('#prodLooseUnit').val(p.looseUnit || '');
            $('#prodLooseUnitPlural').val(p.looseUnitPlural || '');
            $('#prodAllowLoose').prop('checked', p.allowLoose === true);
            $('#prodDefaultSellUnit').val(p.defaultSellUnit || 'PACK');
            // C6 — per-product tracking policy. Read from the ref so the form shows what the TILLS will
            // enforce, not a separate copy that could disagree with it.
            $('#prodRequiresSerial').prop('checked', p.requiresSerial === true);
            $('#prodTracksBatch').prop('checked', p.tracksBatch === true);
            prodPackSizeChanged();
            loadProductStickers(p.id);   // U7: a sticker needs a product to point at
            // Select by category id (dropdown). Reload the list first so the product's category option is present.
            loadCategories(p.categoryId != null ? p.categoryId : '');
            // Pass the value INTO the loader rather than setting it afterwards: the option may not exist yet
            // (a manufacturer used only by this product, or an index that failed to load), and a bare .val()
            // on a missing option silently selects the first one — which would rewrite the field on save.
            loadManufacturers(p.manufacturer || '');
            $('#prodDesc').val(p.description || '');
            $('#ProductModalTitle').text('Edit Product');
            formEpoch++;               // this is a different product now — drop any in-flight name check
            refreshProductPanel();     // refresh the index so the checks + panel exclude only THIS product
            openModal('ProductModal');
            updateReadOnly(true);   // make the key fields readonly when editing

        }).fail(function () { showFormError(t('ui.js.couldNotLoadTheProduct')); });
    }
    global.editProduct = editProduct;

    // Submit: add a new product, or update the one being edited (hidden #productId set).
    /* ------------------------------------------------------------------------------------------------
     * Rapid cataloguing — "Save & Add Another".
     *
     * A register creates ONE record, so the shared post-save behaviour (wipe the form, close the modal)
     * is right for it. Cataloguing is not that shape: a shipment is a run of products that share a
     * brand, a category and a tax code, and the old flow made the operator re-open the modal and
     * re-pick all three for every single item. Same argument, and the same solution, as P6 gave the
     * purchase form.
     *
     * WHAT STAYS is the batch context — Manufacturer, Category, Tax code, Unit. WHAT CLEARS is what
     * identifies the individual product. Getting that split wrong in either direction is the whole
     * risk: clear too much and nothing is saved; keep too much and the next product silently inherits
     * a price or an SKU that belongs to the last one.
     * ---------------------------------------------------------------------------------------------- */
    /*
     * BLK-4 — the version of the product currently open for EDIT, paired with its id.
     *
     * Paired, not a bare number: saveProduct sends it only when the id still matches, so a version can never ride
     * along on a different product's save or on a create. editProduct fills it from a fresh read every time the
     * form opens, which is also why a product's own tracking-flag write right after a save cannot make the NEXT
     * edit look stale — that edit starts from another read.
     */
    var editingVersion = { id: null, version: null };
    var productAddAnother = false;   // which button submitted (consumed once, in saveProduct's success)
    var productSavedCount = 0;       // products catalogued into the run currently open

    /** Clear ONLY what identifies this product; leave the batch context selected. */
    function resetProductIdentityFields() {
        $('#productId').val('');
        // DUP-1 — the Save-&-Add-Another path. THE most important of the three: this is the one flow that keeps
        // the modal open across saves, so a key left in place here would make every subsequent product in the
        // run replay the first one.
        retireProductKey();
        ['prodName', 'prodSku', 'prodBarcode', 'prodPrice', 'prodDesc'].forEach(function (id) {
            $('#' + id).val('');
        });
        $('#prodSku').removeClass('alert-danger');
        $('#prodName').removeClass('alert-danger');
        // Any name/SKU check still in flight belongs to a product that has now been saved.
        formEpoch++;
        markExistingRow(null);
    }

    /** Post-save when the operator chose "Save & Add Another": stay in the form, ready for the next one. */
    function keepCataloguing() {
        resetProductIdentityFields();

        // Refresh the grid WITHOUT loadDataTable(): that rebuilds the DataTable and re-runs the
        // section's dropdown preload, which would repaint Category/Manufacturer and throw away the
        // selection this whole feature exists to keep.
        try { if (typeof datatable !== 'undefined' && datatable) datatable.ajax.reload(null, false); } catch (e) {}
        if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
        if (typeof clearFormError === 'function') clearFormError();
        if (typeof refreshProductPanel === 'function') refreshProductPanel();   // keep the dup-SKU check current

        productSavedCount++;
        $('#productSaveCount')
            .text(t('ui.js.productsAddedCount').replace('{0}', productSavedCount))
            .show();

        $('#prodName').focus();   // the first field of the next product
    }

    // Set the intent, then run the SAME validated save path as Submit — never a second copy of it.
    $(document).on('click', '#addProductAnother', function () {
        // "Add another" is meaningless while EDITING an existing product: there is nothing to add
        // another of, so fall through to the plain save-and-close.
        productAddAnother = !$('#productId').val();
        global.saveProduct();
    });

    global.saveProduct = function () {
        if (!$('#prodName').val().trim()) { showFormError(t('ui.js.productNameIsRequired')); return; }
        var id = $('#productId').val();
        // Client-side uniqueness: block a duplicate SKU before the round-trip (server still enforces it).
        var sku = $('#prodSku').val();
        if (isDuplicateSku(sku, id)) {
            $('#prodSku').addClass('alert-danger').focus();
            showFormError(t('ui.js.sku') + sku.trim() + '" is already used by another product. Enter a unique SKU.');
            return;
        }
        // Multi-rate tax: a chosen code supplies the rate (taxCodeId); "Custom rate…" sends a one-off taxRate instead.
        var codeId = $('#prodTaxCode').val();
        var body = {
            // SKU is optional — send null, not '', so "no code" is absent rather than a value that
            // collides with every other uncoded product. (The service normalises too; this keeps the
            // payload honest and matches how barcode has always been sent.)
            name: $('#prodName').val().trim(), sku: (sku || '').trim() || null,
            barcode: $('#prodBarcode').val().trim() || null,
            sellingPrice: s2n($('#prodPrice').val()),
            taxCodeId: codeId ? Number(codeId) : null,
            taxRate: codeId ? null : s2n($('#prodTax').val()),
            unit: $('#prodUnit').val(),
            // U1. packSize null = "this product is not divisible"; the server treats null as NOT SUPPLIED,
            // so clearing the box leaves the old value — see the note in ProductService.applyDto. Sending 0
            // rather than null would be read as a pack of nothing.
            packSize: s2n($('#prodPackSize').val()) || null,
            looseUnit: $('#prodLooseUnit').val() || null,
            looseUnitPlural: $('#prodLooseUnitPlural').val() || null,
            allowLoose: $('#prodAllowLoose').is(':checked'),
            defaultSellUnit: $('#prodDefaultSellUnit').val() || 'PACK',
            categoryId: $('#prodCategory').val() ? Number($('#prodCategory').val()) : null,
            manufacturer: $('#prodManufacturer').val(), description: $('#prodDesc').val()
        };
        var url = 'addProduct';
        if (id) { body.id = Number(id); url = 'updateProduct'; }
        /*
         * BLK-4 — send back the version this form LOADED, so a save against a copy somebody else has since changed
         * (another editor, or a purchase re-pricing the product) is refused instead of silently overwriting it.
         * Only when the id matches the product that version was read for. A refusal arrives as success:false with
         * the server's "someone else changed this" sentence: shown by the else-branch below, and the form is NOT
         * reset, so the operator's typing survives while they reload the product.
         */
        if (id && editingVersion.version != null && String(editingVersion.id) === String(id)) {
            body.version = editingVersion.version;
        }
        /*
         * DUP-1 — one key per form-fill, so a repeated submit replays instead of registering a second product.
         *
         * CREATE ONLY. An update is addressed by id and is already idempotent; sending a create key on it would
         * only invite a later reader to think the two paths share a rule they do not.
         *
         * This is the layer that does not depend on the browser behaving: a held Enter, a double-click, a retry
         * after a dropped connection and a reload mid-save all arrive carrying the same key, and the server
         * (V16 unique index + ProductController's replay) answers with the ONE product that was created.
         */
        if (!id) body.idempotencyKey = productKey();
        $.ajax({
            type: 'POST', url: serverContext + url, contentType: 'application/json', dataType: 'json',
            /*
             * BLK-2 — the PRESSED button carries the wait, and the screen does not.
             *
             * nonBlocking is allowed here, and only because the server already de-duplicates this write: a create
             * carries the DUP-1 key (V16 unique index + replay) and an update is addressed by id. Both submits are
             * locked while it is in flight — the pressed one labelled "Saving…", the modal's other one disabled by
             * layer 2b — so an edited field cannot be sent again mid-request.
             */
            nonBlocking: true,
            busyControl: productAddAnother ? '#addProductAnother' : '#addProduct',
            data: JSON.stringify(body),
            success: function (resp) {
                if (resp && resp.success) {
                    /*
                     * ⚠ RETIRED FIRST — before saveProductTracking, keepCataloguing and loadDataTable, any of
                     * which can throw. If a throw were to skip this line, the NEXT product would carry this same
                     * key and the server would correctly replay THIS one: the operator catalogues twenty items,
                     * the shop gets one, and every save says "saved". That is worse than the duplicate bug, and
                     * it is exactly how SF-3b happened on the sale (a throw between retiring the key and
                     * clearing the cart). Retire on success only — a failure keeps the key so a retry is safe.
                     */
                    if (!id) retireProductKey();
                    // C6 — the tracking policy is saved through its OWN endpoint, like the clinical flags,
                    // because it is policy rather than product data. Fired after the product exists so a
                    // newly created product has an id to attach it to.
                    saveProductTracking(id || (resp.data && resp.data.id));
                    showSaleSuccess(id ? 'Product updated.' : 'Product saved.');
                    // Consume the intent exactly once, whatever we decide below, so it can never leak
                    // into a later save (the same rule afterSavePurchase follows).
                    var addAnother = productAddAnother;
                    productAddAnother = false;

                    if (addAnother) {
                        keepCataloguing();
                        return;
                    }
                    /*
                     * PUR-INLINE — the product was registered from inside another form, so go BACK to it.
                     *
                     * ⚠ loadDataTable() IS DELIBERATELY SKIPPED ON THIS PATH, and not as an optimisation.
                     * It sets `edit = false` (business.js:1901) — the SHARED edit-mode flag — and clears the
                     * sale-report table. Called while a purchase line was open behind this modal, it would
                     * silently take that line out of edit mode, so saving it would ADD a line instead of
                     * updating one. Nothing on screen would say so.
                     *
                     * The grid behind is not stale either: whatever screen the operator returns to reloads
                     * its own rows, and /addProduct has already dropped the shared picker cache (PERF-8).
                     */
                    var handBack = productCreatedCb;
                    productCreatedCb = null;          // consumed exactly once, whatever happens next
                    if (handBack) {
                        resetProductForm();
                        closeModal('ProductModal');
                        try { handBack(resp.data); } catch (e) { /* never strand the operator in the modal */ }
                        return;
                    }

                    resetProductForm();
                    closeModal('ProductModal');
                    loadDataTable();
                    if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
                } else { showFormError(apiMessage(resp, 'Could not save the product.')); }
            },
            // PERM-1: a refusal is an ANSWER. PermissionInterceptor replies 403 with a sentence ("You are not
            // allowed to edit products. Ask the shop owner."), and discarding it for a generic "could not save"
            // left the operator unable to tell a permission from a fault. apiFailMessage reads that sentence.
            error: function (xhr) { showFormError(apiFailMessage(xhr, t('ui.js.couldNotSaveTheProduct'))); }
        });
    };

    // Delete = deactivate the checked products (they drop off the active list, stay intact for history).
    // `preselectedIds` comes from the shared bulk-delete path (main.js performBulkDelete → bulkDeleteProduct),
    // which has already collected and confirmed them; called with no argument it reads the checkboxes itself.
    // PROD-DEL: Delete deactivates an ACTIVE product and permanently deletes a DEACTIVATED one (the user's ruling,
    // 2026-09-14). The server decides per product and reports each outcome; see CatalogController.removeProducts.
    global.deactivateProducts = function (preselectedIds) {
        var ids = preselectedIds
            || $("#tableProduct input[type='checkbox']:checked").map(function () { return this.value; }).get().join(',');
        if (!ids) { showFormError(t('ui.js.selectAtLeastOneProductToRemove')); return; }

        // How many selected rows are already deactivated. The status cell reads "Active" (label-success) or
        // "Inactive" (label-default), so the default label marks one.
        var inactive = 0;
        String(ids).split(',').forEach(function (id) {
            var $tr = $("#tableProduct input[type='checkbox'][value='" + String(id).trim() + "']").closest('tr');
            if ($tr.find('.label.label-default').length) inactive++;
        });

        function send() {
            $.ajax({
                type: 'POST', url: serverContext + 'removeProducts', contentType: 'application/json', dataType: 'json',
                data: JSON.stringify({ checked: ids }),
                success: function (resp) {
                    if (resp && resp.success) {
                        var kept = resp.kept || [];
                        var msg = resp.message || t('ui.js.productSRemoved');
                        // A kept product is an answer the owner needs to read ("used by 3 stock level records"),
                        // so it is shown as the prominent message rather than folded into a success toast.
                        if (kept.length) showFormError(msg); else showSaleSuccess(msg);
                        resetProductForm(); loadDataTable();
                        if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
                    } else { showFormError(apiMessage(resp, 'Could not remove the product(s).')); }
                },
                error: function (xhr) { showFormError(apiFailMessage(xhr, t('ui.js.couldNotRemoveTheProductS'))); }
            });
        }

        // ⚠ A permanent delete cannot be undone, so it gets its own confirm naming that (STANDARDS §0c question 5),
        // even though the shared bulk path has already asked "delete?". Shown only to the owner (the only one the
        // server lets delete permanently) and only when a deactivated row is selected; everyone else's
        // deactivated rows are reported back as kept.
        if (inactive > 0 && global.canPermanentlyDeleteProduct && typeof global.uiConfirm === 'function') {
            global.uiConfirm({
                title: 'Delete permanently?',
                message: inactive + (inactive === 1 ? ' deactivated product' : ' deactivated products')
                    + ' will be deleted permanently. This cannot be undone. A product still used by stock, sales,'
                    + ' orders, a price rule or a bonus scheme is kept instead.'
            }).then(function (ok) { if (ok) send(); });
            return;
        }
        send();
    };

    // The shared bulk-delete path looks for window.bulkDelete<Entity> before falling back to POST
    // /delete<Entity>. Registering it here is what stops the Product screen posting to /deleteProduct,
    // which does not exist — a product is deactivated, never deleted.
    global.bulkDeleteProduct = function (ids) { global.deactivateProducts(ids); };

    // "Show inactive" toggle — include deactivated products in the list (with a Status column + Reactivate action).
    global.toggleShowInactiveProducts = function (checked) {
        window.productShowInactive = !!checked;
        loadDataTable();
    };

    // Reactivate a deactivated product — brings it back into the list + the purchase/sale/medicine pickers.
    global.reactivateProduct = function (productId) {
        $.ajax({
            type: 'POST', url: serverContext + 'activateProduct', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ id: productId }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess(t('ui.js.productReactivated')); loadDataTable(); }
                else { showFormError(apiMessage(resp, 'Could not reactivate the product.')); }
            },
            error: function () { showFormError(t('ui.js.couldNotReactivateTheProduct')); }
        });
    };

    // Instant "unique check during entry": flag a duplicate SKU as soon as the user leaves the field,
    // and clear the flag while they retype. Delegated so it works with the modal form present at load.
    $(function () {
        $(document).on('blur', '#prodSku', function () {
            /*
             * ⭐ ASK THE SERVER. This used to consult a client-side index built from a whole-catalogue fetch
             * capped at 1,000 rows — so on a 1,632-product tenant 632 SKUs were invisible and every one of
             * them was reported FREE. The operator got a clean field and a rejected save.
             *
             * The epoch guard matters as much as the check: the answer arrives after the user has moved on,
             * and the form may by then hold a different product (they hit Cancel, or clicked another row).
             * Flagging a field that no longer belongs to the SKU we asked about is worse than not flagging.
             */
            var $field = $(this);
            var v = $field.val();
            var epoch = formEpoch;
            checkSkuOnServer(v, $('#productId').val(), function (taken) {
                if (epoch !== formEpoch) return;               // this answer belongs to a form that is gone
                if ($field.val() !== v) return;                // they kept typing — it is about a stale value
                if (taken) {
                    $field.addClass('alert-danger');
                    showFormError(t('ui.js.sku') + String(v).trim()
                        + '" is already used by another product. Enter a unique SKU.');
                } else {
                    $field.removeClass('alert-danger');
                }
            });
        });
        $(document).on('input', '#prodSku', function () {
            $(this).removeClass('alert-danger');
            // The remembered verdict was about the OLD value; keeping it would let save() block a SKU the
            // server never refused, or clear one it did.
            clearSkuVerdict();
        });

        // Typing in any of the three identifying fields narrows the panel.
        // Typing must now ASK the server — the rows are no longer sitting in memory to re-filter.
        // refreshExistingRows() debounces and sequence-guards, so a keystroke does not mean a request.
        $(document).on('input', '#prodName, #prodSku, #prodBarcode', function () { refreshExistingRows(); });

        // ── Server-side duplicate-NAME check, on focus-out of Name ──────────────
        // Server-side and not just a scan of the loaded index, because the index is a snapshot taken when the
        // form opened: a colleague in the same org registering the product a minute ago is invisible to it and
        // visible to this. It ADVISES — a duplicate name is legal (same product, different pack or maker), so
        // the save is not blocked; only a duplicate SKU is refused, by the service.
        $(document).on('blur', '#prodName', function () {
            var $f = $(this);
            var typed = ($f.val() == null ? '' : String($f.val())).trim();
            $f.removeClass('alert-danger');
            markExistingRow(null);
            if (!typed) return;

            var id = $('#productId').val();
            var epoch = formEpoch;   // whose answer this is
            var url = serverContext + 'productNameCheck?name=' + encodeURIComponent(typed)
                + (id ? '&excludeId=' + encodeURIComponent(id) : '');

            $.get(url, function (resp) {
                // Drop a stale answer: the form has since been reset, or loaded with a different product, or
                // the operator has already retyped the name. Flagging a field against a name it no longer
                // holds is worse than not checking at all.
                if (epoch !== formEpoch) return;
                if (($('#prodName').val() || '').trim() !== typed) return;
                if (!resp || !resp.success || !resp.exists) return;

                $('#prodName').addClass('alert-danger');
                showFormError(t('ui.js.productNameAlreadyRegistered', resp.name || typed));
                markExistingRow(resp.id);
            }, 'json');
        });
        $(document).on('input', '#prodName', function () { $(this).removeClass('alert-danger'); });

        // "Edit this one" on a panel row → load that product into the form instead of registering a twin.
        // mousedown fires before the Name field's blur; suppressing it keeps focus put so the click is not
        // racing a name-check that is about to flag the very row being clicked.
        $(document).on('mousedown', '.js-edit-existing', function (e) { e.preventDefault(); });
        $(document).on('click', '.js-edit-existing', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var id = $(this).closest('.crud-existing-row').data('id');
            if (!id) return;
            if (typeof clearFormError === 'function') clearFormError();
            editProduct(id);
        });
    });

    /* ══════════════════════════════════════════════════════════════════════════════════════════════════
     * The Product grid, SERVER-PAGED.
     *
     * Asked for by the user: *"fetching 1000 may cause slowness or performance problem. implement
     * pagination default 50 and click next to fetch next 50 and so on"*.
     *
     * ## What was actually wrong
     * The grid asked for `size=1000&sort=id,desc` and rendered every row. Two costs, one of them silent:
     * the tenant downloaded ~618KB to look at the first screenful, and at 1,001 products the thousand-and
     * -first simply was not in the list. The `sort=id,desc` added earlier made the truncation land on the
     * OLDEST rows instead of the newest, which was the better half of a bad trade, not a fix.
     *
     * ## Why paging alone would have made the screen WORSE
     * DataTables searches and sorts the rows it is holding. Leave those client-side and page the fetch, and
     * the search box goes from searching 1,000 products to searching 50 — typing a product name that
     * exists returns "No matching records", confidently, with no error anywhere. So search and sort move to
     * the server in the SAME change; they cannot ship apart. `serverSide: true` is what makes DataTables
     * hand all three to the `ajax` function below instead of doing them locally.
     *
     * ## Why this is a separate initialiser
     * `loadDataTable()` is shared by 34 call sites across four modules. This grid needs a different
     * DataTables mode, not a different URL, and rebuilding the shared function around one screen's needs is
     * how the other 33 acquire a defect nobody was looking for. `loadDataTable()` forks to here in one line.
     * ══════════════════════════════════════════════════════════════════════════════════════════════════ */

    var PAGE_SIZE_DEFAULT = 50;

    /*
     * "All" is 1,000 — deliberately the SAME ceiling the unpaged grid already used.
     *
     * It exists for export (below), not for browsing. Picking a number here that the old screen did not
     * already reach every single time would have been a new unbounded read introduced by the change that
     * was supposed to remove one.
     */
    // (PS-1f) The old ALL_ROWS = 1000 ceiling is GONE. "All" now pages through via PagedFetch, so
    // there is no number here to quietly become a limit again.

    /*
     * Column index → the catalog property to sort by. `null` means the server cannot sort it, and the column
     * is marked unorderable so the header does not offer a sort that would silently do nothing.
     *
     * ⚠ POSITIONAL, against #tableProduct's header. Same hazard the "Toggle column" data-column indexes
     * carry: insert a column into the table and every entry after it points at the wrong field.
     *   0 id · 1 checkbox · 2 name · 3 sku · 4 unit · 5 price · 6 last-purchase · 7 last-sale
     *   8 tax% · 9 category · 10 manufacturer · 11 on-hand · 12 add-stock · 13 status
     *
     * On-hand is not sortable because it is not catalog's to sort — it is filled in afterwards from
     * inventory (see fillProductOnHand). A header that sorted it would sort the "…" placeholders.
     */
    var SORT_FIELD = [
        'id', null, 'name', 'sku', 'unit', 'sellingPrice',
        'lastPurchaseRate', 'lastSaleRate', 'taxRate', 'category.name', 'manufacturer',
        null, null, 'isActive'
    ];

    /**
     * ONE product as the 14 cells #tableProduct expects.
     *
     * ⚠ This array MUST stay exactly as long as the header — a missing cell shifts every later column and
     * DataTables throws "Requested unknown parameter".
     *
     * <p>Moved here verbatim from `loadDataTable()`, which no longer renders products. It was not copied:
     * two renderers for one grid is how a column gets fixed on one screen and stays broken on the other.
     */
    function productGridRow(obj) {
        return [
            "<div id=productId>" + obj.id + "</div>",
            "<input type='checkbox' value=" + obj.id + ">",
            "<div id=name>" + escHtml(obj.name || '') + "</div>",
            "<div id=sku>" + escHtml(obj.sku || '') + "</div>",
            "<div id=unit>" + escHtml(obj.unit || '') + "</div>",
            "<div id=sellingPrice>" + (obj.sellingPrice != null ? Number(obj.sellingPrice).toFixed(2) : '') + "</div>",
            // Last rates come stamped on the product itself (written by the purchase flow), so they render
            // straight from this row — no lazy fill, no extra request.
            lastRateCell(obj.lastPurchaseRate, obj.lastRateAt, t('ui.js.lastPurchased')),
            lastRateCell(obj.lastSaleRate,     obj.lastRateAt, t('ui.js.lastSold')),
            "<div id=taxRate>" + (obj.taxRate != null ? Number(obj.taxRate).toFixed(2) : '') + "</div>",
            "<div id=categoryName>" + escHtml(obj.categoryName || '') + "</div>",
            // Operator-typed free text → escHtml (XSS-safe rendering rule).
            "<div id=manufacturer>" + escHtml(obj.manufacturer || '') + "</div>",
            "<div id=stk_" + obj.id + " class=prod-onhand>…</div>",
            "<div class='row-actions'>"
                + "<input type=number min=0 step=any id=addstk_" + obj.id + " class='form-control input-sm prod-addstk' style='width:80px;display:inline-block'>"
                + "<button type=button id=addstkbtn_" + obj.id + " class='btn btn-xs btn-success' style='margin-left:4px' title='Add to on-hand' onclick='addProductStock(" + obj.id + ")'><span class='glyphicon glyphicon-plus'></span></button>"
                + "<button type=button id=lessstkbtn_" + obj.id + " class='btn btn-xs btn-warning' style='margin-left:4px' title='Correct / reduce on-hand' onclick='adjustProductStock(" + obj.id + ")'><span class='glyphicon glyphicon-minus'></span></button>"
                + "</div>",
            (obj.isActive === false
                ? "<span class='label label-default'>Inactive</span> <button type=button class='btn btn-xs btn-info' style='margin-left:6px' onclick='reactivateProduct(" + obj.id + ")' title='Reactivate this product'><span class='glyphicon glyphicon-refresh'></span> Reactivate</button>"
                : "<span class='label label-success'>Active</span>")
        ];
    }

    /**
     * On-hand for the rows now on screen, in ONE batch call.
     *
     * <p>Product on-hand is inventory, not catalog, so it cannot come down with the page. `/productStockLevels`
     * → inventory `/stock/levels/detail` returns the whole tenant's levels keyed by product id, and this looks
     * up only the ids drawn — one request per page, never one per row.
     *
     * <p>It shows the honest SELLABLE count (what a sale can actually reserve) plus a red "N expired" badge
     * when physical stock is locked in expired batches, so a 16-on-hand/0-sellable product no longer lies.
     */
    var STOCK_ID_CHUNK = 100;

    function fillProductOnHand(collections) {
        if (!collections || !collections.length) return;

        /*
         * ⭐ ASK ONLY FOR THE ROWS ON SCREEN (PS-1a).
         *
         * This used to GET /productStockLevels with no parameters, which returned EVERY product's levels for
         * the whole tenant to paint 50 cells — 1,112 entries / 69.5 KB measured here, growing with the
         * catalogue and bounded by nothing.
         *
         * ⚠ CHUNKED AT 100, and that number is not arbitrary: 730 ids in one GET once became a silent
         * Tomcat 400 (PERF-12), caught only because a caller logged the warning. Three other callers in this
         * codebase already chunk at the same ceiling.
         *
         * ⚠ A CHUNK THAT FAILS PAINTS "—", NEVER "0". While this fetched the whole tenant, "absent from the
         * response" safely meant "no stock row". Asking for specific ids breaks that equivalence: a failed
         * chunk would otherwise stamp OUT OF STOCK across real inventory, which is a stock figure the shop
         * would act on. Only a chunk that actually answered may resolve an absent id to 0.
         */
        var ids = collections.map(function (o) { return o.id; }).filter(function (v) { return v != null; });
        if (!ids.length) return;

        for (var i = 0; i < ids.length; i += STOCK_ID_CHUNK) {
            fillOnHandChunk(collections, ids.slice(i, i + STOCK_ID_CHUNK));
        }
    }

    function fillOnHandChunk(collections, chunkIds) {
        var wanted = {};
        chunkIds.forEach(function (id) { wanted[String(id)] = true; });

        $.get(serverContext + "productStockLevels", { ids: chunkIds.join(',') }, function (resp) {
            var levels = (resp && resp.success && resp.levels) ? resp.levels : {};
            $.each(collections, function (ind, obj) {
                if (!wanted[String(obj.id)]) return;      // another chunk owns this row
                var d = levels[obj.id];
                var el = $('#stk_' + obj.id);
                // Asked, and the server named no stock row for it — that genuinely is zero.
                if (d == null) { el.text('0'); return; }
                // Back-compat: a bare number means sellable only.
                var sellable = (typeof d === 'object') ? Number(d.sellable || 0) : Number(d);
                var expired  = (typeof d === 'object') ? Number(d.expired  || 0) : 0;
                var onHand   = (typeof d === 'object') ? Number(d.onHand   || 0) : sellable;
                /*
                 * U6 — say it in the language of the person counting the shelf. 9.5 is arithmetically true
                 * and operationally useless: it means nine sealed packs and five loose tablets. An ordinary
                 * product has no pack size, so shelfText returns the plain number.
                 */
                var shelf = (typeof shelfText === 'function')
                    ? shelfText(sellable, obj.packSize, obj.looseUnitPlural || obj.looseUnit)
                    : { text: String(sellable) };
                var html = "<span title='" + onHand + " physical on-hand'>" + escHtml(shelf.text) + "</span>";
                if (expired > 0) {
                    html += " <span class='label label-danger' style='margin-left:4px' title='" + expired
                        + " unit(s) in expired batches — physically present but not sellable'>" + expired + " expired</span>";
                }
                el.html(html);   // numbers only (no user data) → XSS-safe
            });
        }).fail(function () {
            /*
             * ⚠ ONLY THIS CHUNK'S ROWS. Before chunking there was one request, so blanking every cell was
             * right. Now a failed chunk must not wipe the cells a SUCCESSFUL chunk has already filled — that
             * would turn one bad request into a screen of unknowns.
             *
             * "—" and never "0": an unanswered request means the figure is unknown, and "0" would read as
             * out of stock on inventory the shop actually holds.
             */
            $.each(collections, function (ind, obj) {
                if (wanted[String(obj.id)]) $('#stk_' + obj.id).text('—');
            });
        });
    }

    /**
     * Make an export button cover the WHOLE result set, not just the page on screen.
     *
     * <h3>The silent half of server-side paging</h3>
     * DataTables exports the rows it holds. Under `serverSide` that is one page — so Excel/PDF/Print would
     * have quietly started producing a 50-row file for a 1,042-product catalogue. Nothing errors; the file
     * opens; it is simply missing 992 products. That is the whole class of defect this codebase keeps
     * paying for, so the button loads everything first.
     *
     * <h3>⚠ Why the page length is NOT restored afterwards</h3>
     * The export libraries are lazy-loaded (see lazy-export.js): the button's action returns a promise and
     * reads the table LATER, once JSZip/pdfmake has arrived. Restoring the length as soon as the action
     * returns would put the 50-row page back before the exporter ever looked — reintroducing the exact bug
     * this wrapper exists to prevent, and only on slow connections, which is the worst way to have it.
     * The grid is therefore left showing every row; the state is visible, and the length menu puts it back.
     */
    function exportAll(btn) {
        var inner = btn && btn.action;
        if (typeof inner !== 'function') return btn;
        return $.extend({}, btn, {
            action: function (e, dt, node, cfg) {
                var self = this, args = [e, dt, node, cfg];
                if (dt.page.len() === -1) { inner.apply(self, args); return; }   // already showing everything
                dt.one('draw', function () { inner.apply(self, args); });
                dt.page.len(-1).draw(false);
            }
        });
    }

    /**
     * Build (or rebuild) #tableProduct as a server-paged grid. Entered only through `loadDataTable()`.
     */
    global.loadProductTable = function () {
        if (datatable != null) { datatable.destroy(); datatable = null; }

        // The section's dropdowns belong to the SECTION, not to a grid refresh — start them in parallel with
        // the grid rather than after it. Same call, same reason, as the shared path.
        preloadSectionPickers();

        datatable = $('#tableProduct').DataTable({
            serverSide: true,
            processing: true,          // DataTables draws its own "Processing…" while a page is in flight
            lengthMenu: [[25, 50, 100, 200, -1], ['25', '50', '100', '200', 'All']],
            pageLength: PAGE_SIZE_DEFAULT,
            order: [[0, 'desc']],      // newest first — the same window the unpaged grid settled on
            autoWidth: true,
            // A keystroke must not be a request. 400ms is long enough to type through and short enough that
            // the list feels live; DataTables also skips the call when the term has not changed.
            searchDelay: 400,
            columnDefs: [
                { targets: 0, visible: false },
                { targets: SORT_FIELD.reduce(function (acc, f, i) { if (f === null) acc.push(i); return acc; }, []),
                  orderable: false }
            ],
            dom: 'Bfrtip',
            buttons: ['pageLength',
                exportAll(lazyExcelButton({ footer: true })),
                exportAll({ extend: 'print', footer: true }),
                exportAll(lazyPdfButton({ orientation: 'landscape', pageSize: 'LEGAL', footer: true }))
            ].concat((typeof importButtons === 'function') ? importButtons('Product') : []),

            ajax: function (d, callback) {
                /*
                 * The adapter between DataTables' request shape and /getProductPage.
                 *
                 * A translation layer rather than teaching the server DataTables' protocol (draw / start /
                 * length / search[value]): that protocol would then be baked into a monolith proxy and a
                 * catalog endpoint that other, non-DataTables callers — the dashboard category drill, for
                 * one — also use.
                 */
                var all = !(d.length > 0);                       // "All" arrives as length = -1
                var size = all ? PagedFetch.PAGE_SIZE : d.length;
                var page = all ? 0 : Math.floor(d.start / d.length);

                var ord = (d.order && d.order.length) ? d.order[0] : null;
                var field = ord ? SORT_FIELD[ord.column] : null;
                var sort = field ? (field + ',' + (ord.dir === 'asc' ? 'asc' : 'desc')) : 'id,desc';

                var params = { page: page, size: size, sort: sort };
                if (d.search && d.search.value) params.q = d.search.value;
                if (global.productShowInactive) params.includeInactive = true;
                // Set by the dashboard category card; see openProductsForCategory().
                if (global.productCategoryFilter != null) {
                    if (global.productCategoryFilter === 'none') params.uncategorised = true;
                    else params.category = global.productCategoryFilter;
                }

                /** Hand DataTables a drawn set. `total` is the FILTERED total it paginates by. */
                var deliver = function (rows, total) {
                    if (rows.length) userId = rows[0].userId;   // keeps the shared bookkeeping happy

                    callback({
                        draw: d.draw,
                        // Equal on purpose. DataTables prints "(filtered from N total)" only when they
                        // differ, and the server returns the FILTERED total — quoting an unfiltered
                        // count we were never told would be a number invented on the client.
                        recordsTotal: total,
                        recordsFiltered: total,
                        data: rows.map(productGridRow)
                    });

                    // After the draw, so the #stk_<id> cells the fill targets exist.
                    fillProductOnHand(rows);
                };

                var onFail = function (jqXHR, textStatus, errorThrown) {
                    callback({ draw: d.draw, recordsTotal: 0, recordsFiltered: 0, data: [] });
                    handleAjaxFailure(jqXHR, errorThrown, 'loadProductTable');
                };

                if (all) {
                    /*
                     * ⭐ PS-1f — "All" MEANS ALL. It used to mean `size=ALL_ROWS`, i.e. 1000.
                     *
                     * DataTables was handed 1000 rows and told `recordsTotal` was 1,632, so it printed
                     * "Showing 1 to 1000 of 1,632" — the count was right and the rows were not. Silent in
                     * the one way that matters: nothing on screen said which 632 were missing.
                     *
                     * ⚠ THE EXPORT IS WHY THIS IS NOT COSMETIC. exportAll() switches the grid to "All"
                     * before handing the rows to Excel/PDF/Print precisely so a 50-row page does not become
                     * a 50-row spreadsheet — its own comment calls that "the whole class of defect this
                     * codebase keeps paying for". With a 1000-row ceiling it was still producing exactly
                     * that file, just a bigger one, and a stock-take done from it would be short.
                     *
                     * PagedFetch reads page 0, learns totalPages from the envelope, then fetches the rest in
                     * PARALLEL — and says so in the console if a catalogue ever exceeds ITS ceiling, rather
                     * than trimming quietly. Reused rather than re-implemented; it needed only to learn the
                     * monolith's {collection, page} envelope.
                     */
                    /*
                     * ⚠ STRIP page/size BEFORE handing the query to PagedFetch — it appends its OWN.
                     *
                     * Leaving them in produces `?page=0&size=500&page=1&size=500`, and a proxy that keeps
                     * only the FIRST value of a repeated parameter would then re-read page 0 for every page
                     * and return the same rows N times. That exact collapse has bitten this codebase before
                     * (the purchase proxy, on `serials`), and it fails by returning plausible data.
                     */
                    var pagedParams = $.extend({}, params);
                    delete pagedParams.page;
                    delete pagedParams.size;

                    PagedFetch.all('getProductPage?' + $.param(pagedParams), function (rows) {
                        // Everything was fetched, so the row count IS the total — no second call to ask.
                        deliver(rows, rows.length);
                    }, onFail);
                } else {
                    $.get(serverContext + 'getProductPage', params)
                        .done(function (resp) {
                            var rows = (resp && resp.collection) ? resp.collection : [];
                            var meta = (resp && resp.page) ? resp.page : {};
                            var total = (meta.totalElements != null) ? Number(meta.totalElements) : rows.length;
                            deliver(rows, total);
                        })
                        .fail(onFail);
                }
            }
        });

        // Enable the fields for editing the data in the table (parity with the shared path).
        updateReadOnly(false);
    };

    /**
     * Open the Product screen already filtered to one category — the dashboard card's destination.
     *
     * <p>`'none'` is the uncategorised bucket, and is NOT the same as no filter: null already means "any
     * category", so the products with no category at all needed a value of their own to be reachable.
     */
    global.openProductsForCategory = function (categoryId, label) {
        // `null` is the uncategorised BUCKET, not "no filter" — see the note above. Clearing the filter is
        // openProductsUnfiltered(), a separate call, so neither can be reached by accident from the other.
        global.productCategoryFilter = (categoryId == null ? 'none' : categoryId);
        global.productCategoryLabel = label || '';
        showProducts();
        renderProductFilterChip();
    };

    /** Open the Product screen with no category filter at all — the card's "All categories" link. */
    global.openProductsUnfiltered = function () {
        global.productCategoryFilter = null;
        global.productCategoryLabel = '';
        showProducts();
        renderProductFilterChip();
    };

    /**
     * The active-filter chip.
     *
     * <p>A grid that silently holds a filter is a support call: the shop searches for a product it owns,
     * finds nothing, and has no way to see why. The chip states the filter and is itself the way to clear
     * it — the pattern every faceted catalogue (Amazon, eBay, LinkedIn) settled on for the same reason.
     */
    function renderProductFilterChip() {
        var $bar = $('#productFilterBar');
        if (!$bar.length) return;
        if (global.productCategoryFilter == null) { $bar.empty().hide(); return; }
        $bar.html('<span class="active-filter-chip">'
            + '<span class="glyphicon glyphicon-filter"></span> '
            + escHtml(global.productCategoryLabel || 'Category')
            + ' <button type="button" class="chip-x" id="productFilterClear" '
            + 'aria-label="Clear this filter" title="Clear this filter">&times;</button></span>').show();
    }

    $(document).on('click', '#productFilterClear', function () {
        global.productCategoryFilter = null;
        global.productCategoryLabel = '';
        renderProductFilterChip();
        loadDataTable();          // forks straight back to loadProductTable(), now unfiltered
    });

})(window);
