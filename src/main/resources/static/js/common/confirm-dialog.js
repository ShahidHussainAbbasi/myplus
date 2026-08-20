/**
 * Shared confirmation dialog — the app-themed replacement for window.confirm/prompt/alert.
 *
 * The native dialogs are unstyled, say "localhost:8080 says", can't carry a reason field, and made a void
 * take TWO stacked browser popups. This is one component for the whole app (DRY: never re-implement it in a
 * module file), promise-based so call sites read like the old synchronous ones:
 *
 *     uiConfirm({ title: 'Delete 3 records?', message: '…', tone: 'danger' })
 *       .then(function (ok) { if (!ok) return; …proceed… });
 *
 *     // confirm + a reason in ONE dialog (replaces confirm() followed by prompt())
 *     uiPromptConfirm({ title: 'Void invoice 1042?', input: { label: 'Reason (optional)' } })
 *       .then(function (reason) { if (reason === null) return; …proceed with reason… });
 *
 *     uiAlert({ title: 'Void failed', message: resp.message, tone: 'danger' });
 *
 * Accessible: role=dialog + aria-modal, focus moves in and is trapped, ESC cancels, Enter confirms, focus
 * returns to whatever opened it. Text is inserted with textContent, never HTML, so a record name in a message
 * can't inject markup (see /js/common/dom-safe.js for the same rule elsewhere).
 */
