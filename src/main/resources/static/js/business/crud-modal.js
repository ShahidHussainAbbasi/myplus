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
        if (global.resetAddAnotherCount) global.resetAddAnotherCount(entity);
    };

    /* ============================================================================================
     * "Save & Add Another" for the register screens.
     *
     * THE PROBLEM. Registering a run of records cost one modal open per record, and every field was
     * wiped in between — including the ones that are the SAME for the whole run (a supplier's parent
     * company, a customer's segment and payment terms, a product's brand/category/tax code). The
     * operator re-picked them for every single row.
     *
     * This is the shape P6 already solved for the purchase form; it is generalised here so every
     * register screen on every dashboard gets it from one implementation rather than four copies.
     *
     * KEEP vs CLEAR — the whole risk lives here, and the polarity is deliberate:
     *   • CLEAR is the default. Everything in the modal is cleared unless it is named in `keep`.
     *   • KEEP is an explicit, reviewed list of BATCH CONTEXT.
     * A field added to a form later therefore starts out CLEARED. The opposite default would let a
     * new field silently carry one record's value onto the next, which is the failure that actually
     * costs money (a price, an SKU, a credit limit inherited by the wrong account).
     *
     * Retained values stay VISIBLE and editable on screen — the operator can see exactly what is
     * carrying over, the same contract the purchase header has.
     * ============================================================================================ */

    var addAnotherState = {};   // entity -> { intent, count, keep, focus }

    /** Clear every field in the modal except the record id and the declared batch context. */
    function clearIdentityFields(entity) {
        var st = addAnotherState[entity];
        var idField = entity.charAt(0).toLowerCase() + entity.slice(1) + 'Id';
        var keep = st.keep.concat([idField]);

        $('#' + entity + 'Modal').find('input, select, textarea').each(function () {
            var id = this.id;
            if (!id || keep.indexOf(id) >= 0) return;
            if (this.type === 'button' || this.type === 'submit' || this.type === 'reset') return;
            if (this.type === 'checkbox' || this.type === 'radio') { this.checked = false; return; }
            $(this).val('').removeClass('alert-danger');
        });
        $('#' + idField).val('');   // a saved record must not be re-saved as an edit of itself

        // A bootstrap-select shows a rendered button, not the <select>; without a refresh it keeps
        // displaying the value that was just cleared.
        $('#' + entity + 'Modal').find('select.selectpicker, select').each(function () {
            if ($(this).data('selectpicker')) $(this).selectpicker('refresh');
        });
    }

    /** Reset the run counter — a fresh "+ New" starts a new run. */
    global.resetAddAnotherCount = function (entity) {
        var st = addAnotherState[entity];
        if (!st) return;
        st.count = 0;
        st.intent = false;
        $('#' + entity + 'SaveCount').hide().text('');
    };

    /**
     * Wire "Save & Add Another" for one entity.
     *   entity  'Customer' | 'Vender' | 'Company' | …  (matches #add<Entity>, #<Entity>Modal)
     *   opts.keep   [ids]  batch context that SURVIVES a save
     *   opts.focus  id     where the cursor lands for the next record (default: first kept-out field)
     */
    global.registerAddAnother = function (entity, opts) {
        opts = opts || {};
        addAnotherState[entity] = { intent: false, count: 0, keep: opts.keep || [], focus: opts.focus };

        // DELEGATED on document, deliberately: main.js does `$("#add"+buttonV).off()` every time a
        // section is opened, which would strip a handler bound to the element itself. Same reason
        // business.js delegates #addPurchase.
        $(document).on('click', '#add' + entity + 'Another', function () {
            var idField = entity.charAt(0).toLowerCase() + entity.slice(1) + 'Id';
            // "Add another" is meaningless while EDITING an existing record — there is nothing to add
            // another of — so fall through to the plain save-and-close.
            addAnotherState[entity].intent = !$('#' + idField).val();
            $('#add' + entity).click();   // the SAME validated save path, never a second copy of it
        });

        // A REAL click on the plain save button cancels a stale intent (e.g. a previous "Add another"
        // whose save failed, so the hook never ran to consume it). jQuery's programmatic .click()
        // above carries no originalEvent, so it does not clear the flag it just set.
        $(document).on('click', '#add' + entity, function (e) {
            if (e.originalEvent) addAnotherState[entity].intent = false;
        });

        // The hook main.js calls after a successful save. TRUE = "I handled the reset, the modal and
        // the grid"; anything else keeps the generic register behaviour (wipe + close).
        global['afterSave' + entity] = function () {
            var st = addAnotherState[entity];
            var another = st.intent;
            st.intent = false;                 // consumed exactly once, whatever we decide below
            if (!another) return false;

            clearIdentityFields(entity);

            // Refresh the grid WITHOUT the full rebuild the generic path does: loadDataTable()
            // re-runs the section's dropdown preload, which would repaint the very pickers whose
            // selection this feature exists to keep.
            try { if (typeof datatable !== 'undefined' && datatable) datatable.ajax.reload(null, false); } catch (e) {}
            if (typeof refreshBulkBar === 'function') refreshBulkBar(entity);
            if (typeof clearFormError === 'function') clearFormError();

            st.count++;
            var $c = $('#' + entity + 'SaveCount');
            if ($c.length) {
                var msg = (typeof t === 'function') ? t('ui.js.recordsAddedCount') : '{0} added';
                $c.text(String(msg).replace('{0}', st.count)).show();
            }

            var focusId = st.focus;
            if (!focusId) {
                var $first = $('#' + entity + 'Modal').find('input, select, textarea').filter(function () {
                    return this.id && st.keep.indexOf(this.id) < 0 && this.type !== 'hidden'
                        && this.type !== 'button' && this.type !== 'submit' && this.type !== 'reset';
                }).first();
                focusId = $first.attr('id');
            }
            if (focusId) $('#' + focusId).focus();

            return true;
        };
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

    /* --------------------------------------------------------------------------------------------
     * Which fields are BATCH CONTEXT on the business register screens.
     *
     * The test applied to each field: "would an operator entering twenty of these in a row give the
     * same answer every time?" Classification and parentage pass; anything identifying the individual
     * record, and anything that is a per-account financial grant, does not.
     *
     * Deliberately NOT kept, though it might look like it belongs:
     *   • creditLimit (Customer, Vender) — a limit is granted to ONE account on its own standing.
     *     Carrying it onto the next account silently extends someone else's credit; the segment
     *     (customerType) and the terms are shared, the amount is not.
     *   • customerCnic / licence fields — identity documents, unique by definition.
     * ------------------------------------------------------------------------------------------ */
    $(function () {
        if (!global.registerAddAnother) return;

        // A run of customers is typically one segment on the same terms in the same town.
        global.registerAddAnother('Customer', {
            keep: ['customerType', 'paymentTermsDays', 'customerCity'],
            focus: 'customerName'
        });

        // Suppliers are registered under a parent company — that is the run.
        global.registerAddAnother('Vender', {
            keep: ['venderCompanyDD'],
            focus: 'venderName'
        });

        // Companies share little except the registration date they are entered on.
        global.registerAddAnother('Company', {
            keep: ['companyDated'],
            focus: 'companyName'
        });
    });
})(window);
