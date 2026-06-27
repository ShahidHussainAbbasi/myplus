/*
 * Quarantine register (slice 58, P11 follow-on) — lists returned, non-sellable lots (restockable=false in
 * inventory) and lets an operator dispose them (destroyed / returned to supplier). Product names resolved from
 * the catalog list. Reuses /quarantineList + /disposeQuarantine monolith proxies.
 */
(function (global) {
    'use strict';

    var nameById = {};

    global.showQuarantine = function () {
        $('.formDiv').hide();
        $('#QuarantineDiv').show();
        loadProductNames(loadQuarantine);
    };

    function loadProductNames(cb) {
        $.get(serverContext + 'catalogProducts?size=1000', function (resp) {
            var list = (resp && resp.data && resp.data.content) ? resp.data.content : [];
            nameById = {};
            list.forEach(function (p) { nameById[p.id] = p.name; });
        }).always(function () { if (cb) cb(); });
    }

    function loadQuarantine() {
        $.get(serverContext + 'quarantineList', function (resp) {
            var items = (resp && resp.items) ? resp.items : [];
            var $b = $('#quarantineBody').empty();
            $('#quarantineEmpty').toggle(items.length === 0);
            items.forEach(function (q) {
                var tr = $('<tr>');
                tr.append($('<td>').text(nameById[q.productId] || ('#' + q.productId)));
                tr.append($('<td>').text(q.batchNo || ''));
                tr.append($('<td>').text(q.expiryDate || ''));
                tr.append($('<td>').text(q.quantity != null ? q.quantity : ''));
                var btn = $('<button>').attr({ type: 'button', id: 'disp_' + q.id }).addClass('btn btn-xs btn-danger')
                    .html('<span class="glyphicon glyphicon-trash"></span> Dispose')
                    .on('click', function () { disposeQuarantineLot(q.id); });
                tr.append($('<td>').append(btn));
                $b.append(tr);
            });
        }).fail(function () { showFormError('Could not load the quarantine register.'); });
    }
    global.loadQuarantine = loadQuarantine;

    global.disposeQuarantineLot = function (id) {
        if (!confirm('Dispose this quarantined lot? It will be removed from inventory.')) return;
        $.ajax({
            type: 'POST', url: serverContext + 'disposeQuarantine', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ id: id }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess('Lot disposed.'); loadQuarantine(); }
                else showFormError((resp && resp.message) || 'Could not dispose the lot.');
            },
            error: function () { showFormError('Could not dispose the lot.'); }
        });
    };
})(window);
