/**
 * Team & Users screen — ONE implementation for every vertical (POS/pharma/e-commerce use it via
 * businessDashboard, schools via educationDashboard). It used to live in business.js, which meant education
 * had no way to add a member at all; cloning it would have left two copies of the same rule to drift.
 *
 * The endpoints are vertical-agnostic already: /team/users creates the member, and /assignStores grants them
 * locations — auth derives the location module (store vs school) from the caller's own userType. So the only
 * thing a dashboard has to say is where its location list comes from, and what to call one:
 *
 *   window.TEAM_LOCATIONS_URL  — endpoint returning this vertical's locations   (default: 'getStores')
 *   window.TEAM_LOCATION_NOUN  — 'store' | 'branch', for the picker's label     (default: 'store')
 *
 * The dashboards keep identical element ids (#TeamDiv, #teamStores, #tableTeam …) so this file needs no
 * per-vertical branching.
 */
/**
 * Translate, with a readable English fallback.
 *
 * Same tHas-then-t shape the other shared modules use. This file's button and empty-state labels were
 * hardcoded, so the Team screen stayed English in all six languages — and it is the screen every vertical
 * shares, so the gap appeared on business, education, pharmacy and e-commerce at once.
 *
 * Named tMsg, not msg: this file is NOT wrapped in an IIFE, so anything declared here is global and a name
 * as common as `msg` would collide with the next module that wants one.
 */
function tMsg(key, fallback) {
	if (typeof window.tHas === 'function' && typeof window.t === 'function' && window.tHas(key)) {
		return window.t(key);
	}
	return fallback;
}

function showTeam() {
	$('.formDiv').hide();
	$('#TeamDiv').show();
	$('#teamMsg').hide();
	cancelTeamAccessEdit();
	// Locations FIRST: the team rows name each member's stores/branches, and can only do that once the id→label
	// map exists — otherwise the table renders raw ids on first paint.
	loadTeamLocations(loadTeamUsers);
	/*
	 * PERM-1 — the matrix loads with the screen it lives on.
	 *
	 * Guarded on the FUNCTION rather than on a role: #permWrap carries sec:authorize="ROLE_OWNER", so for
	 * an admin the block is not in the DOM at all and permissions.js has nothing to fill. Asking "does the
	 * screen have this section" is the honest test; re-deriving the role here would be a second opinion
	 * about a question the template already answered.
	 */
	if (typeof showPermissions === 'function' && $('#permWrap').length) showPermissions();
}

