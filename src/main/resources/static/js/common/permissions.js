/*
 * permissions.js — PERM-1. The owner's permission matrix.
 *
 * Design: microservices/docs/slices/perm-1-permission-sets-design.md
 *
 * ── WHY A PLAIN TABLE AND NOT A DATATABLE ───────────────────────────────────────────────────────
 * Twelve rows. A DataTable earns its keep at two hundred: search, paging, export. At twelve it adds a
 * page-length control nobody wants and one real bug — DataTables REBUILDS its cells on every draw, so
 * checkbox state is wiped by a sort or a search. This codebase has already paid for that trap once, in
 * the cart hints, which is why they are re-applied on `draw`. A matrix of checkboxes has no such hook to
 * hang on and no reason to need one.
 *
 * ── THE THREE THINGS THAT MAKE IT USABLE ────────────────────────────────────────────────────────
 *  1. ROW MASTER TOGGLE — tick the area, get every action in it.
 *  2. CASCADE BOTH WAYS — ticking sale.create ticks product.view (it cannot work without it), and
 *     unticking product.view unticks whatever depended on it. The owner changes the CAUSE, never
 *     fights the effect, and no incoherent set is reachable.
 *  3. THE PREVIEW — the sidebar as that member will see it. A matrix is abstract; a sidebar is not. It
 *     turns "did I tick the right 6 of 41 boxes" into "is that the menu I meant", which is a question an
 *     owner can actually answer — and it catches a mis-grant BEFORE saving rather than after.
 */