(function (global) {
    'use strict';

    var TONES = {
        primary: { from: '#0D3B8C', to: '#1565C0', solid: '#1565C0', ring: 'rgba(21,101,192,.28)', icon: '?' },
        danger:  { from: '#9F1239', to: '#DC2626', solid: '#DC2626', ring: 'rgba(220,38,38,.28)',  icon: '!' },
        warning: { from: '#B45309', to: '#F59E0B', solid: '#D97706', ring: 'rgba(217,119,6,.28)',  icon: '!' }
    };

    var STYLE_ID = 'uiConfirmStyles';

    function injectStyles() {
        if (document.getElementById(STYLE_ID)) return;
        var css =
            '.uiC-backdrop{position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;' +
            'padding:16px;background:rgba(15,23,42,.55);-webkit-backdrop-filter:blur(3px);backdrop-filter:blur(3px);' +
            'opacity:0;transition:opacity .16s ease}' +
            '.uiC-backdrop.is-open{opacity:1}' +
            '.uiC-card{width:100%;max-width:460px;background:#fff;border-radius:16px;overflow:hidden;' +
            'box-shadow:0 24px 64px rgba(2,6,23,.32);font-family:inherit;transform:translateY(8px) scale(.98);' +
            'transition:transform .16s ease}' +
            '.uiC-backdrop.is-open .uiC-card{transform:none}' +
            '.uiC-head{display:flex;align-items:center;gap:12px;padding:18px 22px;color:#fff}' +
            '.uiC-badge{flex:0 0 auto;width:34px;height:34px;border-radius:50%;background:rgba(255,255,255,.2);' +
            'display:flex;align-items:center;justify-content:center;font-size:19px;font-weight:700;line-height:1}' +
            '.uiC-title{margin:0;font-size:17px;font-weight:700;letter-spacing:.2px}' +
            '.uiC-body{padding:20px 22px 4px;color:#334155;font-size:14.5px;line-height:1.6;white-space:pre-line}' +
            '.uiC-field{padding:14px 22px 0}' +
            '.uiC-label{display:block;margin:0 0 6px;font-size:12.5px;font-weight:600;color:#475569;text-transform:none}' +
            '.uiC-input{width:100%;padding:9px 12px;border:1px solid #cbd5e1;border-radius:9px;font-size:14px;' +
            'font-family:inherit;color:#0f172a;background:#fff;outline:none;transition:border-color .12s,box-shadow .12s}' +
            '.uiC-input:focus{border-color:#1565C0;box-shadow:0 0 0 3px rgba(21,101,192,.18)}' +
            '.uiC-err{margin:6px 0 0;font-size:12.5px;color:#DC2626;min-height:1px}' +
            '.uiC-foot{display:flex;gap:10px;justify-content:flex-end;padding:20px 22px 22px}' +
            '.uiC-btn{padding:9px 18px;border-radius:9px;font-size:14px;font-weight:600;cursor:pointer;' +
            'border:1px solid transparent;font-family:inherit;transition:filter .12s,box-shadow .12s}' +
            '.uiC-btn:focus-visible{outline:none;box-shadow:0 0 0 3px rgba(21,101,192,.35)}' +
            '.uiC-cancel{background:#fff;border-color:#cbd5e1;color:#334155}' +
            '.uiC-cancel:hover{background:#f8fafc}' +
            '.uiC-ok{color:#fff}.uiC-ok:hover{filter:brightness(1.07)}' +
            '@media (max-width:420px){.uiC-foot{flex-direction:column-reverse}.uiC-btn{width:100%}}' +
            '@media (prefers-reduced-motion:reduce){.uiC-backdrop,.uiC-card{transition:none}}';
        var el = document.createElement('style');
        el.id = STYLE_ID;
        el.appendChild(document.createTextNode(css));
        document.head.appendChild(el);
    }

    /**
     * The one implementation behind uiConfirm/uiPromptConfirm/uiAlert.
     * Resolves: false (cancelled) | true (confirmed) | the entered string when `input` is configured.
     */
    function open(opts) {
        opts = opts || {};
        injectStyles();

        var tone = TONES[opts.tone] || TONES.primary;
        var hasInput = !!opts.input;
        var alertOnly = !!opts.alertOnly;
        var opener = document.activeElement;

        var backdrop = document.createElement('div');
        backdrop.className = 'uiC-backdrop';

        var card = document.createElement('div');
        card.className = 'uiC-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');
        card.setAttribute('aria-labelledby', 'uiC-title');

        var head = document.createElement('div');
        head.className = 'uiC-head';
        head.style.background = 'linear-gradient(135deg,' + tone.from + ',' + tone.to + ')';
        var badge = document.createElement('div');
        badge.className = 'uiC-badge';
        badge.textContent = opts.icon || tone.icon;
        badge.setAttribute('aria-hidden', 'true');
        var title = document.createElement('h3');
        title.className = 'uiC-title';
        title.id = 'uiC-title';
        title.textContent = opts.title || 'Please confirm';
        head.appendChild(badge);
        head.appendChild(title);
        card.appendChild(head);

        if (opts.message) {
            var body = document.createElement('div');
            body.className = 'uiC-body';
            body.textContent = opts.message;          // textContent, never innerHTML
            card.appendChild(body);
        }

        var input = null, err = null;
        if (hasInput) {
            var field = document.createElement('div');
            field.className = 'uiC-field';
            if (opts.input.label) {
                var lbl = document.createElement('label');
                lbl.className = 'uiC-label';
                lbl.setAttribute('for', 'uiC-input');
                lbl.textContent = opts.input.label;
                field.appendChild(lbl);
            }
            input = document.createElement(opts.input.multiline ? 'textarea' : 'input');
            input.className = 'uiC-input';
            input.id = 'uiC-input';
            if (opts.input.multiline) input.rows = 3;
            if (opts.input.placeholder) input.placeholder = opts.input.placeholder;
            if (opts.input.value) input.value = opts.input.value;
            if (opts.input.maxlength) input.maxLength = opts.input.maxlength;
            field.appendChild(input);
            err = document.createElement('p');
            err.className = 'uiC-err';
            field.appendChild(err);
            card.appendChild(field);
        }

        var foot = document.createElement('div');
        foot.className = 'uiC-foot';
        var cancelBtn = null;
        if (!alertOnly) {
            cancelBtn = document.createElement('button');
            cancelBtn.type = 'button';
            cancelBtn.className = 'uiC-btn uiC-cancel';
            cancelBtn.textContent = opts.cancelText || 'Cancel';
            foot.appendChild(cancelBtn);
        }
        var okBtn = document.createElement('button');
        okBtn.type = 'button';
        okBtn.className = 'uiC-btn uiC-ok';
        okBtn.style.background = tone.solid;
        okBtn.textContent = opts.confirmText || (alertOnly ? 'OK' : 'Confirm');
        okBtn.setAttribute('data-ui-confirm', 'ok');   // stable hook for tests
        foot.appendChild(okBtn);
        card.appendChild(foot);

        backdrop.appendChild(card);
        document.body.appendChild(backdrop);
        // next frame so the transition runs
        requestAnimationFrame(function () { backdrop.classList.add('is-open'); });

        return new Promise(function (resolve) {
            var done = false;

            function close(result) {
                if (done) return;
                done = true;
                document.removeEventListener('keydown', onKey, true);
                backdrop.classList.remove('is-open');
                setTimeout(function () {
                    if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
                    if (opener && typeof opener.focus === 'function') opener.focus();
                }, 160);
                resolve(result);
            }

            function cancel() { close(hasInput ? null : false); }

            function accept() {
                if (hasInput) {
                    var v = input.value.trim();
                    if (opts.input.required && !v) {
                        err.textContent = opts.input.requiredMessage || 'This is required.';
                        input.focus();
                        return;
                    }
                    close(v);
                } else {
                    close(true);
                }
            }

            function onKey(e) {
                if (e.key === 'Escape') { e.preventDefault(); cancel(); return; }
                if (e.key === 'Enter' && !(hasInput && opts.input.multiline && e.target === input)) {
                    e.preventDefault(); accept(); return;
                }
                if (e.key === 'Tab') {                      // keep focus inside the dialog
                    var f = [cancelBtn, input, okBtn].filter(Boolean);
                    var i = f.indexOf(document.activeElement);
                    var next = e.shiftKey ? (i <= 0 ? f.length - 1 : i - 1) : (i === f.length - 1 ? 0 : i + 1);
                    e.preventDefault();
                    f[next].focus();
                }
            }

            okBtn.addEventListener('click', accept);
            if (cancelBtn) cancelBtn.addEventListener('click', cancel);
            backdrop.addEventListener('mousedown', function (e) { if (e.target === backdrop) cancel(); });
            document.addEventListener('keydown', onKey, true);

            // Focus the least destructive control first: the input if there is one, else Cancel.
            (input || cancelBtn || okBtn).focus();
        });
    }

    /**
     * Make the dialog chrome (.uiC-*) available to a module that renders its OWN panel.
     *
     * Exposed because injectStyles() used to be reachable only through open(), so a module that built
     * .uiC-backdrop / .uiC-card markup directly got NO styles unless a confirm dialog happened to have
     * been opened first on that page — and .uiC-backdrop starts at opacity:0, so the result was an
     * invisible panel rather than an ugly one. (Found by the CSV import's preview, slice I1/I2.)
     *
     * Sharing the injector rather than copying the CSS keeps one definition of what a dialog looks like.
     */
    global.uiDialogStyles = injectStyles;

    /** Themed confirm. Resolves true/false. */
    global.uiConfirm = function (opts) {
        var o = opts || {};
        o.input = null;
        return open(o);
    };

    /** Confirm + a single field in ONE dialog. Resolves the string, or null when cancelled. */
    global.uiPromptConfirm = function (opts) {
        var o = opts || {};
        o.input = o.input || { label: 'Reason' };
        return open(o);
    };

    /** Themed alert (single OK). Resolves when dismissed. */
    global.uiAlert = function (opts) {
        var o = typeof opts === 'string' ? { message: opts } : (opts || {});
        o.alertOnly = true;
        o.input = null;
        if (!o.title) o.title = 'Notice';
        return open(o);
    };
})(window);