function loadTeamUsers() {
	if (window.GridLoading) GridLoading.fill('#tableTeam');   // BLK-3: never the previous rows while re-fetching
	$.get(serverContext + 'team/users', function (resp) {
		var users = (resp && resp.data) ? resp.data : [];
		var $tb = $('#tableTeam tbody').empty();
		if (!users.length) {
			$tb.append('<tr><td colspan="6" class="text-center">No team members yet.</td></tr>');
			return;
		}
		users.forEach(function (u) {
			var $tr = $('<tr>');
			$tr.append($('<td>').text(u.name || ''));
			$tr.append($('<td>').text(u.email || ''));
			$tr.append($('<td>').text(u.role || ''));
			// TEAM-1: Active / Invited (added, no password chosen yet) / Removed (reversible)
			var removed = String(u.status || '').toUpperCase() === 'INACTIVE';
			var $status = $('<span class="label">');
			if (removed) $status.addClass('label-default').text(tMsg('ui.js.teamStatusRemoved', 'Removed'));
			else if (u.invited) $status.addClass('label-warning').text(tMsg('ui.js.teamStatusInvited', 'Invited — no password yet'));
			else $status.addClass('label-success').text(tMsg('ui.js.teamStatusActive', 'Active'));
			$tr.append($('<td>').append($status));
			if (removed) $tr.addClass('text-muted');
			// Where this member works, and a way to CHANGE it — assignment used to be a one-way door: you could
			// grant on creation and never move or revoke afterwards.
			$tr.append($('<td>').text(teamLocationNames(u.locationIds)));

			/*
			 * PERM-1 — which permission set this member is on, changeable in place.
			 *
			 * The matrix above edits a SET; this is what puts a PERSON on one, and without it the feature
			 * was half a loop: an owner could define "Cashier" perfectly and had no way to make anybody a
			 * cashier. Reported from the counter as "I cannot change permission to him".
			 *
			 * Rendered only when the owner is signed in - #permWrap carries sec:authorize="ROLE_OWNER", so
			 * for an admin the whole section is absent from the DOM and this column has no header to sit
			 * under. Asking the DOM is the honest test; re-deriving the role in JS would be a second
			 * opinion about a question the template already answered.
			 */
			/*
			 * ⚠ NO PICKER ON AN OWNER'S ROW.
			 *
			 * An owner's access is implicit — the token is minted from the whole catalog and their set row
			 * is never read — so a picker there offers a control that appears to change their access and
			 * cannot. A control that does nothing is worse than no control: it invites exactly the one
			 * action it will silently ignore. V13 removes the rows; this removes the invitation.
			 */
			var isOwnerRow = String(u.role || '').toUpperCase() === 'OWNER';
			if ($('#permWrap').length && !isOwnerRow) {
				var $sel = $('<select class="form-control input-sm js-permset">')
					.attr('data-user-id', u.userId || u.id);
				(window.teamPermissionSets || []).forEach(function (ps) {
					$('<option>').val(ps.id).text(ps.name + (ps.builtin ? ' \u00b7 built-in' : ''))
						.prop('selected', String(ps.id) === String(u.permissionSetId))
						.appendTo($sel);
				});
				$tr.append($('<td>').append($sel));
			} else if ($('#permWrap').length) {
				// The column still exists on this row, so the table does not go ragged — it simply says
				// what an owner holds, which is everything.
				$tr.append($('<td>').html('<span class="text-muted">'
					+ tMsg('ui.js.permOwnerAll', 'Owner · full access') + '</span>'));
			}
			var $acts = $('<td class="team-actions">');
			if (!removed) {
				$acts.append($('<button type="button" class="btn btn-xs btn-default">')
					.text(tMsg('ui.js.teamEditAccess', 'Edit access'))
					.on('click', function () { editTeamAccess(u); }));
			}
			// TEAM-1: the owner's row has none of these (the server refuses them too)
			if (!isOwnerRow) {
				if (!removed && u.invited) {
					$acts.append(' ', $('<button type="button" class="btn btn-xs btn-default js-team-resend">')
						.text(tMsg('ui.js.teamResend', 'Resend invitation'))
						.on('click', function () { teamAction(u, 'resend-invite', this); }));
				}
				if (removed) {
					$acts.append(' ', $('<button type="button" class="btn btn-xs btn-primary js-team-reactivate">')
						.text(tMsg('ui.js.teamReactivate', 'Reactivate'))
						.on('click', function () { teamReactivate(u, this); }));
				} else {
					$acts.append(' ', $('<button type="button" class="btn btn-xs btn-link text-danger js-team-remove">')
						.text(tMsg('ui.js.teamRemove', 'Remove'))
						.on('click', function () { teamRemove(u, this); }));
				}
			}
			$tr.append($acts);
			$tb.append($tr);
		});
	}).fail(function () {
		$('#tableTeam tbody').html('<tr><td colspan="6" class="text-center">Could not load the team.</td></tr>');
	});
}

/** Human-readable location list for a member's row. */
/**
 * Put a member on a permission set.
 *
 * ⚠ THE MESSAGE SAYS 15 MINUTES, and that is not padding. Permissions travel in the access token and
 * the token is not re-minted until it refreshes, so a change lands on the member's NEXT sign-in and
 * within a quarter of an hour at the latest. The owner accepted that trade deliberately -- instant
 * revocation costs a database read on every request -- but "I moved him off that set and he could
 * still sell" is otherwise reported as a defect rather than as the documented behaviour.
 */
