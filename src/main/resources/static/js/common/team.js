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
function showTeam() {
	$('.formDiv').hide();
	$('#TeamDiv').show();
	$('#teamMsg').hide();
	loadTeamUsers();
	loadTeamLocations();
}

function loadTeamUsers() {
	$.get(serverContext + 'team/users', function (resp) {
		var users = (resp && resp.data) ? resp.data : [];
		var $tb = $('#tableTeam tbody').empty();
		if (!users.length) {
			$tb.append('<tr><td colspan="4" class="text-center">No team members yet.</td></tr>');
			return;
		}
		users.forEach(function (u) {
			$tb.append('<tr><td>' + escHtml(u.name || '') + '</td><td>' + escHtml(u.email || '')
				+ '</td><td>' + escHtml(u.role || '') + '</td><td>' + (u.enabled ? 'Active' : 'Pending') + '</td></tr>');
		});
	}).fail(function () {
		$('#tableTeam tbody').html('<tr><td colspan="4" class="text-center">Could not load the team.</td></tr>');
	});
}

/** Populate the location-assignment picker: stores for commerce, branches (schools) for education. */
function loadTeamLocations() {
	var url = window.TEAM_LOCATIONS_URL || 'getStores';
	$.get(serverContext + url, function (resp) {
		var rows = (resp && (resp.collection || resp.data)) || [];
		var $s = $('#teamStores').empty();
		rows.forEach(function (loc) {
			// School calls it branchName; Store calls it name (+ an optional code).
			var label = loc.branchName || loc.name || ('#' + loc.id);
			if (loc.code) { label += ' (' + loc.code + ')'; }
			$s.append($('<option>').val(loc.id).text(label));
		});
	}, 'json');
}

function addTeamUser() {
	var body = {
		firstName: ($('#teamFirstName').val() || '').trim(),
		lastName: ($('#teamLastName').val() || '').trim(),
		email: ($('#teamEmail').val() || '').trim(),
		role: $('#teamRole').val(),
		// The server calls the grant list storeIds whatever the vertical — it resolves stores vs schools itself.
		storeIds: ($('#teamStores').val() || []).map(function (v) { return Number(v); })
	};
	if (!body.email) { teamMsg('Please enter an email.', true); return; }
	$.ajax({
		type: 'POST', url: serverContext + 'team/users', contentType: 'application/json',
		data: JSON.stringify(body), dataType: 'json',
		success: function (resp) {
			if (resp && resp.data && resp.data.userId) {
				teamMsg('Team member added — a set-password email was sent to ' + body.email + '.', false);
				$('#teamFirstName,#teamLastName,#teamEmail').val('');
				$('#teamStores').val([]);
				loadTeamUsers();
			} else {
				teamMsg((resp && resp.message) || 'Could not add the team member.', true);
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
