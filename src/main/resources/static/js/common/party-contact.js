/**
 * Contact-360 — the cross-module contact view, shared by EVERY vertical (P4c).
 *
 * One person can be a POS customer, a pharmacy patient, a student, a donor and an online shopper at once; the
 * party bridges give them a single partyId and party-service keeps the role index. This renders that: identity
 * + a chip per module role. Reads the monolith proxy /partyRoles?id= (→ party-service GET /parties/{id}/roles),
 * which is owner/admin-gated on both sides — the mere EXISTENCE of a pharmacy PATIENT role is sensitive.
 *
 * Lives in /js/common because four dashboards use it: business (customers), education (students), welfare
 * (donors) and pharmacy (prescriptions). Never copy these functions into a module file.
 *
 *   contact360Button(partyId)   → the row-action HTML ('' when not permitted or not bridged yet)
 *   openContact360(partyId)     → open the panel
 *
 * The name is NOT passed in: the panel shows the name from the party record itself, so no call site has to
 * escape anything into an onclick attribute.
 */
(function (global) {
    'use strict';

    /**
     * Translate, with a readable English fallback.
     *
     * <p>Same {@code tHas}-then-{@code t} shape the other shared modules use: ask whether the bundle has the
     * key before looking it up, so a missing key shows English rather than rendering the key itself at the
     * user. This file's user-visible strings were hardcoded, so the panel stayed English in all six
     * languages.
     */
    function msg(key, fallback) {
        if (typeof global.tHas === 'function' && typeof global.t === 'function' && global.tHas(key)) {
            return global.t(key);
        }
        return fallback;
    }


    var MODULES = {
        business:    { label: 'Point of Sale', color: '#1565C0' },
        education:   { label: 'Education',     color: '#2E7D32' },
        welfare:     { label: 'Welfare',       color: '#0277BD' },
        pharma:      { label: 'Pharmacy',      color: '#B45309' },
        marketplace: { label: 'Online Store',  color: '#AD1457' }
    };

    var STYLE_ID = 'uiC360Styles';

    function injectStyles() {
        if (document.getElementById(STYLE_ID)) return;
        var css =
            '.c360-backdrop{position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;' +
            'padding:16px;background:rgba(15,23,42,.55);-webkit-backdrop-filter:blur(3px);backdrop-filter:blur(3px)}' +
            '.c360-card{width:100%;max-width:540px;max-height:86vh;overflow:auto;background:#fff;border-radius:16px;' +
            'box-shadow:0 24px 64px rgba(2,6,23,.32)}' +
            '.c360-head{display:flex;align-items:center;gap:12px;padding:18px 22px;color:#fff;' +
            'background:linear-gradient(135deg,#0D3B8C,#1565C0)}' +
            '.c360-head h3{margin:0;font-size:17px;font-weight:700;flex:1}' +
            '.c360-x{background:rgba(255,255,255,.18);border:1px solid rgba(255,255,255,.4);color:#fff;border-radius:8px;' +
            'width:30px;height:30px;font-size:17px;line-height:1;cursor:pointer}' +
            '.c360-body{padding:18px 22px 22px}' +
            '.c360-id{display:grid;grid-template-columns:92px 1fr;gap:6px 12px;font-size:14px;margin-bottom:18px}' +
            '.c360-id dt{color:#64748b;font-weight:600}.c360-id dd{margin:0;color:#0f172a}' +
            '.c360-sec{font-size:12.5px;font-weight:700;letter-spacing:.4px;text-transform:uppercase;color:#64748b;' +
            'margin:0 0 10px}' +
            '.c360-chips{display:flex;flex-wrap:wrap;gap:8px}' +
            '.c360-chip{display:inline-flex;align-items:baseline;gap:6px;padding:7px 12px;border-radius:999px;' +
            'font-size:12.5px;color:#fff;font-weight:600}' +
            '.c360-chip small{opacity:.85;font-weight:500}' +
            '.c360-empty{color:#64748b;font-size:14px}';
        var el = document.createElement('style');
        el.id = STYLE_ID;
        el.appendChild(document.createTextNode(css));
        document.head.appendChild(el);
    }

    function row(dl, label, value) {
        if (!value) return;
        var dt = document.createElement('dt'); dt.textContent = label;
        var dd = document.createElement('dd'); dd.textContent = value;   // textContent — never innerHTML
        dl.appendChild(dt); dl.appendChild(dd);
    }

    /** The row action. Returns '' unless the viewer may see it AND the record is actually bridged to a party. */
    global.contact360Button = function (partyId) {
        if (!global.canViewContact360 || !partyId) return '';
        return " <button type='button' class='btn btn-xs btn-default' onclick='openContact360(" + Number(partyId) + ")'"
             + " title='View this contact across modules'><span class='glyphicon glyphicon-user'></span> 360</button>";
    };

    global.openContact360 = function (partyId) {
        injectStyles();

        var backdrop = document.createElement('div');
        backdrop.className = 'c360-backdrop';
        var card = document.createElement('div');
        card.className = 'c360-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');

        var head = document.createElement('div');
        head.className = 'c360-head';
        var h = document.createElement('h3');
        h.textContent = msg('ui.js.pcTitle', 'Contact across modules');
        var x = document.createElement('button');
        x.type = 'button';
        x.className = 'c360-x';
        x.setAttribute('aria-label', 'Close');
        x.innerHTML = '&times;';
        head.appendChild(h);
        head.appendChild(x);

        var body = document.createElement('div');
        body.className = 'c360-body';
        body.textContent = msg('ui.js.pcLoading', 'Loading…');

        card.appendChild(head);
        card.appendChild(body);
        backdrop.appendChild(card);
        document.body.appendChild(backdrop);

        var opener = document.activeElement;
        function close() {
            if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
            document.removeEventListener('keydown', onKey, true);
            if (opener && opener.focus) opener.focus();
        }
        function onKey(e) { if (e.key === 'Escape') { e.preventDefault(); close(); } }
        x.addEventListener('click', close);
        backdrop.addEventListener('mousedown', function (e) { if (e.target === backdrop) close(); });
        document.addEventListener('keydown', onKey, true);
        x.focus();

        jQuery.get(serverContext + 'partyRoles?id=' + encodeURIComponent(partyId), function (resp) {
            var d = (typeof resp === 'string') ? (resp ? JSON.parse(resp) : {}) : (resp || {});
            var p = d.party, roles = d.roles || [];
            body.textContent = '';

            if (!p || p.id == null) {
                // 404/{} — not bridged yet, or not visible to this tenant. Not an error worth alarming anyone.
                var none = document.createElement('div');
                none.className = 'c360-empty';
                none.textContent = msg('ui.js.pcNoRecord',
                    'No shared contact record yet. It is created the next time this record is saved.');
                body.appendChild(none);
                return;
            }

            h.textContent = msg('ui.js.pcTitle', 'Contact across modules') + ' — ' + (p.name || ('#' + p.id));

            var dl = document.createElement('dl');
            dl.className = 'c360-id';
            row(dl, 'Name', p.name);
            row(dl, 'Contact', p.contact);
            row(dl, 'Email', p.email);
            row(dl, 'Address', p.address);
            row(dl, 'Party ID', '#' + p.id);
            body.appendChild(dl);

            var sec = document.createElement('p');
            sec.className = 'c360-sec';
            sec.textContent = msg('ui.js.pcRoles', 'Roles across modules');
            body.appendChild(sec);

            if (!roles.length) {
                var e = document.createElement('div');
                e.className = 'c360-empty';
                e.textContent = msg('ui.js.pcNoRoles',
                    'No module roles recorded yet — they are indexed as each module saves this contact.');
                body.appendChild(e);
                return;
            }
            var chips = document.createElement('div');
            chips.className = 'c360-chips';
            roles.forEach(function (r) {
                var m = MODULES[(r.module || '').toLowerCase()] || { label: r.module || '?', color: '#475569' };
                var chip = document.createElement('span');
                chip.className = 'c360-chip';
                chip.style.background = m.color;
                chip.appendChild(document.createTextNode(m.label + ' · ' + (r.role || '')));
                if (r.label) {
                    var s = document.createElement('small');
                    s.textContent = '(' + r.label + ')';
                    chip.appendChild(s);
                }
                chips.appendChild(chip);
            });
            body.appendChild(chips);
        }, 'json').fail(function () {
            body.textContent = '';
            var err = document.createElement('div');
            err.className = 'c360-empty';
            err.style.color = '#DC2626';
            err.textContent = msg('ui.js.pcLoadFailed', 'Could not load the contact view.');
            body.appendChild(err);
        });
    };
})(window);