$(document).on('change', '.js-permset', function () {
	var $sel = $(this);
	$.ajax({
		url: serverContext + 'team/permissions/assign', method: 'POST',
		contentType: 'application/json',
		// BLK-2: the picker is locked (disabled + aria-busy) while the change posts, so a second pick cannot race
		// the first. A <select> gets no label; the message below reports the outcome.
		busyControl: this,
		data: JSON.stringify({ userId: $sel.data('user-id'), setId: $sel.val() })
	}).done(function (resp) {
		$('#teamMsg').removeClass('alert-danger').addClass('alert-success')
			.text((resp && resp.message) || tMsg('ui.js.permAssigned',
				'Saved. It applies the next time they sign in, and within 15 minutes at the latest.'))
			.show();
	}).fail(function (xhr) {
		var body = xhr.responseJSON || {};
		$('#teamMsg').removeClass('alert-success').addClass('alert-danger')
			.text(body.message || tMsg('ui.js.permAssignFailed', 'Could not change the permission set.'))
			.show();
		loadTeamUsers();   // put the picker back to the truth rather than leaving a lie on screen
	});
});

function teamLocationNames(ids) {
	var noun = window.TEAM_LOCATION_NOUN || 'store';
	if (!ids || !ids.length) { return 'All (' + noun + '-wide)'; }
	return ids.map(function (id) {
		var hit = teamLocations.filter(function (l) { return l.id === Number(id); })[0];
		return hit ? hit.label : ('#' + id);
	}).join(', ');
}

/**
 * Reassign an existing member: pre-fill the picker with what they hold today, then save the picker's contents
 * as their COMPLETE set (replace:true), so removing a chip actually revokes access. The server re-checks the
 * caller's authority — an admin can only add or take away locations they themselves hold.
 */
function editTeamAccess(user) {
	teamSelectedLocations.clear();
	(user.locationIds || []).forEach(function (id) { teamSelectedLocations.add(Number(id)); });
	renderTeamLocations();

	var noun = window.TEAM_LOCATION_NOUN || 'store';
	teamMsg('Editing ' + (user.email || 'member') + ' — pick their ' + noun + 's below, then Save access.', false);

	// Swap the create button for a save-this-member button while an edit is in flight.
	var $save = $('#saveTeamAccess');
	if (!$save.length) {
		$save = $('<button type="button" id="saveTeamAccess" class="btn btn-primary" style="margin-left:6px">')
			.insertAfter('#addTeamUser');
	}
	$save.text(tMsg('ui.js.teamSaveAccess', 'Save access')).off('click').on('click', function () {
		$.ajax({
			type: 'POST', url: serverContext + 'assignStores', contentType: 'application/json',
			data: JSON.stringify({
				userId: user.userId,
				storeIds: selectedTeamLocationIds(),
				replace: true                        // the picker is the whole truth — omissions are revocations
			}),
			dataType: 'json',
			success: function (res) {
				if (res && (res.success || res.status === 'SUCCESS')) {
					teamMsg('Access updated for ' + (user.email || 'member') + '.', false);
					cancelTeamAccessEdit();
					loadTeamUsers();
				} else {
					teamMsg(apiMessage(res, 'Could not update access.'), true);
				}
			},
			error: function () { teamMsg('Could not update access.', true); }
		});
	});
}

function cancelTeamAccessEdit() {
	$('#saveTeamAccess').remove();
	teamSelectedLocations.clear();
	renderTeamLocations();
}

// ── Location picker (stores / branches) ───────────────────────────────────────────────────────────
// A set of toggle chips rather than a native <select multiple>: that control needs ctrl+click to pick more
// than one (undiscoverable, and unusable on touch), drops the whole selection on a stray click, and shows
// three rows at a time. Chips are one tap each and the selection is always visible.

