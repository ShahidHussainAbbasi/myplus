/**
 * OMS O7 D6a + SRA-2 — the owner's territory screen: the SALES REPS, and who covers which outlet.
 * Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §5.
 *
 * <h3>SRA-2: an outlet is covered by a REP, not a login</h3>
 * D6a pointed an outlet at a LOGIN (`assigned_rep_user_id`), so a rep who books on paper — and never logs in — could
 * never cover one. Now the business keeps its own rep list (business-service `sales_rep`, V83): a rep is a person, with
 * an optional login. `/outletAssignments` answers each outlet's `repId`; `/assignOutlets` takes a `repId`. A rep whose
 * login is linked sees their own outlets plus every unassigned one; everyone else is not narrowed.
 *
 * <h3>Who may use it</h3>
 * The rep.manage permission (auth V33: the menu row 'territory', and the endpoint rules) — data, not role names. A rep
 * never assigns outlets to themselves (the D6a rule).
 *
 * <h3>The same list feeds the customer form</h3>
 * The "Usual rep" picker on the customer form ([data-sales-rep-picker]) is filled from `/getSalesReps` here, so there
 * is one reader of the list on this page and one place that knows its shape (GenericResponse: a list in `collection`).
 *
 * <h3>Names are joined here, not on the server</h3>
 * An assignment is CURRENT state — renaming a rep should change what this screen shows — so the row carries the rep id
 * and this screen names it from the list it already holds.
 */
