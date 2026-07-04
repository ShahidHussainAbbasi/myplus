/*
 * crud-modal.js — reusable modal + bulk-select layer for the register DataTables (pilot: Company).
 *
 * Opt-in per screen: give a screen a modal overlay #<Entity>Modal (wrapping its existing #<Entity> form), a
 * toolbar "+ New" button (onclick="newEntity('<Entity>')"), a bulk-action bar #bulkBar<Entity>, and keep a
 * hidden #delete<Entity> button (bound by the generic machinery). main.js activates the modal behaviour only
 * when #<tableV>Modal exists, so screens without a modal (Sell/Purchase/…) are unchanged.
 *
 * The delete flow REUSES the existing generic delete: the confirm dialog just clicks #delete<Entity>, which
 * collects the checked ids + posts delete<Entity> + reloads (same as before) — so no duplicate delete logic.
 */
(function (global) {
    'use strict';

    global.openModal  = function (id) { $('#' + id).addClass('open'); };
    global.closeModal = function (id) { $('#' + id).removeClass('open'); };

    // Toolbar "+ New": open the entity modal for a fresh record.
    global.newEntity = function (entity) {
        if (typeof resetForm === 'function') resetForm();
        $('#' + entity + 'ModalTitle').text('New ' + entity);
        openModal(entity + 'Modal');
    };

    // Called by main.js AFTER it has populated the form for editing (row-click).
    global.openCrudModal = function (entity) {
        $('#' + entity + 'ModalTitle').text('Edit ' + entity);
        openModal(entity + 'Modal');
    };
    global.closeCrudModal = function (entity) { closeModal(entity + 'Modal'); };

    // Count checked rows in #table<Entity> → show/update the contextual bulk-action bar.
    global.refreshBulkBar = function (entity) {
        var $bar = $('#bulkBar' + entity);
        if (!$bar.length) return;
        var n = $("#table" + entity + " input[type='checkbox']:checked").length;
        $bar.find('.bulk-count').text(n);
        $bar.toggle(n > 0);
    };

    global.clearSelection = function (entity) {
        $("#table" + entity + " input[type='checkbox']:checked").prop('checked', false);
        refreshBulkBar(entity);
    };

    // Bulk Delete → open the shared confirm modal listing the selection; confirm reuses #delete<Entity>.
    global.confirmBulkDelete = function (entity) {
        var $checked = $("#table" + entity + " input[type='checkbox']:checked");
        if (!$checked.length) return;
        var names = $checked.map(function () {
            // first visible data column after the checkbox (name) — id column is hidden, so td:eq(1)
            return $(this).closest('tr').find('td:eq(1)').text().trim();
        }).get();
        $('#confirmDeleteCount').text(names.length);
        var $list = $('#confirmDeleteList').empty();
        names.forEach(function (nm) { $list.append($('<li>').text(nm || '(record)')); });
        $('#confirmDeleteYes').off('click').on('click', function () {
            $('#delete' + entity).click();          // reuse the generic delete (ids + callAjax + reload)
            closeModal('confirmDeleteModal');
        });
        openModal('confirmDeleteModal');
    };
})(window);