var teamLocations = [];                  // [{id, label}]
var teamSelectedLocations = new Set();   // ids currently chosen

/** The ids the form will submit. Kept as a Set so the DOM is never the source of truth. */
function selectedTeamLocationIds() {
	return Array.from(teamSelectedLocations);
}

/** Load the location-assignment picker: stores for commerce, branches (schools) for education. */
function loadTeamLocations(done) {
	var url = window.TEAM_LOCATIONS_URL || 'getStores';
	$.get(serverContext + url, function (resp) {
		var rows = (resp && (resp.collection || resp.data)) || [];
		teamLocations = rows.map(function (loc) {
			// School calls it branchName; Store calls it name (+ an optional code).
			var label = loc.branchName || loc.name || ('#' + loc.id);
			if (loc.code) { label += ' (' + loc.code + ')'; }
			return { id: Number(loc.id), label: label };
		});
		teamSelectedLocations.clear();
		renderTeamLocations();
		if (done) { done(); }
	}, 'json').fail(function () {
		renderTeamLocations();
		if (done) { done(); }   // a missing location list must not leave the team table unrendered
	});
}

function renderTeamLocations(filter) {
	var noun = window.TEAM_LOCATION_NOUN || 'store';
	var $box = $('#teamStores').empty().addClass('locpick');

	if (!teamLocations.length) {
		// Say what to do next, rather than just reporting emptiness.
		$box.append($('<div class="locpick__empty">').text(
			'No ' + noun + 's yet — create one first, then you can assign people to it.'));
		return;
	}

	var q = (filter || '').toLowerCase();
	var shown = teamLocations.filter(function (l) { return !q || l.label.toLowerCase().indexOf(q) >= 0; });

	var $bar = $('<div class="locpick__bar">');
	var n = teamSelectedLocations.size;
	$bar.append($('<span class="locpick__count">').text(
		n ? (n + ' ' + noun + (n === 1 ? '' : 's') + ' selected')
		  : ('No ' + noun + ' selected — they will inherit (owner = all, admin = their own)')));

	var $actions = $('<span class="locpick__actions">');
	$('<button type="button" class="locpick__action">').text(tMsg('ui.js.teamSelectAll', 'Select all'))
		.prop('disabled', n === teamLocations.length)
		.on('click', function () {
			teamLocations.forEach(function (l) { teamSelectedLocations.add(l.id); });
			renderTeamLocations(filter);
		}).appendTo($actions);
	$('<button type="button" class="locpick__action">').text(tMsg('ui.js.teamClear', 'Clear'))
		.prop('disabled', n === 0)
		.on('click', function () { teamSelectedLocations.clear(); renderTeamLocations(filter); })
		.appendTo($actions);
	$bar.append($actions);
	$box.append($bar);

	// Only worth a search box once the list is long enough to scan.
	if (teamLocations.length > 8) {
		$('<input type="text" class="locpick__search">')
			.attr('placeholder', 'Filter ' + noun + 's…')
			.val(filter || '')
			.on('input', function () { renderTeamLocations($(this).val()); })
			.appendTo($box)
			.focus();
	}

	var $chips = $('<div class="locpick__chips">');
	shown.forEach(function (loc) {
		var on = teamSelectedLocations.has(loc.id);
		var $chip = $('<button type="button" class="locpick__chip">')
			.attr('aria-pressed', on ? 'true' : 'false')
			.on('click', function () {
				if (teamSelectedLocations.has(loc.id)) { teamSelectedLocations.delete(loc.id); }
				else { teamSelectedLocations.add(loc.id); }
				renderTeamLocations(filter);
			});
		$chip.append($('<span class="glyphicon glyphicon-ok locpick__chip-tick">'));
		$chip.append($('<span class="locpick__chip-label">').text(loc.label));   // .text() — never inject a name
		$chips.append($chip);
	});
	if (!shown.length) {
		$chips.append($('<div class="locpick__empty">').text(tMsg('ui.js.teamNoMatch', 'No {0} matches that.').replace('{0}', noun)));
	}
	$box.append($chips);
}