(function (global, $) {
    'use strict';

    var catalog = [];          // [{code, area, action, label, implies}]
    var sets = [];             // [{id, name, scope, builtin, codes[]}]
    var current = null;        // the set being edited
    var chosen = {};           // code -> true. The model; the DOM renders FROM it, never the reverse.
    var dependents = {};       // code -> [codes that imply it] — the reverse edge, for the up-cascade

    /** Areas in the order the matrix draws them, and the label an owner reads. */
    var AREA_LABEL = {
        // shop
        sale: 'Sale', purchase: 'Purchase', customer: 'Customers', product: 'Products',
        supplier: 'Suppliers', stock: 'Stock', till: 'Till & shifts',
        finance: 'Finance & ledger', opening: 'Opening balances',
        // school (EDU-PERM-1). The matrix labels every row from this map, so an area missing here
        // renders as its raw slug — "reportcard", "behaviour" — which is the screen looking unfinished.
        student: 'Students', guardian: 'Guardians', staff: 'Staff', attendance: 'Attendance',
        'class': 'Classes', subject: 'Subjects', timetable: 'Timetable', exam: 'Examinations',
        marks: 'Marks', reportcard: 'Report cards', homework: 'Homework', behaviour: 'Behaviour',
        communication: 'Notices & meetings', fee: 'Fees', school: 'Campus', transport: 'Transport',
        // shared by both — see the COMMON module in V16
        report: 'Reports', settings: 'Settings', team: 'Team & users'
    };
    /** The columns every area is measured against. Anything else is a SPECIAL, shown in its own cell. */
    var CORE = ['view', 'create', 'edit', 'delete'];

    function tr(key, fallback) {
        return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
            ? global.t(key) : fallback;
    }
    function esc(v) {
        return (typeof global.escHtml === 'function') ? global.escHtml(v == null ? '' : String(v))
            : String(v == null ? '' : v);
    }

    // ── the model ──────────────────────────────────────────────────────────────────────────────

    function permByCode(code) {
        for (var i = 0; i < catalog.length; i++) if (catalog[i].code === code) return catalog[i];
        return null;
    }
    function impliedBy(code) {
        var p = permByCode(code);
        if (!p || !p.implies) return [];
        return String(p.implies).split(',').map(function (s) { return s.trim(); })
            .filter(function (s) { return s.length > 0; });
    }

    /** Build the reverse edge once: for each permission, who depends on it. */
    function indexDependents() {
        dependents = {};
        catalog.forEach(function (p) {
            impliedBy(p.code).forEach(function (dep) {
                (dependents[dep] = dependents[dep] || []).push(p.code);
            });
        });
    }

    /**
     * ⭐ TICK — and pull in everything this permission cannot work without.
     *
     * Transitive: opening.create implies customer.view, and if customer.view ever gains an implication
     * of its own this follows it with no edit here. Returns what was added, so the screen can SAY what
     * it did rather than silently ticking boxes the owner did not touch.
     */
    function grant(code, added) {
        if (chosen[code]) return added;
        chosen[code] = true;
        added.push(code);
        impliedBy(code).forEach(function (dep) { grant(dep, added); });
        return added;
    }

    /**
     * ⭐ UNTICK — and drop everything that depended on it.
     *
     * The half that keeps the owner in control. Locking a dependency (greying it out while its dependent
     * is on) would take the decision away; cascading only downwards would let them re-break it. Removing
     * the dependents instead means the owner changes the CAUSE and the effect follows — the same way
     * every file-permission dialog behaves, and the only version where no incoherent state exists.
     */
    function revoke(code, removed) {
        if (!chosen[code]) return removed;
        delete chosen[code];
        removed.push(code);
        (dependents[code] || []).forEach(function (dep) { revoke(dep, removed); });
        return removed;
    }

    function areasInOrder() {
        var seen = [], out = [];
        catalog.forEach(function (p) {
            if (seen.indexOf(p.area) < 0) { seen.push(p.area); out.push(p.area); }
        });
        return out;
    }
    function permsOf(area) {
        return catalog.filter(function (p) { return p.area === area; });
    }

    // ── rendering ──────────────────────────────────────────────────────────────────────────────

    function cell(area, action) {
        var p = permsOf(area).filter(function (x) { return x.action === action; })[0];
        /*
         * ⚠ A NON-EXISTENT ACTION IS A DASH, NEVER AN UNTICKED BOX.
         *
         * Finance has no Create; Settings has no Delete. A full grid would be 84 cells of which ~39 mean
         * nothing, and an unticked checkbox is a promise that ticking it does something. A dash says
         * "not applicable here" and cannot be misread as "switched off".
         */
        if (!p) return '<td class="pm-na" title="' + esc(tr('ui.js.permNotApplicable',
                'Not applicable to ' + (AREA_LABEL[area] || area))) + '">&mdash;</td>';

        // View is the row's foundation: with it off, the rest of the row is meaningless and is disabled
        // rather than left tickable. Owner's ruling.
        var viewOff = action !== 'view' && !chosen[area + '.view'];
        return '<td><label class="pm-box' + (viewOff ? ' is-off' : '') + '">'
             + '<input type="checkbox" data-code="' + esc(p.code) + '"'
             + (chosen[p.code] ? ' checked' : '') + (viewOff ? ' disabled' : '') + '>'
             + '<span class="sr-only">' + esc(p.label) + '</span></label></td>';
    }

    function specialsCell(area) {
        var extra = permsOf(area).filter(function (p) { return CORE.indexOf(p.action) < 0; });
        if (!extra.length) return '<td class="pm-na">&mdash;</td>';
        var viewOff = !chosen[area + '.view'];
        return '<td class="pm-specials">' + extra.map(function (p) {
            return '<label class="pm-chip' + (viewOff ? ' is-off' : '') + '">'
                 + '<input type="checkbox" data-code="' + esc(p.code) + '"'
                 + (chosen[p.code] ? ' checked' : '') + (viewOff ? ' disabled' : '') + '> '
                 + esc(p.label.replace(/^(Give|Void|Export|Close|Reverse)\b.*/, function (m) { return m; }))
                 + '</label>';
        }).join('') + '</td>';
    }

    function renderMatrix() {
        var rows = areasInOrder().map(function (area) {
            var all = permsOf(area);
            var on = all.filter(function (p) { return chosen[p.code]; }).length;
            var master = on === all.length ? 'checked' : '';
            var part = on > 0 && on < all.length ? ' pm-partial' : '';
            return '<tr data-area="' + esc(area) + '">'
                 + '<th class="pm-area"><label class="pm-box' + part + '">'
                 + '<input type="checkbox" class="pm-master" data-area="' + esc(area) + '" ' + master + '>'
                 + '</label> <span>' + esc(AREA_LABEL[area] || area) + '</span></th>'
                 + CORE.map(function (a) { return cell(area, a); }).join('')
                 + specialsCell(area)
                 + '</tr>';
        }).join('');

        $('#permMatrix tbody').html(rows);
        renderPreview();
    }

    /**
     * ⭐ THE PREVIEW — the sidebar as this member will see it.
     *
     * Derived from the same `chosen` model the matrix is, so it cannot disagree with what will be saved.
     * An area appears when the member can VIEW it, which is exactly the rule the server uses to render
     * the nav — one rule, stated once, shown before it takes effect.
     */
    function renderPreview() {
        var visible = areasInOrder().filter(function (a) { return !!chosen[a + '.view']; });
        var hidden = areasInOrder().filter(function (a) { return !chosen[a + '.view']; });

        /*
         * ⭐ The preview says WHOSE records too, not only which areas.
         *
         * The matrix and the Sees control answer two different questions, and an owner reading only the
         * sidebar preview would see "Sale, Customers" and reasonably conclude the member can work with
         * the shop's customers -- when on OWN they will open an EMPTY customer picker and be unable to
         * ring up a credit sale at all. The preview has to carry both halves or it predicts the wrong
         * screen, which is worse than predicting nothing.
         */
        /*
         * ⚠ SOME TENANTS HAVE NO ROW SCOPE TO CHOOSE. `scope` means "rows this member CREATED", and in a
         * school a teacher created none of them — the office did — so OWN would show a teacher nothing at
         * all. AuthService mints EDUCATION as scope.ALL whatever a set says (rowScopedTenant is false for
         * it), so the control is fixed and hidden there rather than offered and ignored.
         *
         * `data-scope-fixed` on #permWrap is how the page says so. The preview still states whose records
         * they see, because the preview predicting only half the screen is what this block exists for.
         */
        var scopeFixed = $('#permWrap').attr('data-scope-fixed');
        var scopeAll = scopeFixed ? scopeFixed === 'ALL' : $('#permScope').val() === 'ALL';
        $('#permPreviewScope').text(scopeFixed
            ? tr('ui.js.permPreviewShared', 'Sees every record — a school shares its students')
            : (scopeAll
                ? tr('ui.js.permPreviewAll', 'Sees every record in the shop')
                : tr('ui.js.permPreviewOwn', 'Sees only the records they create themselves')));

        $('#permPreviewOn').html(visible.length
            ? visible.map(function (a) {
                return '<span class="pm-nav">' + esc(AREA_LABEL[a] || a) + '</span>';
              }).join('')
            : '<em class="pm-none">' + esc(tr('ui.js.permNothing',
                'Nothing — they will sign in to an empty dashboard.')) + '</em>');

        $('#permPreviewOff').text(hidden.length
            ? tr('ui.js.permHidden', 'Not shown') + ': '
                + hidden.map(function (a) { return AREA_LABEL[a] || a; }).join(', ')
            : '');
    }

    /** Say what the cascade did. A box the owner did not tick, ticking itself, needs a reason on screen. */
    function explain(added, removed) {
        var $n = $('#permNote');
        var msg = '';
        if (added.length > 1) {
            msg = tr('ui.js.permAlsoOn', 'Also enabled, because the ones you chose need them') + ': '
                + added.slice(1).map(function (c) {
                    var p = permByCode(c); return p ? p.label : c;
                  }).join(', ');
        } else if (removed.length > 1) {
            msg = tr('ui.js.permAlsoOff', 'Also turned off, because they cannot work without it') + ': '
                + removed.slice(1).map(function (c) {
                    var p = permByCode(c); return p ? p.label : c;
                  }).join(', ');
        }
        if (!msg) { $n.hide().text(''); return; }
        $n.text(msg).show();
    }

    // ── loading and saving ─────────────────────────────────────────────────────────────────────

    function selectSet(id) {
        current = sets.filter(function (s) { return String(s.id) === String(id); })[0] || null;
        chosen = {};
        if (current) (current.codes || []).forEach(function (c) { chosen[c] = true; });

        $('#permSetName').val(current ? current.name : '');
        var fixed = $('#permWrap').attr('data-scope-fixed');
        $('#permScope').val(fixed ? fixed : (current ? current.scope : 'OWN'));

        // A built-in is the CONTRACT that this feature's deploy changed nothing for anybody. Editing one
        // in place would rewrite that contract for every member already migrated onto it, so the screen
        // offers Duplicate instead of pretending the fields are editable.
        var ro = !!(current && current.builtin);
        $('#permMatrix, #permSetName, #permScope').find('input, select').prop('disabled', ro);
        $('#permMatrix').toggleClass('is-readonly', ro);
        $('#permSave').prop('disabled', ro);
        $('#permBuiltinNote').toggle(ro);

        renderMatrix();
    }

    global.showPermissions = function () {
        $.get(serverContext + 'team/permissions', function (resp) {
            var d = (resp && (resp.data || resp.object)) || {};
            catalog = d.catalog || [];
            sets = d.sets || [];
            indexDependents();
            /*
             * Published for the team table's per-row picker (team.js).
             *
             * One fetch, two consumers: the matrix draws itself from `catalog` + `sets`, and the row
             * picker needs only the set names. A second call for the same payload would be a second
             * answer to one question, and the day they disagree the picker offers a set the matrix
             * does not have.
             */
            global.teamPermissionSets = sets.map(function (s) {
                return { id: s.id, name: s.name, builtin: s.builtin };
            });
            if (typeof global.loadTeamUsers === 'function') global.loadTeamUsers();
            $('#permSetPicker').html(sets.map(function (s) {
                return '<option value="' + esc(s.id) + '">' + esc(s.name)
                     + (s.builtin ? ' · ' + esc(tr('ui.js.permBuiltin', 'built-in')) : '')
                     + '</option>';
            }).join(''));
            if (sets.length) selectSet(sets[0].id);
        }).fail(function () {
            $('#permMsg').removeClass('alert-success').addClass('alert-danger')
                .text(tr('ui.js.permLoadFailed', 'Could not load permissions.')).show();
        });
    };

    global.savePermissionSet = function () {
        var codes = Object.keys(chosen);
        $.ajax({
            url: serverContext + 'team/permissions/sets', method: 'POST',
            contentType: 'application/json',
            // BLK-2: this save had no lock at all. It keeps the veil — creating a set is not idempotent and the
            // version check is BLK-7 — but the button itself now says so and cannot be pressed twice.
            busyControl: '#permSave',
            data: JSON.stringify({
                id: current && !current.builtin ? current.id : null,
                name: $('#permSetName').val(),
                scope: $('#permScope').val(),
                codes: codes
            })
        }).done(function (resp) {
            var msg = (resp && resp.message) || tr('ui.js.permSaved', 'Permission set saved.');
            $('#permMsg').removeClass('alert-danger').addClass('alert-success').text(msg).show();
            global.showPermissions();
        }).fail(function (xhr) {
            var body = xhr.responseJSON || {};
            $('#permMsg').removeClass('alert-success').addClass('alert-danger')
                .text(body.message || tr('ui.js.permSaveFailed', 'Could not save the permission set.'))
                .show();
        });
    };

    /** Duplicate: the supported way to start from a built-in, and the only way to change one. */
    global.duplicatePermissionSet = function () {
        if (!current) return;
        current = { id: null, name: current.name + ' (copy)', scope: current.scope,
                    builtin: false, codes: Object.keys(chosen) };
        $('#permSetName').val(current.name);
        $('#permMatrix, #permSetName, #permScope').find('input, select').prop('disabled', false);
        $('#permMatrix').removeClass('is-readonly');
        $('#permSave').prop('disabled', false);
        $('#permBuiltinNote').hide();
        renderMatrix();
    };

    // ── events ─────────────────────────────────────────────────────────────────────────────────

    $(document).on('change', '#permMatrix input[type=checkbox]:not(.pm-master)', function () {
        var code = $(this).data('code');
        var added = [], removed = [];
        if (this.checked) grant(code, added); else revoke(code, removed);
        renderMatrix();
        explain(added, removed);
    });

    // The row master: the owner's own request. Tick the area, get every action in it.
    $(document).on('change', '#permMatrix .pm-master', function () {
        var area = $(this).data('area');
        var on = this.checked, added = [], removed = [];
        permsOf(area).forEach(function (p) {
            if (on) grant(p.code, added); else revoke(p.code, removed);
        });
        renderMatrix();
        explain(added, removed);
    });

    $(document).on('change', '#permSetPicker', function () { selectSet(this.value); });

    // The Sees control feeds the preview, so a change to it has to redraw — otherwise the panel keeps
    // predicting the screen the OTHER setting would have produced.
    $(document).on('change', '#permScope', renderPreview);

    global.PermissionMatrix = {
        // Exposed for the gate: the closure is the behaviour worth asserting directly, and driving it
        // through 41 checkboxes would test the DOM rather than the rule.
        grant: function (c) { return grant(c, []); },
        revoke: function (c) { return revoke(c, []); },
        chosen: function () { return Object.keys(chosen); },
        load: function (cat, s) { catalog = cat; sets = s || []; indexDependents(); }
    };
})(window, jQuery);