(function (global) {
    'use strict';
    var $ = global.jQuery;
    if (!$) return;

    /** Outlets as last read; the business's reps; the members a rep can be linked to. */
    var state = { rows: [], reps: [], members: [], loaded: null, outletsLoaded: false };

    function esc(s) {
        return (global.escHtml ? global.escHtml(String(s == null ? '' : s)) : String(s == null ? '' : s));
    }

    function tr(key, fallback) {
        var v = (typeof global.t === 'function') ? global.t(key) : null;
        return (v && v !== key) ? v : fallback;
    }

    function notice(msg, kind) {
        var $n = $('#terrNotice');
        if (!msg) { $n.hide(); return; }
        $n.attr('class', 'alert alert-' + (kind || 'info')).text(msg).show();
    }

    function repNotice(msg, kind) {
        var $n = $('#srNotice');
        if (!msg) { $n.hide(); return; }
        $n.attr('class', 'alert alert-' + (kind || 'info')).text(msg).show();
    }

    function refresh($sel) {
        if ($sel.data('selectpicker')) { try { $sel.selectpicker('refresh'); } catch (e) {} }
    }

    function active(r) { return String(r.status || 'ACTIVE').toUpperCase() === 'ACTIVE'; }

    function repById(id) {
        for (var i = 0; i < state.reps.length; i++) if (String(state.reps[i].id) === String(id)) return state.reps[i];
        return null;
    }

    global.showTerritory = function () {
        $('.formDiv').hide();
        $('#TerritoryDiv').show();
        notice('');
        repNotice('');
        $('#terrFilter').val('');
        state.outletsLoaded = false;   // until /outletAssignments answers, the table says "Loading…", never "No outlets."
        loadMembers();
        loadReps(true).always(loadOutlets);
    };

    // ── the rep list ──────────────────────────────────────────────────────────────────────────────────────────

    /** The business's reps — fetched once per page, again after a change (it is small). */
    function loadReps(force) {
        if (state.loaded && !force) return state.loaded;
        state.loaded = $.ajax({ url: '/getSalesReps', nonBlocking: true, global: false })
            .done(function (res) {
                state.reps = (res && res.status === 'SUCCESS' && res.collection) || [];
                paintRepPickers();
                renderReps();
                render();
            })
            .fail(function () {
                state.loaded = null;
                repNotice(tr('ui.js.salesRepsLoadFailed', 'Could not load the sales reps.'), 'warning');
            });
        return state.loaded;
    }

    /** The assign picker (active reps only) and every "Usual rep" picker (inactive ones kept, marked, so an edit can show them). */
    function paintRepPickers() {
        var opts = ['<option value="">' + esc(tr('ui.js.chooseRep', '-- choose a rep --')) + '</option>'];
        state.reps.forEach(function (r) { if (active(r)) opts.push('<option value="' + esc(r.id) + '">' + esc(r.name) + '</option>'); });
        $('#terrRep').html(opts.join(''));
        refresh($('#terrRep'));

        $('[data-sales-rep-picker]').each(function () {
            var $p = $(this), keep = $p.val() || '0';
            // SRA-3: who took an order — the sale screen ("sale": none = a counter sale) and the booking screen ("order":
            // none = the office's own order). The customer form's "Usual rep" (no value) says nobody covers the outlet.
            var variant = $p.attr('data-sales-rep-picker');
            var none = variant === 'sale' ? tr('ui.js.noRepCounter', '— No rep (counter) —')
                     : variant === 'order' ? tr('ui.js.noRepOffice', '— No rep (office order) —')
                     : tr('ui.js.noRep', '— Nobody (shared) —');
            var html = '<option value="0">' + esc(none) + '</option>';
            state.reps.forEach(function (r) {
                if (active(r) || String(r.id) === keep)
                    html += '<option value="' + esc(r.id) + '">' + esc(r.name) + (active(r) ? '' : ' (' + esc(tr('ui.js.inactive', 'inactive')) + ')') + '</option>';
            });
            $p.html(html).val(keep);
            if ($p.val() == null) $p.val('0');
            // A business with no reps never sees the sale picker. The WRAPPER is toggled (a bootstrap-select button
            // cannot be hidden itself); an edit of an invoice made by a since-deactivated rep still shows it.
            if (variant) $p.closest('[data-sales-rep-wrap]').toggle(state.reps.some(active) || keep !== '0');
            refresh($p);
        });
    }

    /** The members a rep can be linked to (the Team screen's own proxy — an ApiResponse: {success, data}). */
    function loadMembers() {
        $.get('/team/users').done(function (res) {
            state.members = (res && (res.data || res.collection)) || [];
            var opts = ['<option value="">' + esc(tr('ui.js.noLogin', '— No login —')) + '</option>'];
            state.members.forEach(function (u) {
                var id = u.userId != null ? u.userId : u.id;
                if (id == null || String(u.status || '').toUpperCase() === 'INACTIVE') return;
                opts.push('<option value="' + esc(id) + '">' + esc(memberLabel(u)) + '</option>');
            });
            $('#srNewLogin').html(opts.join(''));
            renderReps();
        });
    }

    function memberLabel(u) {
        // The email is the dependable label — `name` is blank for anyone who never filled in a profile.
        return (u.name && String(u.name).trim()) ? (u.name + ' (' + (u.email || '') + ')') : (u.email || ('#' + (u.userId || u.id)));
    }

    function memberName(userId) {
        if (userId == null) return '';
        for (var i = 0; i < state.members.length; i++) {
            var u = state.members[i];
            if (String(u.userId != null ? u.userId : u.id) === String(userId)) return memberLabel(u);
        }
        return '#' + userId;
    }

    function renderReps() {
        if (!$('#srBody').length) return;
        if (!state.reps.length) {
            $('#srBody').html('<tr><td colspan="5" class="text-muted">' + esc(tr('ui.js.noSalesReps', 'No reps yet — add the first above.')) + '</td></tr>');
            return;
        }
        var loginOpts = function (selected) {
            var html = '<option value="0">' + esc(tr('ui.js.noLogin', '— No login —')) + '</option>';
            state.members.forEach(function (u) {
                var id = u.userId != null ? u.userId : u.id;
                if (id == null) return;
                html += '<option value="' + esc(id) + '"' + (String(id) === String(selected) ? ' selected' : '') + '>' + esc(memberLabel(u)) + '</option>';
            });
            if (selected != null && !state.members.some(function (u) { return String(u.userId != null ? u.userId : u.id) === String(selected); }))
                html += '<option value="' + esc(selected) + '" selected>#' + esc(selected) + '</option>';
            return html;
        };
        $('#srBody').html(state.reps.map(function (r) {
            var on = active(r);
            return '<tr data-rep-id="' + esc(r.id) + '"' + (on ? '' : ' class="text-muted"') + '>'
                + '<td><input type="text" class="form-control input-sm" data-sr-field="name" maxlength="120" value="' + esc(r.name) + '"></td>'
                + '<td><input type="text" class="form-control input-sm" data-sr-field="phone" maxlength="40" value="' + esc(r.phone || '') + '"></td>'
                + '<td><select class="form-control input-sm" data-sr-field="userId" data-no-search>' + loginOpts(r.userId) + '</select></td>'
                + '<td>' + esc(on ? tr('ui.js.active', 'Active') : tr('ui.js.inactive', 'inactive')) + '</td>'
                + '<td style="white-space:nowrap">'
                + '<button type="button" class="btn btn-xs btn-primary" data-sr-save>' + esc(tr('ui.js.save', 'Save')) + '</button> '
                + '<button type="button" class="btn btn-xs btn-default" data-sr-toggle>'
                + esc(on ? tr('ui.js.deactivate', 'Deactivate') : tr('ui.js.reactivate', 'Reactivate')) + '</button>'
                + '</td></tr>';
        }).join(''));
    }

    function saveRep(path, data, done) {
        $.ajax({ type: 'POST', url: '/' + path, data: data, nonBlocking: true })
            .done(function (res) {
                if (!res || res.status !== 'SUCCESS') {
                    repNotice((res && res.message) || tr('ui.territorySaveFailed', 'Could not save.'), 'danger');
                    return;
                }
                repNotice(res.message || tr('ui.saved', 'Saved.'), 'success');
                if (done) done(res.object);
                loadReps(true).always(loadOutlets);
            })
            .fail(function (x) {
                repNotice((x && x.responseJSON && x.responseJSON.message) || tr('ui.territorySaveFailed', 'Could not save.'), 'danger');
            });
    }

    // ── outlets ───────────────────────────────────────────────────────────────────────────────────────────────

    function loadOutlets() {
        $.get('/outletAssignments')
            .done(function (res) {
                if (!res || res.status !== 'SUCCESS') {
                    notice((res && res.message) || tr('ui.territoryLoadFailed', 'Could not load the outlets.'), 'danger');
                    state.rows = [];
                    render();
                    return;
                }
                state.rows = res.collection || [];
                state.outletsLoaded = true;
                render();
            })
            .fail(function () {
                notice(tr('ui.territoryLoadFailed', 'Could not load the outlets.'), 'danger');
            });
    }

    function visibleRows() {
        var q = String($('#terrFilter').val() || '').trim().toLowerCase();
        if (!q) return state.rows;
        return state.rows.filter(function (r) {
            return String(r.name || '').toLowerCase().indexOf(q) >= 0
                || String(r.contact || '').toLowerCase().indexOf(q) >= 0;
        });
    }

    function render() {
        if (!$('#terrBody').length) return;
        /*
         * ⚠ The rep list usually answers FIRST, and repainting names used to draw "No outlets." over a table whose
         * outlets had not arrived — on every open, and a check that waited for "a row" accepted it. Not loaded yet is
         * not empty.
         */
        if (!state.outletsLoaded) {
            $('#terrBody').html('<tr data-loading><td colspan="4" class="text-muted">' + esc(tr('ui.js.loading', 'Loading…')) + '</td></tr>');
            return;
        }
        var rows = visibleRows();
        if (!rows.length) {
            $('#terrBody').html('<tr><td colspan="4" class="text-muted">'
                + esc(tr('ui.noOutlets', 'No outlets.')) + '</td></tr>');
            $('#terrAll').prop('checked', false);
            return;
        }
        var html = rows.map(function (r) {
            // "Unassigned" is not a gap to apologise for — it is the shared pool, and saying so stops an
            // owner assigning every outlet just to make a blank column go away.
            var rep = r.repId == null ? null : repById(r.repId);
            var who = (r.repId == null)
                ? '<span class="text-muted">' + esc(tr('ui.unassignedShared', 'Unassigned — visible to all')) + '</span>'
                : esc(rep ? rep.name + (active(rep) ? '' : ' (' + tr('ui.js.inactive', 'inactive') + ')') : ('#' + r.repId));
            return '<tr>'
                 + '<td><input type="checkbox" class="terr-pick" value="' + esc(r.id) + '" /></td>'
                 + '<td>' + esc(r.name) + '</td>'
                 + '<td>' + esc(r.contact || '') + '</td>'
                 + '<td>' + who + '</td>'
                 + '</tr>';
        }).join('');
        $('#terrBody').html(html);
        $('#terrAll').prop('checked', false);
    }

    function picked() {
        return $('.terr-pick:checked').map(function () { return Number(this.value); }).get();
    }

    /**
     * @param repId the rep to assign to, or null to UNASSIGN — which returns the outlets to the shared pool rather
     *              than hiding them from everybody.
     */
    function apply(repId) {
        var ids = picked();
        if (!ids.length) {
            notice(tr('ui.territoryPickSome', 'Choose at least one outlet first.'), 'warning');
            return;
        }
        if (repId !== null && !repId) {
            notice(tr('ui.territoryPickRep', 'Choose who to assign them to.'), 'warning');
            return;
        }
        var body = { customerIds: ids.join(',') };
        if (repId !== null) body.repId = repId;

        $.post('/assignOutlets', body)
            .done(function (res) {
                if (!res || res.status !== 'SUCCESS') {
                    notice((res && res.message) || tr('ui.territorySaveFailed', 'Could not save.'), 'danger');
                    return;
                }
                /*
                 * Report what the SERVER changed, not what we asked it to. The endpoint scopes by tenant inside its
                 * query, so ids that were not ours are simply not written and `assigned` comes back lower than
                 * `requested`. Echoing our own request back as "done" is how a half-applied territory goes unnoticed.
                 */
                var o = res.object || {};
                var done = Number(o.assigned), asked = Number(o.requested);
                notice(res.message || tr('ui.saved', 'Saved.'),
                       (isFinite(done) && isFinite(asked) && done < asked) ? 'warning' : 'success');
                loadOutlets();
            })
            .fail(function () {
                notice(tr('ui.territorySaveFailed', 'Could not save.'), 'danger');
            });
    }

    $(function () {
        $(document).on('click', '#terrApply', function () { apply(Number($('#terrRep').val()) || 0); });
        $(document).on('click', '#terrClear', function () { apply(null); });
        $(document).on('keyup', '#terrFilter', render);
        // Only what is on screen — a "select all" that silently included rows filtered out of view would
        // reassign outlets the operator cannot see.
        $(document).on('change', '#terrAll', function () {
            $('.terr-pick').prop('checked', $(this).is(':checked'));
        });

        $(document).on('click', '#srAdd', function () {
            var name = $.trim($('#srNewName').val() || '');
            if (!name) { repNotice(tr('ui.js.sraNameRequired', 'Type a name.'), 'warning'); return; }
            saveRep('addSalesRep', { name: name, phone: $('#srNewPhone').val() || '', userId: $('#srNewLogin').val() || '' }, function () {
                $('#srNewName, #srNewPhone').val('');
                $('#srNewLogin').val('');
            });
        });
        $(document).on('click', '[data-sr-save]', function () {
            var $tr = $(this).closest('tr');
            saveRep('updateSalesRep', {
                id: $tr.attr('data-rep-id'),
                name: $tr.find('[data-sr-field="name"]').val(),
                phone: $tr.find('[data-sr-field="phone"]').val() || '',
                userId: $tr.find('[data-sr-field="userId"]').val() || '0'   // 0 = unlink the login
            });
        });
        $(document).on('click', '[data-sr-toggle]', function () {
            var $tr = $(this).closest('tr'), r = repById($tr.attr('data-rep-id'));
            if (!r) return;
            saveRep('updateSalesRep', { id: r.id, status: active(r) ? 'INACTIVE' : 'ACTIVE' });
        });

        // The customer form's "Usual rep" picker needs the list before an edit fills it (editRecord selects the stored
        // rep from the row's hidden cell), so it loads with the page when the picker is there.
        if ($('[data-sales-rep-picker]').length) loadReps();

        /*
         * ⚠ editRecord() (main.js) RENAMES the selected option of a searchable picker to the row cell's text — for this
         * picker that text is the rep's ID, so after an edit the option read "5" instead of the name. Repaint from the
         * list once the edit overlay opens (the value it selected is kept); address.js does the same for City / Area.
         */
        var open = global.openCrudModal;
        if (typeof open === 'function') {
            global.openCrudModal = function (entity) {
                var r = open.apply(this, arguments);
                if ($('#' + entity).find('[data-sales-rep-picker]').length) loadReps().always(paintRepPickers);
                return r;
            };
        }
    });

    // A form reset (New Customer) must not leave the previous customer's rep selected; editRecord fills it right after.
    $(document).on('reset', 'form', function () {
        var $p = $(this).find('[data-sales-rep-picker]');
        if ($p.length) setTimeout(function () { refresh($p); }, 0);
    });

    /**
     * SRA-3: set a rep picker (the sale screen's by default) to a rep id, or null/0 for none, and repaint so an inactive
     * rep still shows by name.
     */
    function setSaleRep(repId, selector) {
        var $p = $(selector || '#sellRepId');
        if (!$p.length) return;
        $p.val(repId ? String(repId) : '0');
        if (repId && $p.val() !== String(repId)) {   // not in the list yet (inactive, or the list still loading)
            $p.append('<option value="' + esc(repId) + '"></option>').val(String(repId));
            loadReps().always(paintRepPickers);
            return;
        }
        refresh($p);
    }

    global.SalesReps = { load: loadReps, byId: repById, setSaleRep: setSaleRep };
})(window);
