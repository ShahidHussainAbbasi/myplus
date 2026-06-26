/*
 * Catalog Product master (slice 42, M1) — register/list Products in catalog-service (the single product master
 * shared by POS, pharmacy, e-commerce). Additive: the existing Item screen still works; later phases repoint the
 * sell/purchase pickers + stock to Product, then retire Item. Reuses the existing /catalogProducts (list) and the
 * new /addProduct proxy. catalog returns the common-web ApiResponse envelope; lists are paged (data.content).
 */
(function (global) {
    'use strict';

    function num(v) { var n = Number(v); return isNaN(n) ? 0 : n; }

    global.showProducts = function () {
        $('.formDiv').hide();
        $('#ProductDiv').show();
        loadProducts();
    };

    function loadProducts() {
        $.get(serverContext + 'catalogProducts?size=500', function (resp) {
            var page = (resp && resp.data) ? resp.data : {};
            var list = page.content || [];
            var $b = $('#productBody').empty();
            list.forEach(function (p) {
                var tr = $('<tr>');
                tr.append($('<td>').text(p.name || ''));
                tr.append($('<td>').text(p.sku || ''));
                tr.append($('<td>').text(p.unit || ''));
                tr.append($('<td>').text(p.sellingPrice != null ? Number(p.sellingPrice).toFixed(2) : ''));
                tr.append($('<td>').text(p.taxRate != null ? p.taxRate : ''));
                tr.append($('<td>').text(p.categoryName || ''));
                // On hand (lazy-loaded) + add-stock control. Inventory the storefront/POS saga draws down (slice 50).
                tr.append($('<td>').attr('id', 'stk_' + p.id).addClass('prod-onhand').text('…'));
                var addCell = $('<td>');
                $('<input>').attr({ type: 'number', min: '0', step: 'any', id: 'addstk_' + p.id })
                    .addClass('form-control input-sm prod-addstk').css({ width: '90px', display: 'inline-block' })
                    .appendTo(addCell);
                $('<button>').attr({ type: 'button', id: 'addstkbtn_' + p.id }).addClass('btn btn-xs btn-success')
                    .css('margin-left', '4px').html('<span class="glyphicon glyphicon-plus"></span> Add')
                    .on('click', function () { addProductStock(p.id); })
                    .appendTo(addCell);
                tr.append(addCell);
                $b.append(tr);
                refreshStock(p.id);
            });
        }).fail(function () { showFormError('Could not load products.'); });
    }
    global.loadProducts = loadProducts;

    // Read a product's current on-hand from inventory and show it in its row.
    function refreshStock(productId) {
        $.get(serverContext + 'productStock?productId=' + productId, function (resp) {
            var v = (resp && resp.success) ? Number(resp.stock) : NaN;
            $('#stk_' + productId).text(isNaN(v) ? '0' : v);
        }).fail(function () { $('#stk_' + productId).text('—'); });
    }
    global.refreshStock = refreshStock;

    // Add opening stock for a product (slice 50) — feeds the inventory the storefront/POS reservation saga draws down.
    global.addProductStock = function (productId) {
        var qty = num($('#addstk_' + productId).val());
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

    global.saveProduct = function () {
        if (!$('#prodName').val().trim()) { showFormError('Product name is required.'); return; }
        $.ajax({
            type: 'POST', url: serverContext + 'addProduct', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({
                name: $('#prodName').val().trim(), sku: $('#prodSku').val(),
                sellingPrice: num($('#prodPrice').val()), taxRate: num($('#prodTax').val()),
                unit: $('#prodUnit').val(), categoryName: $('#prodCategory').val(),
                manufacturer: $('#prodManufacturer').val(), description: $('#prodDesc').val()
            }),
            success: function (resp) {
                if (resp && resp.success) {
                    showSaleSuccess('Product saved.');
                    $('#Product')[0].reset();
                    loadProducts();
                } else { showFormError((resp && resp.message) || 'Could not save the product.'); }
            },
            error: function () { showFormError('Could not save the product.'); }
        });
    };
})(window);
