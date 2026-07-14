/**
 * Active-organization (tenant) switcher — ONE implementation for every dashboard.
 *
 * A user can belong to several organizations: one they OWN, plus any they were added to as a member. Active-org
 * resolution prefers an org you own, so without this control a multi-org user is permanently pinned to their own
 * org — they can never work inside an org they are merely a member of, and records they create there are
 * invisible from it. That is precisely the confusion this fixes: the education dashboard had the switcher, the
 * commerce dashboard never did.
 *
 * Switching asks auth-service to re-issue the session JWT scoped to the chosen org, so the page must reload —
 * every screen then reads and writes inside the new tenant.
 *
 * Markup contract (identical on every dashboard):
 *   <span id="orgSwitcherLi"><select id="orgSwitcher"></select></span>
 */
function loadMyOrganizations() {
	$.get(serverContext + 'getMyOrganizations', function (res) {
		var $sel = $('#orgSwitcher');
		if (!res || res.status !== 'SUCCESS' || !res.collection || res.collection.length === 0) {
			// No tenant context (legacy mode / no orgs) — nothing to show.
			$('#orgSwitcherLi').hide();
			return;
		}
		$sel.empty();
		$.each(res.collection, function (i, org) {
			var $o = $('<option>').val(org.id).text(org.name);   // .text() — never inject a tenant's name
			if (org.active) { $o.prop('selected', true); }
			$sel.append($o);
		});
		// Shown even for a single org: "which tenant am I in" is the context that was missing.
		$('#orgSwitcherLi').show();
	}).fail(function () {
		$('#orgSwitcherLi').hide();
	});
}

function switchOrganization() {
	var orgId = $('#orgSwitcher').val();
	if (!orgId) { return; }
	$.ajax({
		url: serverContext + 'switchOrganization',
		type: 'POST',
		data: { organizationId: orgId },
		success: function (res) {
			if (res && res.status === 'SUCCESS') {
				// The session token is now scoped to the new tenant — reload so every section refetches.
				window.location.reload();
			} else {
				alert((res && res.message) ? res.message : 'Could not switch organization');
				loadMyOrganizations();
			}
		},
		error: function () {
			alert('Could not switch organization');
			loadMyOrganizations();
		}
	});
}

// Every dashboard that ships the markup above gets the behaviour — no per-page wiring to forget.
$(document).ready(function () {
	if ($('#orgSwitcher').length) {
		loadMyOrganizations();
		$(document).on('change', '#orgSwitcher', switchOrganization);
	}
});
