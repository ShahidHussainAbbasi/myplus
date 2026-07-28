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
        }).fail(function () { showFormError(t('ui.js.couldNotLoadTheQuarantineRegister')); });
    }
    global.loadQuarantine = loadQuarantine;

    global.disposeQuarantineLot = function (id) {
        uiConfirm({
            title: t('ui.js.disposeThisQuarantinedLot'),
            message: t('ui.js.theLotIsWrittenOffAndRemoved'),
            confirmText: t('ui.js.disposeLot'),
            tone: 'danger'
        }).then(function (ok) {
            if (!ok) return;
            disposeQuarantineLotNow(id);
        });
    };

    function disposeQuarantineLotNow(id) {
        $.ajax({
            type: 'POST', url: serverContext + 'disposeQuarantine', contentType: 'application/json', dataType: 'json',
            data: JSON.stringify({ id: id }),
            success: function (resp) {
                if (resp && resp.success) { showSaleSuccess(t('ui.js.lotDisposed')); loadQuarantine(); }
                else showFormError((resp && resp.message) || 'Could not dispose the lot.');
            },
            error: function () { showFormError(t('ui.js.couldNotDisposeTheLot')); }
        });
    };
})(window);