function addTeamUser() {
	var body = {
		firstName: ($('#teamFirstName').val() || '').trim(),
		lastName: ($('#teamLastName').val() || '').trim(),
		email: ($('#teamEmail').val() || '').trim(),
		role: $('#teamRole').val(),
		// The server calls the grant list storeIds whatever the vertical — it resolves stores vs schools itself.
		storeIds: selectedTeamLocationIds()
	};
	if (!body.email) { teamMsg('Please enter an email.', true); return; }
	$.ajax({
		type: 'POST', url: serverContext + 'team/users', contentType: 'application/json',
		data: JSON.stringify(body), dataType: 'json',
		success: function (resp) {
			if (resp && resp.data && resp.data.userId) {
				teamMsg('Team member added — a set-password email was sent to ' + body.email + '.', false);
				$('#teamFirstName,#teamLastName,#teamEmail').val('');
				teamSelectedLocations.clear();
				renderTeamLocations();
				loadTeamUsers();
			} else {
				teamMsg(apiMessage(resp, 'Could not add the team member.'), true);
			}
		},
		error: function (xhr) {
			var m = (xhr && xhr.responseJSON && xhr.responseJSON.message) || 'Could not add the team member. Please try again.';
			teamMsg(m, true);
		}
	});
}

/*
 * TEAM-1 (2026-10-13) — resend an invitation, remove a member (reversible), reactivate. The server decides who may
 * (owner; an admin for ordinary members; never the owner or oneself) and words every refusal — shown as-is.
 */
function teamName(u) { return u.name || u.email || ''; }
function teamAction(u, action, btn, body) {
	var $b = $(btn).prop('disabled', true);
	$.ajax({ type: 'POST', url: serverContext + 'team/users/' + encodeURIComponent(u.userId || u.id) + '/' + action,
		contentType: 'application/json', dataType: 'json', data: JSON.stringify(body || {}) })
		.done(function (r) {
			var ok = r && (r.success === true || r.status === 'SUCCESS');
			teamMsg((r && r.message) || '', !ok);
			if (ok) loadTeamUsers();
		})
		.fail(function (xhr) {
			var m = xhr && xhr.responseJSON && xhr.responseJSON.message;
			teamMsg(m || tMsg('ui.js.teamActionFailed', 'Could not do that. Please try again.'), true);
		})
		.always(function () { $b.prop('disabled', false); });
}
function teamRemove(u, btn) {
	uiPromptConfirm({
		title: tMsg('ui.js.teamRemoveQ', 'Remove {0}?').replace('{0}', teamName(u)),
		message: tMsg('ui.js.teamRemoveMsg', 'They are signed out at once and cannot sign in to this business. Nothing is deleted — you can reactivate them.'),
		input: { label: tMsg('ui.js.teamRemoveReason', 'Reason'), required: true,
				 requiredMessage: tMsg('ui.js.teamRemoveReasonNeeded', 'Say why, e.g. left the clinic.') },
		confirmText: tMsg('ui.js.teamRemove', 'Remove'), tone: 'danger'
	}).then(function (why) {
		if (why === null) return;
		teamAction(u, 'remove', btn, { reason: why });
	});
}
function teamReactivate(u, btn) {
	uiConfirm({
		title: tMsg('ui.js.teamReactivateQ', 'Reactivate {0}?').replace('{0}', teamName(u)),
		message: tMsg('ui.js.teamReactivateMsg', 'They can sign in again with their own password, on the same permission set as before.'),
		confirmText: tMsg('ui.js.teamReactivate', 'Reactivate')
	}).then(function (ok) { if (ok) teamAction(u, 'reactivate', btn); });
}

function teamMsg(msg, isErr) {
	$('#teamMsg').removeClass('alert-success alert-danger')
		.addClass(isErr ? 'alert-danger' : 'alert-success')
		.text(msg).show();
}
