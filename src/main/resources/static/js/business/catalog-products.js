/*
 * Catalog Product master (slice 42, M1; UI-parity refactor) — register/list/edit Products in catalog-service
 * (the single product master shared by POS, pharmacy, e-commerce). The list now renders through the SHARED
 * loadDataTable() DataTable path (same as Customer): sort/search/paging/export, a hidden id + checkbox column,
 * row-click edit, and a Delete that DEACTIVATES (products referenced by sales/inventory stay intact, just drop
 * off the list). Per-row Add-stock (addstkbtn_<id>) is preserved. Proxies: /getUserProduct (list, {collection}),
 * /addProduct, /updateProduct, /deactivateProduct, /addProductStock, /productStock, /getCatalogProduct,
 * /productNameCheck.
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

    // ── The registered-product index (one fetch, two consumers) ─────────────────
    // /getUserProduct?includeInactive=true returns this tenant's whole catalogue. It was ALREADY fetched every
    // time the screen or the form opened, with everything except sku→id thrown away — so the "already
    // registered" panel costs no extra round-trip, it just stops discarding the rows. Inactive products are
    // included deliberately: a deactivated product still owns its SKU downstream, and a namesake the operator
    // cannot see on the list is precisely the one they are about to register a second time.
    var productIndex = [];      // rows as returned by /getUserProduct
    var indexState = 'empty';   // 'empty' | 'loaded' | 'failed' — see below
    var skuIndex = {};          // normalised sku → owning product id (as a string)

    // Cap on rows painted into the panel. A tenant may hold hundreds of products; painting all of them on
    // every keystroke is the one way a type-ahead becomes slower than the round-trip it replaced.
    var EXISTING_MAX_ROWS = 40;

    function normSku(s) { return (s == null ? '' : String(s)).trim().toLowerCase(); }

    /**
     * (Re)load the index and repaint the panel. On failure the state is 'failed', NOT an empty list: an empty
     * list renders as "nothing is registered yet", which is the opposite of the truth and exactly the
     * reassurance the operator must not be given. The old refreshSkuIndex() had the same hole silently — a
     * failed GET left skuIndex empty and every duplicate SKU then passed the client check.
     */
    function refreshProductIndex() {
        $.get(serverContext + 'getUserProduct?includeInactive=true', function (resp) {
            productIndex = (resp && resp.collection) ? resp.collection : [];
            indexState = 'loaded';
            skuIndex = {};
            productIndex.forEach(function (p) {
                var k = normSku(p.sku);
                if (k) skuIndex[k] = String(p.id);
            });
            renderExisting();
            // The manufacturer options ARE this index, so they are rebuilt whenever it is — keeping the
            // currently selected value, which matters while a product is open for editing.
            loadManufacturers($('#prodManufacturer').val());
        }, 'json').fail(function () {
            productIndex = [];
            indexState = 'failed';
            skuIndex = {};
            renderExisting();
            // Guard 2: keep whatever the form already holds rather than rendering an empty picker.
            loadManufacturers($('#prodManufacturer').val());
        });
    }

    // Returns true when `sku` is a non-empty duplicate of a DIFFERENT product than the one being edited.
    function isDuplicateSku(sku, currentId) {
        var k = normSku(sku);
        if (!k) return false;                       // SKU is optional — blank never blocks on the client
        var owner = skuIndex[k];
        return owner != null && owner !== String(currentId || '');
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

    /** Rows worth showing: everything when nothing is typed, else anything matching ANY term. The product
     *  being edited is never offered against itself. */
    function existingMatches() {
        var terms = existingTerms();
        var editingId = String($('#productId').val() || '');
        return productIndex.filter(function (p) {
            if (editingId && String(p.id) === editingId) return false;
            if (!terms.length) return true;
            var hay = [p.name, p.sku, p.barcode, p.manufacturer]
                .map(function (v) { return v == null ? '' : String(v); }).join(' ').toLowerCase();
            return terms.some(function (term) { return hay.indexOf(term) >= 0; });
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

        if (indexState === 'failed') {
            $list.empty();
            $count.text('');
            $msg.text(t('ui.js.couldNotLoadTheRegisteredProducts')).removeClass('text-muted').addClass('text-danger').show();
            return;
        }
        // Not fetched yet (the form is painted before the response lands). Say NOTHING — an empty list here
        // would read as "nothing is registered", which is the same false all-clear the failure branch avoids.
        if (indexState !== 'loaded') {
            $list.empty();
            $count.text('');
            $msg.hide();
            return;
        }
        $msg.removeClass('text-danger').addClass('text-muted');

        var matches = existingMatches();
        $count.text(t('ui.js.nRegistered', productIndex.length));

        if (!productIndex.length) {
            $list.empty();
            $msg.text(t('ui.js.noProductsRegisteredYet')).show();
            return;
        }
        if (!matches.length) {
            $list.empty();
            $msg.text(t('ui.js.noRegisteredProductMatches')).show();
            return;
        }

        var shown = matches.slice(0, EXISTING_MAX_ROWS);
        $list.html(shown.map(existingRowHtml).join(''));
        applyFlag();                                     // repaint dropped the highlight — put it back
        if (matches.length > shown.length) {
            $msg.text(t('ui.js.showingFirstNKeepTyping', shown.length, matches.length)).show();
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
        refreshProductIndex();   // keeps the duplicate-SKU check and the "already registered" panel current
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
    global.newProduct = function () {
        resetProductForm();
        loadCategories();
        loadTaxCodes('');        // multi-rate tax: fresh dropdown (defaults to "Custom rate…")
        loadManufacturers('');   // draw from the CURRENT index immediately; refresh below repaints it
        refreshProductIndex();   // re-read the catalogue each time the form opens, then paint the panel
        $('#ProductModalTitle').text('New Product');
        openModal('ProductModal');
    };

    function resetProductForm() {
        var f = document.getElementById('Product');
        if (f) f.reset();
        $('#productId').val('');
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
        markExistingRow(null);
        renderExisting();        // cleared fields → the panel goes back to showing everything
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
     * The "list of registered manufacturers" is simply the distinct values across `productIndex` — which is
     * already fetched for the duplicate-name panel, so this costs no extra request.
     *
     * ⚠ TWO WAYS A <select> SILENTLY CORRUPTS DATA, both guarded here:
     *  1. Editing a product whose manufacturer is NOT among the options — `.val(x)` fails quietly, the select
     *     falls back to its first option, and saving would CHANGE the manufacturer without the user touching
     *     it. So `selected` is injected as an option when it is missing.
     *  2. The index failed to load — rendering an empty picker would blank the field on the next save. In
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
        productIndex.forEach(function (p) {
            var v = (p.manufacturer == null) ? '' : String(p.manufacturer).trim();
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
                } else { showFormError((resp && resp.message) || 'Could not add the category.'); }
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

    // Add opening stock for a product — feeds the inventory the storefront/POS reservation saga draws down.
    global.addProductStock = function (productId) {
        var qty = s2n($('#addstk_' + productId).val());
        if (qty <= 0) { showFormError(t('ui.js.enterAQuantityGreaterThan0To')); return; }
        var $btn = $('#addstkbtn_' + productId).prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'addProductStock', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId: productId, quantity: qty }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t('ui.js.added') + qty + ' to stock.');
                    $('#addstk_' + productId).val('');
                    refreshStock(productId);
                } else { showFormError((resp && resp.message) || 'Could not add stock.'); }
            },
            error: function () { showFormError(t('ui.js.couldNotAddStock')); },
            complete: function () { $btn.prop('disabled', false); }
        });
    };

    // Correct on-hand — reduce (a mistaken over-add) by the entered quantity. Uses inventory's audited DECREASE
    // adjustment, which refuses to go below zero ("Insufficient stock"). Pass 'INCREASE' to add via the same path.
    global.adjustProductStock = function (productId, type) {
        var qty = s2n($('#addstk_' + productId).val());
        if (qty <= 0) { showFormError(t('ui.js.enterAQuantityToCorrectTheOn')); return; }
        var t = type || 'DECREASE';
        var $btn = $('#lessstkbtn_' + productId).prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'adjustProductStock', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId: productId, adjustmentType: t, quantity: qty, reason: 'Manual stock correction' }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess((t === 'DECREASE' ? 'Removed ' : 'Added ') + qty + (t === 'DECREASE' ? ' from' : ' to') + ' stock.');
                    $('#addstk_' + productId).val('');
                    refreshStock(productId);
                } else { showFormError((resp && resp.message) || 'Could not correct stock (not enough on hand?).'); }
            },
            error: function () { showFormError(t('ui.js.couldNotCorrectStock')); },
            complete: function () { $btn.prop('disabled', false); }
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
            $('#productId').val(p.id);
            $('#prodName').val(p.name || '');
            $('#prodSku').val(p.sku || '');
            $('#prodBarcode').val(p.barcode || '');
            $('#prodPrice').val(p.sellingPrice != null ? p.sellingPrice : '');
            $('#prodTax').val(p.taxRate != null ? p.taxRate : '');
            loadTaxCodes(p.taxCodeId != null ? p.taxCodeId : '');
            $('#prodUnit').val(p.unit || '');
            // Select by category id (dropdown). Reload the list first so the product's category option is present.
            loadCategories(p.categoryId != null ? p.categoryId : '');
            // Pass the value INTO the loader rather than setting it afterwards: the option may not exist yet
            // (a manufacturer used only by this product, or an index that failed to load), and a bare .val()
            // on a missing option silently selects the first one — which would rewrite the field on save.
            loadManufacturers(p.manufacturer || '');
            $('#prodDesc').val(p.description || '');
            $('#ProductModalTitle').text('Edit Product');
            formEpoch++;               // this is a different product now — drop any in-flight name check
            refreshProductIndex();     // refresh the index so the checks + panel exclude only THIS product
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
    var productAddAnother = false;   // which button submitted (consumed once, in saveProduct's success)
    var productSavedCount = 0;       // products catalogued into the run currently open

    /** Clear ONLY what identifies this product; leave the batch context selected. */
    function resetProductIdentityFields() {
        $('#productId').val('');
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
        if (typeof refreshProductIndex === 'function') refreshProductIndex();   // keep the dup-SKU check current

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
            categoryId: $('#prodCategory').val() ? Number($('#prodCategory').val()) : null,
            manufacturer: $('#prodManufacturer').val(), description: $('#prodDesc').val()
        };
        var url = 'addProduct';
        if (id) { body.id = Number(id); url = 'updateProduct'; }
        $.ajax({
            type: 'POST', url: serverContext + url, contentType: 'application/json', dataType: 'json',
            data: JSON.stringify(body),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(id ? 'Product updated.' : 'Product saved.');
                    // Consume the intent exactly once, whatever we decide below, so it can never leak
                    // into a later save (the same rule afterSavePurchase follows).
                    var addAnother = productAddAnother;
                    productAddAnother = false;

                    if (addAnother) {
                        keepCataloguing();
                        return;
                    }
                    resetProductForm();
                    closeModal('ProductModal');
                    loadDataTable();
                    if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
                } else { showFormError((resp && resp.message) || 'Could not save the product.'); }
            },
            error: function () { showFormError(t('ui.js.couldNotSaveTheProduct')); }
        });
    };

    // Delete = deactivate the checked products (they drop off the active list, stay intact for history).
    // `preselectedIds` comes from the shared bulk-delete path (main.js performBulkDelete → bulkDeleteProduct),
    // which has already collected and confirmed them; called with no argument it reads the checkboxes itself.
    global.deactivateProducts = function (preselectedIds) {
        var ids = preselectedIds
            || $("#tableProduct input[type='checkbox']:checked").map(function () { return this.value; }).get().join(',');
        if (!ids) { showFormError(t('ui.js.selectAtLeastOneProductToRemove')); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'deactivateProduct', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ checked: ids }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess(t('ui.js.productSRemoved')); resetProductForm(); loadDataTable();
                    if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
                } else { showFormError((resp && resp.message) || 'Could not remove the product(s).'); }
            },
            error: function () { showFormError(t('ui.js.couldNotRemoveTheProductS')); }
        });
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
                else { showFormError((resp && resp.message) || 'Could not reactivate the product.'); }
            },
            error: function () { showFormError(t('ui.js.couldNotReactivateTheProduct')); }
        });
    };

    // Instant "unique check during entry": flag a duplicate SKU as soon as the user leaves the field,
    // and clear the flag while they retype. Delegated so it works with the modal form present at load.
    $(function () {
        $(document).on('blur', '#prodSku', function () {
            var v = $(this).val();
            if (isDuplicateSku(v, $('#productId').val())) {
                $(this).addClass('alert-danger');
                showFormError(t('ui.js.sku') + v.trim() + '" is already used by another product. Enter a unique SKU.');
            } else {
                $(this).removeClass('alert-danger');
            }
        });
        $(document).on('input', '#prodSku', function () { $(this).removeClass('alert-danger'); });

        // Typing in any of the three identifying fields narrows the panel.
        $(document).on('input', '#prodName, #prodSku, #prodBarcode', function () { renderExisting(); });

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
})(window);
