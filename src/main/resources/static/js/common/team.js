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
}

function loadTeamUsers() {
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
			$tr.append($('<td>').text(u.enabled ? 'Active' : 'Pending'));
			// Where this member works, and a way to CHANGE it — assignment used to be a one-way door: you could
			// grant on creation and never move or revoke afterwards.
			$tr.append($('<td>').text(teamLocationNames(u.locationIds)));
			$tr.append($('<td>').append(
				$('<button type="button" class="btn btn-xs btn-default">')
					.text(tMsg('ui.js.teamEditAccess', 'Edit access'))
					.on('click', function () { editTeamAccess(u); })
			));
			$tb.append($tr);
		});
	}).fail(function () {
		$('#tableTeam tbody').html('<tr><td colspan="6" class="text-center">Could not load the team.</td></tr>');
	});
}

/** Human-readable location list for a member's row. */
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

function teamMsg(msg, isErr) {
	$('#teamMsg').removeClass('alert-success alert-danger')
		.addClass(isErr ? 'alert-danger' : 'alert-success')
		.text(msg).show();
}
