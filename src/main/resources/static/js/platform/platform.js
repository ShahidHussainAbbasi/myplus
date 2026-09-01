/* ============================================================================
 * platform.js — E2, the MaxTheService operator console.
 *
 * Design: microservices/docs/slices/e2-operator-portal-design.md
 *
 * WHAT THIS SCREEN IS FOR, AND WHAT IT DELIBERATELY IS NOT
 * It manages ACCOUNTS: plan, trial, entitlements, provisioning. It does not
 * show a tenant's trading data — no orders, no revenue, no stock — and there is
 * no endpoint here that could. Shopify Partners draws that line in the same
 * place, and reaching real tenant data is E5's audited support session. A "just
 * peek at their products" shortcut is how a support backdoor gets built by
 * accident.
 *
 * EVERY GATE IS SERVER-SIDE. Nothing here decides who may do what: auth-service
 * checks ROLE_ADMIN on the operator's own token for every call below. This file
 * decides what to DRAW.
 * ========================================================================== */
(function (global) {
	'use strict';

	var $ = global.jQuery;

	/** Escaped HTML — never build markup from a tenant name without it (dom-safe.js). */
	function esc(s) {
		return (global.escHtml ? global.escHtml(String(s == null ? '' : s))
		                       : String(s == null ? '' : s));
	}

	/** t() with a fallback, so a missing bundle key degrades to English rather than to the key itself. */
	function t(key, fallback) {
		var v = global.t ? global.t(key) : key;
		return (!v || v === key) ? fallback : v;
	}

	var state = { page: 0, size: 25, q: '', total: 0 };

	// ── the tenants list ────────────────────────────────────────────────────────────────────────

	/**
	 * Fetch one page. SERVER-side search and paging, deliberately: 40 tenants today and the query is
	 * written for 40,000. A client-side filter over everything would work for a year and then have to be
	 * undone, along with everything built on top of it.
	 */
	function loadTenants() {
		var url = serverContext + 'platform/organizations?page=' + state.page + '&size=' + state.size
			+ (state.q ? '&q=' + encodeURIComponent(state.q) : '');

		$('#platTenantList').html('<div class="plat__loading">' + esc(t('ui.js.loading', 'Loading…')) + '</div>');

		$.get(url).done(function (res) {
			if (!apiOk(res)) {
				$('#platTenantList').html('<div class="plat__empty">'
					+ esc(apiMessage(res, t('ui.js.couldNotLoadTenants', 'Could not load tenants.'))) + '</div>');
				return;
			}
			renderTenants(apiData(res) || {});
		}).fail(function (xhr) {
			// The server's own sentence wins; the hard-coded string is only a fallback for when it sent none.
			$('#platTenantList').html('<div class="plat__empty">'
				+ esc(apiFailMessage(xhr, t('ui.js.couldNotLoadTenants', 'Could not load tenants.'))) + '</div>');
		});
	}

	function renderTenants(data) {
		var rows = data.rows || [];
		state.total = data.total || 0;

		$('#platCount').text(state.q
			? t('ui.js.nOfMTenants', '{0} of {1} tenants').replace('{0}', rows.length).replace('{1}', state.total)
			: t('ui.js.nTenants', '{0} tenants').replace('{0}', state.total));

		if (!rows.length) {
			$('#platTenantList').html('<div class="plat__empty">'
				+ esc(t('ui.js.noTenantsMatch', 'No tenants match.')) + '</div>');
			$('#platPager').empty();
			return;
		}

		var html = '';
		rows.forEach(function (o) {
			/*
			 * The lapsed-trial badge is the one piece of colour on a row, and it is the reason this screen
			 * exists in the shape it does: 14 of 20 trials are lapsed right now and nothing anywhere says so.
			 *
			 * `trialLapsed` is computed SERVER-side. Deriving it here from trialEndsAt would be a second
			 * definition of "lapsed" in JavaScript, competing with the one the entitlement resolver uses to
			 * decide what the customer may actually do.
			 */
			var lapsed = o.trialLapsed
				? '<span class="plat-badge plat-badge--lapsed" data-testid="trial-lapsed">'
					+ '<span class="glyphicon glyphicon-warning-sign"></span> '
					+ esc(t('ui.js.trialLapsed', 'Trial lapsed')) + '</span>'
				: '';

			html += '<div class="plat-row" data-testid="tenant-row" data-org="' + esc(o.id) + '">'
				+   '<div class="plat-row__main">'
				+     '<div class="plat-row__name">' + esc(o.name) + '</div>'
				+     '<div class="plat-row__meta">' + esc(o.ownerEmail || '—')
				+       ' · ' + esc(t('ui.js.nMembers', '{0} members').replace('{0}', o.memberCount || 0))
				+     '</div>'
				+   '</div>'
				+   '<div class="plat-row__tags">'
				+     '<span class="plat-badge plat-badge--plan">' + esc(o.plan) + '</span>'
				+     lapsed
				+   '</div>'
				+   '<span class="glyphicon glyphicon-chevron-right plat-row__go"></span>'
				+ '</div>';
		});
		$('#platTenantList').html(html);
		renderPager();
	}

	function renderPager() {
		var pages = Math.ceil(state.total / state.size);
		if (pages <= 1) { $('#platPager').empty(); return; }
		$('#platPager').html(
			'<button type="button" class="btn btn-default" id="platPrev"' + (state.page <= 0 ? ' disabled' : '') + '>‹</button>'
			+ '<span class="plat__pageno">' + (state.page + 1) + ' / ' + pages + '</span>'
			+ '<button type="button" class="btn btn-default" id="platNext"'
			+ (state.page >= pages - 1 ? ' disabled' : '') + '>›</button>');
	}

	// ── one tenant ──────────────────────────────────────────────────────────────────────────────

	function openTenant(orgId, name) {
		$('#platTenants').hide();
		$('#platProvision').hide();
		$('#platDetail').show();
		$('#platDetailBody').html('<div class="plat__loading">' + esc(t('ui.js.loading', 'Loading…')) + '</div>');

		$.get(serverContext + 'platform/entitlements?organizationId=' + encodeURIComponent(orgId))
			.done(function (res) {
				if (!apiOk(res)) {
					$('#platDetailBody').html('<div class="plat__empty">' + esc(apiMessage(res, '')) + '</div>');
					return;
				}
				renderTenant(apiData(res) || {}, orgId);
			})
			.fail(function (xhr) {
				$('#platDetailBody').html('<div class="plat__empty">' + esc(apiFailMessage(xhr, '')) + '</div>');
			});
	}

	function renderTenant(d, orgId) {
		var caps = d.capabilities || [];

		var planOptions = ['FREE', 'TRIAL', 'PRO', 'DEMO'].map(function (p) {
			return '<option value="' + p + '"' + (p === d.plan ? ' selected' : '') + '>' + p + '</option>';
		}).join('');

		var html = '<h3 class="plat-detail__name">' + esc(d.organizationName || '') + '</h3>'
			+ '<section class="plat-card">'
			+   '<header class="plat-card__head"><h4>' + esc(t('ui.js.plan', 'Plan')) + '</h4></header>'
			+   '<div class="plat-card__body plat-plan">'
			+     '<select class="form-control" id="platPlanSelect">' + planOptions + '</select>'
			+     '<button type="button" class="btn btn-primary" id="platPlanSave">'
			+       esc(t('ui.js.changePlan', 'Change plan')) + '</button>'
			+     (d.trialEndsAt ? '<span class="plat-plan__trial">'
					+ esc(t('ui.js.trialEnds', 'Trial ends')) + ' ' + esc(String(d.trialEndsAt).substring(0, 10))
					+ '</span>' : '')
			+   '</div>'
			+ '</section>'

			+ '<section class="plat-card">'
			+   '<header class="plat-card__head"><h4>' + esc(t('ui.js.capabilities', 'Capabilities')) + '</h4>'
			+     '<span class="plat-card__n">' + caps.length + '</span></header>'
			/*
			 * Stated where the operator acts, not buried in a release note. Capabilities travel in the JWT
			 * (E1 ruling D-1), so a change reaches the tenant at their next token refresh — within 15
			 * minutes. An operator who does not know that reports a working system as broken.
			 */
			+   '<p class="plat-card__note">' + esc(t('ui.js.entitlementLatency',
					'Changes reach the tenant within 15 minutes, when their session next refreshes.')) + '</p>'
			+   '<div class="plat-caps">';

		caps.forEach(function (c) {
			/*
			 * Three distinct states, shown apart on purpose. "Revoked by us" and "switched off by the
			 * tenant" look identical to a customer and are opposite problems for an operator: without the
			 * distinction they will "fix" an entitlement that was never what was wrong.
			 */
			var status = c.revoked
				? '<span class="plat-badge plat-badge--revoked">' + esc(t('ui.js.revoked', 'Revoked')) + '</span>'
				: (c.grantable
					? '<span class="plat-badge plat-badge--ok">' + esc(t('ui.js.entitled', 'Entitled')) + '</span>'
					: '<span class="plat-badge plat-badge--notinplan">'
						+ esc(t('ui.js.notInPlan', 'Not in plan')) + '</span>');

			html += '<div class="plat-cap" data-cap="' + esc(c.capability) + '">'
				+     '<div class="plat-cap__text">'
				+       '<div class="plat-cap__label">' + esc(c.label) + '</div>'
				+       '<div class="plat-cap__help">' + esc(c.help || '') + '</div>'
				+       (c.reason ? '<div class="plat-cap__reason">' + esc(c.reason) + '</div>' : '')
				+     '</div>'
				+     status
				+     '<div class="plat-cap__actions">'
				+       '<button type="button" class="btn btn-xs btn-default js-grant">'
				+         esc(t('ui.js.grant', 'Grant')) + '</button>'
				+       '<button type="button" class="btn btn-xs btn-default js-revoke">'
				+         esc(t('ui.js.revoke', 'Revoke')) + '</button>'
				+     '</div>'
				+   '</div>';
		});

		html += '</div></section>';
		$('#platDetailBody').html(html);
		$('#platDetailBody').data('org', orgId);
	}

	/**
	 * Grant or revoke, with a REASON.
	 *
	 * The reason is asked for through the shared dialog and enforced by the server — never window.confirm
	 * (the shared-dialog rule), and never validated only here. A UI-only requirement is not a requirement:
	 * the endpoint is reachable without this screen.
	 */
	function setEntitlement(orgId, capability, status) {
		var isRevoke = status === 'SUSPENDED';
		return global.uiPromptConfirm({
			title: isRevoke ? t('ui.js.revokeCapability', 'Revoke capability')
			                : t('ui.js.grantCapability', 'Grant capability'),
			message: capability,
			input: { label: t('ui.js.reason', 'Reason') },
			confirmText: isRevoke ? t('ui.js.revoke', 'Revoke') : t('ui.js.grant', 'Grant')
		}).then(function (reason) {
			if (reason === null) return;   // cancelled
			return $.post(serverContext + 'platform/entitlement', {
				organizationId: orgId, capability: capability, status: status, reason: reason
			}).done(function (res) {
				if (!apiOk(res)) {
					global.uiAlert(apiMessage(res, t('ui.js.saveFailed', 'Save failed')));
					return;
				}
				openTenant(orgId);
			}).fail(function (xhr) {
				global.uiAlert(apiFailMessage(xhr, t('ui.js.saveFailed', 'Save failed')));
			});
		});
	}

	// ── wiring ──────────────────────────────────────────────────────────────────────────────────

	$(function () {
		loadTenants();

		// Focus the search only on a real pointer device. focus-flow's rule: no auto-focus on touch or
		// below 992px, because a keyboard springing up on open is hostile on a phone.
		if (global.innerWidth >= 992 && !('ontouchstart' in global)) $('#platSearch').trigger('focus');

		var searchTimer = null;
		$('#platSearch').on('input', function () {
			var v = this.value;
			// Debounced: one request per pause, not per keystroke. The search is a database query.
			clearTimeout(searchTimer);
			searchTimer = setTimeout(function () {
				state.q = v;
				state.page = 0;
				loadTenants();
			}, 250);
		});

		$('#platPager').on('click', '#platPrev', function () { state.page--; loadTenants(); });
		$('#platPager').on('click', '#platNext', function () { state.page++; loadTenants(); });

		$('#platTenantList').on('click', '.plat-row', function () {
			openTenant($(this).attr('data-org'));
		});

		$('#platBack').on('click', function () {
			$('#platDetail').hide();
			$('#platTenants').show();
			loadTenants();
		});

		$('#platDetailBody').on('click', '.js-grant', function () {
			setEntitlement($('#platDetailBody').data('org'), $(this).closest('.plat-cap').attr('data-cap'), 'ACTIVE');
		});
		$('#platDetailBody').on('click', '.js-revoke', function () {
			setEntitlement($('#platDetailBody').data('org'), $(this).closest('.plat-cap').attr('data-cap'), 'SUSPENDED');
		});

		$('#platDetailBody').on('click', '#platPlanSave', function () {
			var orgId = $('#platDetailBody').data('org');
			var plan = $('#platPlanSelect').val();
			global.uiPromptConfirm({
				title: t('ui.js.changePlan', 'Change plan'),
				message: plan,
				input: { label: t('ui.js.reason', 'Reason') }
			}).then(function (reason) {
				if (reason === null) return;
				$.post(serverContext + 'platform/plan', { organizationId: orgId, plan: plan, reason: reason })
					.done(function (res) {
						if (!apiOk(res)) { global.uiAlert(apiMessage(res, '')); return; }
						openTenant(orgId);
					})
					.fail(function (xhr) { global.uiAlert(apiFailMessage(xhr, '')); });
			});
		});

		// ── provisioning ────────────────────────────────────────────────────────────────────────
		$('#platProvisionBtn').on('click', function () {
			$('#platTenants').hide();
			$('#platDetail').hide();
			$('#platProvision').show();
		});
		$('#platProvisionBack').on('click', function () {
			$('#platProvision').hide();
			$('#platTenants').show();
			loadTenants();
		});
		$('#provSubmit').on('click', function () {
			$.post(serverContext + 'platform/provisionTenant', {
				organizationName: $('#provOrgName').val(),
				email: $('#provEmail').val(),
				firstName: $('#provFirstName').val(),
				lastName: $('#provLastName').val(),
				plan: $('#provPlan').val(),
				userType: 'BUSINESS'
			}).done(function (res) {
				if (!apiOk(res)) { global.uiAlert(apiMessage(res, '')); return; }
				global.uiAlert(apiMessage(res, t('ui.js.tenantProvisioned', 'Tenant provisioned.')));
				$('#platProvision').hide();
				$('#platTenants').show();
				state.q = '';
				$('#platSearch').val('');
				loadTenants();
			}).fail(function (xhr) { global.uiAlert(apiFailMessage(xhr, '')); });
		});
	});
})(window);
