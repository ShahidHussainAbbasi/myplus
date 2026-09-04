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

	/*
	 * NL, rather than a \n escape inside these concatenated strings.
	 *
	 * A \n written here reached target/classes as a REAL line break inside a
	 * single-quoted string literal, so the browser refused the whole file with "Invalid or unexpected
	 * token" and every screen that loads it went blank. Cypress reported it as an application error
	 * during session setup, which points at the app rather than at the edit that caused it.
	 *
	 * fromCharCode(10) cannot be rewritten by anything that processes escape sequences, so the class of
	 * failure is removed rather than guarded against.
	 */
	var NL = String.fromCharCode(10);


	/** Shape code -> the words an owner sees on their own Configuration screen. One vocabulary, both screens. */
	function shapeLabel(code) {
		return {
			retail:       t('ui.js.shapeRetail', 'Retail counter / POS'),
			pharmacy:     t('ui.js.shapePharmacy', 'Pharmacy / dispensing'),
			distribution: t('ui.js.shapeDistribution', 'Distribution / wholesale'),
			storefront:   t('ui.js.shapeStorefront', 'Online storefront'),
			general:      t('ui.js.shapeGeneral', 'General - show every feature')
		}[String(code || '').toLowerCase()] || code;
	}

	/**
	 * ONB-3 — the DATA consequences of a business-type change, as sentences.
	 *
	 * Numbers, not adjectives: "19 products will stop selling" is actionable, "some products may be affected"
	 * is not. And the CONSEQUENCE, not the mechanism — an operator does not care that assertEnabled refuses,
	 * they care that the handsets stop scanning.
	 *
	 * Returns an empty array when there is nothing to warn about. A dialog that always warns is one people
	 * learn to click through, which costs more than never having warned.
	 */
	function impactLines(impact) {
		var out = [];
		if (!impact) return out;
		var serial = Number(impact.productsRequiringSerial || 0);
		var plans = Number(impact.openInstallmentPlans || 0);
		var owed = Number(impact.installmentsOutstanding || 0);

		if (serial > 0) {
			out.push(t('ui.js.impactSerial', '{0} products require a serial number and will stop selling.')
				.replace('{0}', serial));
		}
		// The SILENT one: FEFO simply has no dates to sort on, and no error names the cause.
		if (impact.productsTrackingBatch !== undefined && Number(impact.productsTrackingBatch) === 0) {
			out.push(t('ui.js.impactNoBatches',
				'No products have a batch recorded — expiry ordering will have nothing to sort on.'));
		}
		if (plans > 0) {
			out.push(t('ui.js.impactPlans',
				'{0} open installment plans ({1}) stay collectable but leave the dashboard.')
				.replace('{0}', plans).replace('{1}', owed.toLocaleString()));
		}
		return out;
	}

	/*
	 * ── ONB-3: the cleanup list ──────────────────────────────────────────────────────────────────
	 *
	 * The dialog warns; this is where somebody can DO something about it. A warning an operator cannot act on
	 * is advice, not a feature — and the stranded stock outlives the dialog, so the panel has to live on the
	 * tenant detail rather than only in the moment after the switch. An operator who walked away, or who
	 * inherited the tenant from somebody who did, finds it here.
	 *
	 * Only tracking capabilities appear: these are the two whose PRODUCT POLICY can outlive the capability.
	 * Installments cannot strand anything the same way — an open plan stays collectable and the money is
	 * still chased; the dialog says so and there is nothing to clean up.
	 */
	var CLEANUP_CAPS = [
		['serialTracking', 'ui.js.serialRequirement', 'Serial / IMEI requirement'],
		['batchTracking', 'ui.js.batchRequirement', 'Batch tracking']
	];

	/**
	 * Render one card per capability that is OFF and still has products demanding it.
	 *
	 * <p>Costs nothing on the common path: a tenant whose tracking capabilities are on makes no request at
	 * all, and one whose capability is off but has no stranded product gets no card. The check is the
	 * capability's own `enabled` — the resolver's answer, the same one enforcement uses — never a guess from
	 * the shape.
	 *
	 * <p>A failed fetch renders nothing and says nothing. This panel is an aid; it must never be the reason
	 * an operator cannot see a tenant's plan.
	 */
	function renderCleanup(orgId, caps) {
		var $box = $('#platConflicts').empty();
		CLEANUP_CAPS.forEach(function (c) {
			var off = (caps || []).some(function (row) {
				return row.capability === c[0] && row.enabled === false;
			});
			if (!off) return;
			$.get(serverContext + 'platform/policyConflicts?organizationId=' + encodeURIComponent(orgId)
					+ '&capability=' + encodeURIComponent(c[0]))
				.done(function (res) {
					if (!apiOk(res)) return;
					var rows = (apiData(res) || {}).rows || [];
					if (!rows.length) return;      // nothing stranded -> nothing shown, same rule as the dialog
					$box.append(conflictCard(c, rows));
				});
		});
	}

	/**
	 * One cleanup card: the count, the products by name, and the single button that frees them.
	 *
	 * <p>Named, not just counted. "19 products" tells an operator the size of the problem; the names tell them
	 * whether it is the handset range or one forgotten test item, and those call for different decisions.
	 * Capped at ten because the card is a prompt to act, not the inventory screen.
	 */
	function conflictCard(cap, rows) {
		var names = rows.slice(0, 10).map(function (p) { return esc(p.name || ''); }).join(' · ');
		if (rows.length > 10) names += ' …';
		return '<section class="plat-card" data-testid="policy-conflicts" data-conflict-cap="'
			+ esc(cap[0]) + '">'
			+   '<header class="plat-card__head"><h4>'
			+     esc(t('ui.js.policyConflicts', 'Products needing attention')) + '</h4>'
			+     '<span class="plat-card__n" data-testid="policy-conflict-count">' + rows.length
			+     '</span></header>'
			+   '<p class="plat-card__note">' + esc(t('ui.js.policyConflictsHelp',
					'These products require something this business type can no longer record, so they will not '
					+ 'sell until the requirement is cleared.')) + '</p>'
			+   '<div class="plat-card__body">'
			+     '<div class="plat-cap__help">' + esc(t(cap[1], cap[2])) + ' — ' + names + '</div>'
			+     '<button type="button" class="btn btn-default js-clear-policy" '
			+       'data-testid="clear-policy">'
			+       esc(t('ui.js.clearOnAll', 'Clear on all {0}').replace('{0}', rows.length)) + '</button>'
			+   '</div>'
			+ '</section>';
	}

	var state = { page: 0, size: 25, q: '', total: 0, needsType: false };

	// ── the tenants list ────────────────────────────────────────────────────────────────────────

	/**
	 * Fetch one page. SERVER-side search and paging, deliberately: 40 tenants today and the query is
	 * written for 40,000. A client-side filter over everything would work for a year and then have to be
	 * undone, along with everything built on top of it.
	 */
	function loadTenants() {
		var url = serverContext + 'platform/organizations?page=' + state.page + '&size=' + state.size
			+ (state.q ? '&q=' + encodeURIComponent(state.q) : '')
			+ (state.needsType ? '&needsType=true' : '');

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

			/*
			 * E3 — a stopped customer must be visible at a glance. This is the whole reason `status` is on
			 * the row: E2 printed the field while nothing enforced it, which is worse than not showing it.
			 */
			var stopped = (o.status && String(o.status).toUpperCase() !== 'ACTIVE')
				? '<span class="plat-badge plat-badge--stopped" data-testid="status-' + esc(String(o.status).toLowerCase()) + '">'
					+ '<span class="glyphicon glyphicon-ban-circle"></span> '
					+ esc(t('ui.js.status' + String(o.status).charAt(0) + String(o.status).slice(1).toLowerCase(),
						String(o.status))) + '</span>'
				: '';

			/*
			 * ONB-1 — the remediation worklist.
			 *
			 * A tenant that has never been asked what it is resolves to GENERAL, whose preset is EVERY
			 * capability — so it sees the whole product. Nothing backfills those, and nothing safely could:
			 * the platform cannot know what trade a customer is in, and guessing would put a pharmacy on
			 * retail and hide their expiry tracking.
			 *
			 * `shapeSet` is the RAW answer — whether anybody ever chose — which is the only one that makes a
			 * worklist. The effective answer says GENERAL for "never asked" and for "deliberately chose
			 * General business" alike, and an operator working through these needs to tell them apart.
			 */
			var noShape = (o.shapeSet === false)
				? '<span class="plat-badge plat-badge--noshape" data-testid="no-business-type">'
					+ '<span class="glyphicon glyphicon-question-sign"></span> '
					+ esc(t('ui.js.noBusinessType', 'No business type')) + '</span>'
				: '';

			/*
			 * ONB-2 — WHAT this business is, on the row.
			 *
			 * Without it an operator cannot tell org 20 from org 44 without opening both, and at 39 tenants that
			 * is the difference between reading a list and clicking through 39 pages. Shown only when a type was
			 * actually chosen: an unset tenant already carries the "No business type" badge, and two badges saying
			 * the same thing is noise.
			 */
			var shapeBadge = (o.shapeSet !== false && o.shape)
				? '<span class="plat-badge plat-badge--shape" data-testid="tenant-shape">'
					+ esc(shapeLabel(o.shape)) + '</span>'
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
				+     shapeBadge
				+     stopped
				+     noShape
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

		var currentStatus = String(d.status || 'ACTIVE').toUpperCase();
		var statusOptions = ['ACTIVE', 'SUSPENDED', 'CLOSED'].map(function (st) {
			return '<option value="' + st + '"' + (st === currentStatus ? ' selected' : '') + '>' + st + '</option>';
		}).join('');

		var currentShape = String(d.shape || 'general').toLowerCase();
		var shapeOptions = [
			['retail', t('ui.js.shapeRetail', 'Retail counter / POS')],
			['pharmacy', t('ui.js.shapePharmacy', 'Pharmacy / dispensing')],
			['distribution', t('ui.js.shapeDistribution', 'Distribution / wholesale')],
			['storefront', t('ui.js.shapeStorefront', 'Online storefront')],
			['general', t('ui.js.shapeGeneral', 'General business')]
		].map(function (o) {
			return '<option value="' + o[0] + '"' + (o[0] === currentShape ? ' selected' : '') + '>'
				+ esc(o[1]) + '</option>';
		}).join('');

		var html = '<h3 class="plat-detail__name">' + esc(d.organizationName || '') + '</h3>'
			/*
			 * ONB-1 — the business type comes FIRST, above plan and status.
			 *
			 * It is the question that decides what the customer sees at all; plan and status decide what they
			 * may do and whether they may trade. An operator correcting a wrongly-onboarded tenant is looking
			 * for this, and it used to be reachable only from the tenant's own Configuration screen.
			 */
			+ '<section class="plat-card">'
			+   '<header class="plat-card__head"><h4>' + esc(t('ui.js.businessType', 'Business type')) + '</h4></header>'
			+   '<div class="plat-card__body plat-plan">'
			+     '<select class="form-control" id="platShapeSelect">' + shapeOptions + '</select>'
			+     '<button type="button" class="btn btn-default" id="platShapeSave">'
			+       esc(t('ui.js.changeBusinessType', 'Change business type')) + '</button>'
			+   '</div>'
			+ '</section>'
			/*
			 * ONB-3 — the aftermath of a business-type change sits directly beneath the control that causes
			 * it. Filled asynchronously and usually empty; a tenant with nothing stranded never sees a
			 * heading about it.
			 */
			+ '<div id="platConflicts"></div>'
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
			/*
			 * E3 — status sits in the PLAN card because plan and status are the two commercial facts and an
			 * operator reasons about them together: "are they paying, and are they trading?"
			 */
			+   '<div class="plat-card__body plat-plan plat-plan--status">'
			+     '<select class="form-control" id="platStatusSelect">' + statusOptions + '</select>'
			+     '<button type="button" class="btn btn-default" id="platStatusSave">'
			+       esc(t('ui.js.updateStatus', 'Update status')) + '</button>'
			+     (currentStatus !== 'ACTIVE'
					? '<span class="plat-badge plat-badge--stopped"><span class="glyphicon glyphicon-ban-circle"></span> '
						+ esc(currentStatus) + '</span>'
					: '')
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
		// ONB-3 — after the markup exists, never before: renderCleanup writes into #platConflicts.
		renderCleanup(orgId, caps);
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

		/*
		 * ONB-2 — the worklist filter. A segmented control rather than a dropdown: three states, one click, and
		 * the current one visible without opening anything. `general` counts as "needs a type" per the ruling —
		 * it is a legitimate answer AND an unanswered question, and only a person can tell which.
		 */
		$('#platNeedsType').on('click', 'button', function () {
			var mode = this.getAttribute('data-mode');
			$('#platNeedsType button').removeClass('is-on');
			$(this).addClass('is-on');
			state.needsType = (mode === 'needs');
			state.page = 0;
			loadTenants();
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

		/*
		 * E3 — start or stop a tenant trading.
		 *
		 * The confirm text says the BLAST RADIUS plainly, because an operator must not discover it afterwards:
		 * everyone at that business is signed out and cannot log back in. And it states the timing, which
		 * differs from an entitlement change — immediate at the door, up to 15 minutes for anyone already
		 * signed in. An operator who assumes otherwise suspends a tenant and watches them keep working.
		 */
		$('#platDetailBody').on('click', '#platStatusSave', function () {
			var orgId = $('#platDetailBody').data('org');
			var status = $('#platStatusSelect').val();
			var stopping = status !== 'ACTIVE';
			global.uiPromptConfirm({
				title: t('ui.js.updateStatus', 'Update status'),
				message: stopping
					? t('ui.js.suspendWarning',
						'Everyone at this business will be signed out and unable to log in. Anyone already '
						+ 'signed in loses access within 15 minutes.')
					: t('ui.js.reactivateNote', 'Access is restored immediately.'),
				input: { label: t('ui.js.reason', 'Reason') },
				confirmText: status
			}).then(function (reason) {
				if (reason === null) return;
				$.post(serverContext + 'platform/status', { organizationId: orgId, status: status, reason: reason })
					.done(function (res) {
						if (!apiOk(res)) { global.uiAlert(apiMessage(res, '')); return; }
						openTenant(orgId);
					})
					.fail(function (xhr) { global.uiAlert(apiFailMessage(xhr, '')); });
			});
		});

		/*
		 * ONB-1 — change the business type, RE-APPLYING that shape's defaults.
		 *
		 * This is a deliberate reversal of C4's "an explicit override always wins", and the confirmation is
		 * the entire reason it is safe: the objection to re-applying was that it would happen SILENTLY. So the
		 * dialog names the switches that will change, computed server-side from THIS tenant's current state —
		 * generic prose would be the "are you sure?" that teaches operators to click through.
		 */
		$('#platDetailBody').on('click', '#platShapeSave', function () {
			var orgId = $('#platDetailBody').data('org');
			var shape = $('#platShapeSelect').val();

			$.get(serverContext + 'platform/shapePreview?organizationId=' + encodeURIComponent(orgId)
					+ '&shape=' + encodeURIComponent(shape))
				.done(function (res) {
					if (!apiOk(res)) { global.uiAlert(apiMessage(res, '')); return; }
					var p = apiData(res) || {};
					var on = p.turningOn || [], off = p.turningOff || [];

					var lines = [];
					if (off.length) lines.push(t('ui.js.turningOff', 'Turning OFF') + ': ' + off.join(' · '));
					if (on.length) lines.push(t('ui.js.turningOn', 'Turning ON') + ': ' + on.join(' · '));
					// ONB-3 — what it will BREAK, beneath what it will switch. The capability diff says what
					// changes; these say what stops working, which is the half an operator can act on.
					impactLines(p.impact).forEach(function (l) { lines.push(NL + '! ' + l); });
					// A dialog that lists nothing when nothing changes is far more useful than one that
					// always warns — an operator learns to read it because it is not always the same.
					if (!lines.length) lines.push(t('ui.js.noSwitchesChange', 'No switches change for this business.'));

					global.uiPromptConfirm({
						title: t('ui.js.changeBusinessType', 'Change business type'),
						message: t('ui.js.shapeResetsSwitches',
							'This resets the switches under "What this business does" to the defaults for the '
							+ 'new business type.') + '\n\n' + lines.join('\n'),
						input: { label: t('ui.js.reason', 'Reason') }
					}).then(function (reason) {
						if (reason === null) return;
						$.post(serverContext + 'platform/shape',
								{ organizationId: orgId, shape: shape, reason: reason })
							.done(function (r2) {
								if (!apiOk(r2)) { global.uiAlert(apiMessage(r2, '')); return; }
								openTenant(orgId);
							})
							.fail(function (xhr) { global.uiAlert(apiFailMessage(xhr, '')); });
					});
				})
				.fail(function (xhr) { global.uiAlert(apiFailMessage(xhr, '')); });
		});

		/*
		 * ONB-3 — the cleanup list's one button.
		 *
		 * CLEAR ONLY, and no value travels: catalog's endpoint cannot set a flag at all, which is what keeps
		 * C6's rule intact for exactly the tenants that have just lost the capability — they may remove a
		 * product policy, never grant themselves one.
		 *
		 * Confirmed, because it is bulk and one-way from here: the business type no longer permits setting
		 * the requirement back, so the dialog says that rather than letting the operator discover it.
		 */
		$('#platDetailBody').on('click', '.js-clear-policy', function () {
			var orgId = $('#platDetailBody').data('org');
			var $card = $(this).closest('[data-conflict-cap]');
			var cap = $card.attr('data-conflict-cap');
			var n = $card.find('.plat-card__n').text();

			global.uiConfirm({
				title: t('ui.js.policyConflicts', 'Products needing attention'),
				message: t('ui.js.clearPolicyConfirm',
					'This removes the requirement from {0} product(s) so they can sell again. It cannot be put '
					+ 'back from here — the business type no longer allows setting it.').replace('{0}', n),
				confirmText: t('ui.js.clear', 'Clear')
			}).then(function (ok) {
				if (!ok) return;
				$.post(serverContext + 'platform/clearPolicyFlags', { organizationId: orgId, capability: cap })
					.done(function (res) {
						if (!apiOk(res)) { global.uiAlert(apiMessage(res, '')); return; }
						openTenant(orgId);   // re-read, never patch the DOM: the card must vanish because the
						                     // server says it is empty, not because we assume the post worked.
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
				shape: $('#provShape').val(),
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
