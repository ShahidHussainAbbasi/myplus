/*
 * Catalog Product master (slice 42, M1; UI-parity refactor) — register/list/edit Products in catalog-service
 * (the single product master shared by POS, pharmacy, e-commerce). The list now renders through the SHARED
 * loadDataTable() DataTable path (same as Customer): sort/search/paging/export, a hidden id + checkbox column,
 * row-click edit, and a Delete that DEACTIVATES (products referenced by sales/inventory stay intact, just drop
 * off the list). Per-row Add-stock (addstkbtn_<id>) is preserved. Proxies: /getUserProduct (list, {collection}),
 * /addProduct, /updateProduct, /deactivateProduct, /addProductStock, /productStock, /getCatalogProduct.
 */
(function (global) {
    'use strict';

    // Numeric coercion uses the shared s2n() from main.js (was a duplicate local s2n()).

    // ── Client-side SKU uniqueness ──────────────────────────────────────────────
    // catalog-service enforces a unique SKU per org (a duplicate → 409 "Product SKU already exists").
    // Mirror that on the client so the user gets instant feedback while typing / before submit, instead of a
    // round-trip. Index maps a normalised (trim + lower-case) SKU → the owning product id (as a string), built
    // from getUserProduct incl. inactive products (a deactivated product still owns its SKU downstream).
    var skuIndex = {};
    function normSku(s) { return (s == null ? '' : String(s)).trim().toLowerCase(); }
    function refreshSkuIndex() {
        $.get(serverContext + 'getUserProduct?includeInactive=true', function (resp) {
            skuIndex = {};
            var list = (resp && resp.collection) ? resp.collection : [];
            list.forEach(function (p) {
                var k = normSku(p.sku);
                if (k) skuIndex[k] = String(p.id);
            });
        });
    }
    // Returns true when `sku` is a non-empty duplicate of a DIFFERENT product than the one being edited.
    function isDuplicateSku(sku, currentId) {
        var k = normSku(sku);
        if (!k) return false;                       // SKU is optional — blank never blocks on the client
        var owner = skuIndex[k];
        return owner != null && owner !== String(currentId || '');
    }

    global.showProducts = function () {
        $('.formDiv').hide();
        $('#ProductDiv').show();
        resetProductForm();
        loadCategories();   // populate the Category dropdown
        // Render #tableProduct through the shared DataTable path (like #tableCustomer).
        tableV = 'Product'; getAll = 'Product'; buttonV = 'Product'; deleteV = 'Product';
        loadDataTable();
        refreshSkuIndex();   // keep the client-side duplicate-SKU check current for this screen
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
        loadTaxCodes('');    // multi-rate tax: fresh dropdown (defaults to "Custom rate…")
        refreshSkuIndex();   // refresh the known SKUs each time the form opens
        $('#ProductModalTitle').text('New Product');
        openModal('ProductModal');
    };

    function resetProductForm() {
        var f = document.getElementById('Product');
        if (f) f.reset();
        $('#productId').val('');
        $('#prodCategory').val('');
        $('#prodCategoryNew').val('');
        $('#prodSku').removeClass('alert-danger');
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

    // Inline quick-add a category, then reload the dropdown with the new one selected.
    global.addCategoryInline = function () {
        var name = $('#prodCategoryNew').val().trim();
        if (!name) { showFormError('Enter a category name.'); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addCategory', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ name: name }),
            success: function (resp) {
                if (resp && resp.success && resp.data) {
                    showSaleSuccess('Category added.');
                    $('#prodCategoryNew').val('');
                    loadCategories(resp.data.id);
                } else { showFormError((resp && resp.message) || 'Could not add the category.'); }
            },
            error: function () { showFormError('Could not add the category.'); }
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
        if (qty <= 0) { showFormError('Enter a quantity greater than 0 to add stock.'); return; }
        var $btn = $('#addstkbtn_' + productId).prop('disabled', true);
        $.ajax({
            type: 'POST', url: serverContext + 'addProductStock', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ productId: productId, quantity: qty }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess('Added ' + qty + ' to stock.');
                    $('#addstk_' + productId).val('');
                    refreshStock(productId);
                } else { showFormError((resp && resp.message) || 'Could not add stock.'); }
            },
            error: function () { showFormError('Could not add stock.'); },
            complete: function () { $btn.prop('disabled', false); }
        });
    };

    // Correct on-hand — reduce (a mistaken over-add) by the entered quantity. Uses inventory's audited DECREASE
    // adjustment, which refuses to go below zero ("Insufficient stock"). Pass 'INCREASE' to add via the same path.
    global.adjustProductStock = function (productId, type) {
        var qty = s2n($('#addstk_' + productId).val());
        if (qty <= 0) { showFormError('Enter a quantity to correct the on-hand by.'); return; }
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
            error: function () { showFormError('Could not correct stock.'); },
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

    // Load a product into the form for editing (row-click).
    function editProduct(id) {
        $.get(serverContext + 'getCatalogProduct?id=' + id, function (resp) {
            var p = (resp && resp.data) ? resp.data : null;
            if (!p) { showFormError('Could not load the product.'); return; }
            $('#productId').val(p.id);
            $('#prodName').val(p.name || '');
            $('#prodSku').val(p.sku || '');
            $('#prodPrice').val(p.sellingPrice != null ? p.sellingPrice : '');
            $('#prodTax').val(p.taxRate != null ? p.taxRate : '');
            loadTaxCodes(p.taxCodeId != null ? p.taxCodeId : '');
            $('#prodUnit').val(p.unit || '');
            // Select by category id (dropdown). Reload the list first so the product's category option is present.
            loadCategories(p.categoryId != null ? p.categoryId : '');
            $('#prodManufacturer').val(p.manufacturer || '');
            $('#prodDesc').val(p.description || '');
            $('#ProductModalTitle').text('Edit Product');
            refreshSkuIndex();      // refresh known SKUs so the duplicate check excludes only THIS product
            openModal('ProductModal');
            updateReadOnly(true);   // make the key fields readonly when editing

        }).fail(function () { showFormError('Could not load the product.'); });
    }
    global.editProduct = editProduct;

    // Submit: add a new product, or update the one being edited (hidden #productId set).
    global.saveProduct = function () {
        if (!$('#prodName').val().trim()) { showFormError('Product name is required.'); return; }
        var id = $('#productId').val();
        // Client-side uniqueness: block a duplicate SKU before the round-trip (server still enforces it).
        var sku = $('#prodSku').val();
        if (isDuplicateSku(sku, id)) {
            $('#prodSku').addClass('alert-danger').focus();
            showFormError('SKU "' + sku.trim() + '" is already used by another product. Enter a unique SKU.');
            return;
        }
        // Multi-rate tax: a chosen code supplies the rate (taxCodeId); "Custom rate…" sends a one-off taxRate instead.
        var codeId = $('#prodTaxCode').val();
        var body = {
            name: $('#prodName').val().trim(), sku: sku,
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
                    resetProductForm();
                    closeModal('ProductModal');
                    loadDataTable();
                    if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
                } else { showFormError((resp && resp.message) || 'Could not save the product.'); }
            },
            error: function () { showFormError('Could not save the product.'); }
        });
    };

    // Delete = deactivate the checked products (they drop off the active list, stay intact for history).
    global.deactivateProducts = function () {
        var ids = $("#tableProduct input[type='checkbox']:checked").map(function () { return this.value; }).get().join(',');
        if (!ids) { showFormError('Select at least one product to remove.'); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'deactivateProduct', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ checked: ids }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess('Product(s) removed.'); resetProductForm(); loadDataTable();
                    if (typeof refreshBulkBar === 'function') refreshBulkBar('Product');
                } else { showFormError((resp && resp.message) || 'Could not remove the product(s).'); }
            },
            error: function () { showFormError('Could not remove the product(s).'); }
        });
    };

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
                if (resp && resp.success) { showSaleSuccess('Product reactivated.'); loadDataTable(); }
                else { showFormError((resp && resp.message) || 'Could not reactivate the product.'); }
            },
            error: function () { showFormError('Could not reactivate the product.'); }
        });
    };

    // Instant "unique check during entry": flag a duplicate SKU as soon as the user leaves the field,
    // and clear the flag while they retype. Delegated so it works with the modal form present at load.
    $(function () {
        $(document).on('blur', '#prodSku', function () {
            var v = $(this).val();
            if (isDuplicateSku(v, $('#productId').val())) {
                $(this).addClass('alert-danger');
                showFormError('SKU "' + v.trim() + '" is already used by another product. Enter a unique SKU.');
            } else {
                $(this).removeClass('alert-danger');
            }
        });
        $(document).on('input', '#prodSku', function () { $(this).removeClass('alert-danger'); });
    });
})(window);
