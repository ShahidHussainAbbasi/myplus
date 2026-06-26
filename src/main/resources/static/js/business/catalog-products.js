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
                $b.append(tr);
            });
        }).fail(function () { showFormError('Could not load products.'); });
    }
    global.loadProducts = loadProducts;

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
