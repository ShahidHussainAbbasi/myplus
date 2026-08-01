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
/**
 * "Name — Module" for the switcher. Falls back to the bare name when the org has no type (every tenant
 * created before Organization.type was populated), so those users see exactly what they see today.
 */
function orgLabel(org) {
	if (!org || !org.type) { return (org && org.name) ? org.name : ''; }
	// ui.js.* is the ONLY prefix JsMessageSource ships to the browser (see LocaleInterceptor) — a
	// plain ui.module.* key would never resolve here and every org would silently show a bare name.
	var key = 'ui.js.module.' + String(org.type).toLowerCase();
	var label = (typeof t === 'function') ? t(key) : null;
	// t() returns the key itself when a translation is missing — never show "ui.module.business" to a user.
	if (!label || label === key) { return org.name; }
	return org.name + ' — ' + label;
}

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
			// B2B P0.5: label each org with its MODULE. A customer running "Springfield" as both a school and
			// a shop sees two identical names otherwise — and picking the wrong one silently lands them in the
			// wrong module. .text() throughout: a tenant name is never injected as HTML.
			var $o = $('<option>').val(org.id).text(orgLabel(org));
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
				// B2B P0.5: go through /dashboard rather than reloading this page. The new tenant may be a
				// DIFFERENT module, and a reload would keep you on (say) the commerce dashboard while every
				// call underneath it is now scoped to a school. /dashboard re-decides server-side via
				// ModuleRouter, so the browser never has to know the type→dashboard map.
				window.location = serverContext + 'dashboard';
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
