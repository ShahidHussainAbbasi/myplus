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

    global.openModal  = function (id) {
        $('#' + id).addClass('open');
        // Land the cursor in the first field so a record can be typed straight away — on a tall form the modal
        // opens with its inputs below the fold otherwise. Desktop only (focus-flow skips touch/narrow screens).
        if (typeof focusFirstField === 'function') {
            window.requestAnimationFrame(function () { focusFirstField(document.getElementById(id)); });
        }
    };
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

    /**
     * Bulk Delete → the app-wide confirm dialog (uiConfirm), listing what is about to go. This used to be a
     * bespoke #confirmDeleteModal in each dashboard's HTML, which then clicked #delete<Entity> — whose handler
     * raised a SECOND, native confirm(). Now there is one confirmation, one look, and one delete implementation
     * (main.js performBulkDelete).
     */
    global.confirmBulkDelete = function (entity) {
        var $checked = $("#table" + entity + " input[type='checkbox']:checked");
        if (!$checked.length) return;
        var names = $checked.map(function () {
            // first visible data column after the checkbox (name) — id column is hidden, so td:eq(1)
            return $(this).closest('tr').find('td:eq(1)').text().trim() || '(record)';
        }).get();
        var ids = $checked.map(function () { return this.value; }).get().join(',');

        var shown = names.slice(0, 8).map(function (n) { return '•  ' + n; }).join('\n');
        if (names.length > 8) shown += '\n…and ' + (names.length - 8) + ' more';

        uiConfirm({
            title: names.length === 1 ? 'Delete this record?' : 'Delete ' + names.length + ' records?',
            message: shown + '\n\nThis cannot be undone.',
            confirmText: names.length === 1 ? 'Delete' : 'Delete ' + names.length,
            tone: 'danger'
        }).then(function (ok) {
            if (ok) performBulkDelete(entity, ids);
        });
    };
})(window);
