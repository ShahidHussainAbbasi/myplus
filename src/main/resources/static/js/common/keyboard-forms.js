/**
 * keyboard-forms.js — P7.2: Enter-chain for every registration modal, by convention.
 *
 * The dashboards carry 21 CRUD modals. Writing a binding for each would mean 21 near-identical blocks
 * and 21 field lists to keep in step with 21 forms — which is the exact mistake P7.1 removed. So this
 * binds ALL of them from the convention they already follow:
 *
 *     <div id="<Entity>Modal" class="crud-overlay">   ... fields ...   <button id="add<Entity>">
 *
 * A modal that does not fit says so in its own markup — no code here knows any entity's name:
 *
 *     data-kbd-custom              this modal owns its chain (the purchase form, which has its own
 *                                  Save-&-Add-Another end behaviour)
 *     data-kbd-submit="#selector"  the submit control, when it is not #add<Entity>
 *
 * The CONTAINER is the modal element, not the <form>. Two of the business dialogs (Receive Payment,
 * Pay Vendor) have no <form> at all — they are hand-built dialogs posting through their own function.
 * Deriving from the modal covers both shapes with one rule, and the fields are the same either way.
 *
 * Nothing is auto-included: a dashboard opts in by loading this file. That keeps a rollout one script
 * tag per module rather than a flag day across four.
 */
(function (global, $) {
    'use strict';

    /** Master switch. Absent (a config-read hiccup, an older service) means ON — the keyboard is
     *  additive, and losing it silently is a worse failure than a tenant who wanted it off keeping it. */
    function navEnabled() { return global.kbdFormNavEnabled !== false; }

    /** Whether Enter past the last field submits. OFF still leaves Ctrl+Enter, so a form is never
     *  un-submittable from the keyboard — it only stops a stray Enter from saving a half-typed record. */
    function enterSubmits() { return global.kbdEnterSubmits !== false; }

    function isOpen(id) { return $('#' + id).hasClass('open'); }

    function submitControl(el, entity) {
        var sel = el.getAttribute('data-kbd-submit') || ('#add' + entity);
        var $btn = $(sel);
        return $btn.length ? $btn.first() : null;
    }

    function bindModal(el) {
        var id = el.id;
        if (!id || !/Modal$/.test(id)) return;
        if (el.hasAttribute('data-kbd-custom')) return;      // owns its own chain

        var entity = id.replace(/Modal$/, '');

        function submit() {
            // Resolved at press time, not at bind time: a button can be re-rendered, and a modal that
            // is not open has no business submitting anything.
            if (!isOpen(id)) return false;
            var $btn = submitControl(el, entity);
            if (!$btn || $btn.prop('disabled')) return false;
            $btn.click();
            return true;
        }

        global.EnterChain.bind('modal:' + id, {
            container: '#' + id,
            active: function () { return navEnabled() && isOpen(id); },
            // Enter past the last field. Returning TRUE means handled; returning false leaves the
            // cursor where it is, which is the honest outcome when the tenant turned submission off.
            onEnd: function () { return enterSubmits() ? submit() : true; },
            onCtrlEnter: submit,
            onEscape: function () {
                if (typeof global.closeModal === 'function') { global.closeModal(id); return true; }
                return false;
            }
        });
    }

    $(function () {
        Array.prototype.forEach.call(
            document.querySelectorAll('.crud-overlay[id$="Modal"]'), bindModal);
    });

    // Exposed for the gate: lets a test assert WHICH modals were wired without driving each one.
    global.KeyboardForms = {
        boundModals: function () {
            var out = [];
            Array.prototype.forEach.call(
                document.querySelectorAll('.crud-overlay[id$="Modal"]'), function (el) {
                    if (!el.hasAttribute('data-kbd-custom')) out.push(el.id);
                });
            return out;
        }
    };
})(window, jQuery);
