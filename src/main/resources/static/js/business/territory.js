/**
 * OMS O7 D6a — the owner's territory screen: who covers which outlet.
 *
 * <h3>What this is for</h3>
 * `Customer.assigned_rep_user_id` and the rule that reads it both shipped in D2d, and nothing ever wrote the
 * column — so every rep fell through the "no assignments → sees everything" branch and no territory had ever
 * narrowed. This screen is what gives that column its data.
 *
 * <h3>Its own file, deliberately</h3>
 * `order-booking.js` is the FIELD's surface — a rep on a phone, choosing shops they may book for. This is the
 * OWNER's, deciding who those shops belong to. They read the same column from opposite sides of an authority
 * boundary, and business-service refuses this one to a rep outright.
 *
 * <h3>The names are joined here, not on the server</h3>
 * `/outletAssignments` returns `assignedRepUserId` and no name. This screen already loads the member list to
 * fill its dropdown, so it can resolve names itself — where having business-service do it would put an
 * auth-service round trip on a read whose only job is to draw a table. Nor is the name stamped: unlike
 * `booked_by_name`, frozen because an issued order outlives its staff, an assignment is CURRENT state, so
 * renaming a rep should change what this screen shows.
 */
(function (global) {
    'use strict';
    var $ = global.jQuery;
    if (!$) return;

    /** Outlets as last read, and the members we can assign to. */
    var state = { rows: [], reps: [], repName: {} };

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

    global.showTerritory = function () {
        $('.formDiv').hide();
        $('#TerritoryDiv').show();
        notice('');
        $('#terrFilter').val('');
        loadReps();
        loadOutlets();
    };

    /**
     * The people an outlet can be given to.
     *
     * EVERY member of the org, not "the order bookers". The members endpoint returns the MEMBERSHIP role
     * (OWNER/ADMIN/USER), not the security role, so "only reps" is not derivable from the data that exists —
     * and filtering on a guess would hide real staff from the picker and produce a bug nobody could explain.
     * The owner knows who their reps are; this screen should not pretend to know better.
     */
    function loadReps() {
        /*
         * `/team/users`, the proxy the Team screen already uses — not a second one of my own. It answers with
         * an ApiResponse (`{success, data}`), NOT the GenericResponse (`{status, collection}`) that every
         * business-service read on this page returns; the two shapes live side by side in this app and reading
         * the wrong field yields undefined rather than an error.
         */
        $.get('/team/users')
            .done(function (res) {
                var rows = (res && (res.data || res.collection)) || [];
                state.reps = rows;
                state.repName = {};
                var opts = ['<option value="">' + esc(tr('ui.chooseRep', '-- choose a rep --')) + '</option>'];
                rows.forEach(function (u) {
                    var id = u.userId != null ? u.userId : u.id;
                    if (id == null) return;
                    // The email is the dependable label — `name` is blank for anyone who never filled in a
                    // profile, and an unlabelled row in a picker is worse than a long one.
                    var label = (u.name && String(u.name).trim()) ? (u.name + ' (' + (u.email || '') + ')')
                                                                  : (u.email || ('#' + id));
                    state.repName[String(id)] = label;
                    opts.push('<option value="' + esc(id) + '">' + esc(label) + '</option>');
                });
                $('#terrRep').html(opts.join(''));
                if ($('#terrRep').data('selectpicker')) $('#terrRep').selectpicker('refresh');
                render();
            })
            .fail(function () {
                // Say so rather than showing an empty dropdown that looks like "no staff exist".
                notice(tr('ui.territoryRepsFailed', 'Could not load the team list.'), 'warning');
            });
    }

    function loadOutlets() {
        $.get('/outletAssignments')
            .done(function (res) {
                if (!res || res.status !== 'SUCCESS') {
                    notice(tr('ui.territoryLoadFailed', 'Could not load the outlets.'), 'danger');
                    state.rows = [];
                    render();
                    return;
                }
                state.rows = res.collection || [];
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
        var rows = visibleRows();
        if (!rows.length) {
            $('#terrBody').html('<tr><td colspan="4" class="text-muted">'
                + esc(tr('ui.noOutlets', 'No outlets.')) + '</td></tr>');
            $('#terrAll').prop('checked', false);
            return;
        }
        var html = rows.map(function (r) {
            var repId = r.assignedRepUserId;
            // "Unassigned" is not a gap to apologise for — it is the shared pool, and saying so stops an
            // owner assigning every outlet just to make a blank column go away.
            var who = (repId == null)
                ? '<span class="text-muted">' + esc(tr('ui.unassignedShared', 'Unassigned — visible to all')) + '</span>'
                : esc(state.repName[String(repId)] || ('#' + repId));
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
     * @param repUserId the rep to assign to, or null to UNASSIGN — which returns the outlets to the shared
     *                  pool rather than hiding them from everybody.
     */
    function apply(repUserId) {
        var ids = picked();
        if (!ids.length) {
            notice(tr('ui.territoryPickSome', 'Choose at least one outlet first.'), 'warning');
            return;
        }
        if (repUserId !== null && !repUserId) {
            notice(tr('ui.territoryPickRep', 'Choose who to assign them to.'), 'warning');
            return;
        }
        var body = { customerIds: ids.join(',') };
        if (repUserId !== null) body.repUserId = repUserId;

        $.post('/assignOutlets', body)
            .done(function (res) {
                if (!res || res.status !== 'SUCCESS') {
                    notice((res && res.message) || tr('ui.territorySaveFailed', 'Could not save.'), 'danger');
                    return;
                }
                /*
                 * Report what the SERVER changed, not what we asked it to.
                 *
                 * The endpoint scopes by tenant inside its query, so ids that were not ours are simply not
                 * written and `assigned` comes back lower than `requested`. Echoing our own request back as
                 * "done" is how a half-applied territory goes unnoticed.
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
    });
})(window);
