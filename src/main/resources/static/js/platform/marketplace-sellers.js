/**
 * MKT-0a — the operator's marketplace seller queue (platformDashboard.html #platMktSellers), and
 * MKT-1b — the product match review queue (#platMktMatches).
 * Server: marketplace-service /mkt/operator/sellers via the monolith's /platform/mkt/** proxy (ROLE_ADMIN).
 *
 * The server decides everything: who may decide (ROLE_ADMIN, never a tenant's ADMIN_PRIVILEGE), which moves are
 * legal (MarketplaceStateMachines.SELLER_ACCOUNT) and that a rejection or suspension carries a reason. The buttons
 * offered per row follow the same lifecycle so the screen never offers a move the server would refuse.
 */
(function (global) {
	'use strict';

	function tr(key, fallback) {
		return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
			? global.t(key) : fallback;
	}
	function ctx() { return typeof global.serverContext === 'string' ? global.serverContext : '/'; }
	function ok(res) { return typeof global.apiOk === 'function' ? global.apiOk(res) : !!(res && res.success === true); }
	function data(res) { return typeof global.apiData === 'function' ? global.apiData(res) : (res && res.data); }
	function message(res, fb) { return typeof global.apiMessage === 'function' ? global.apiMessage(res, fb) : ((res && res.message) || fb); }
	function failMessage(xhr, fb) { return typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, fb) : fb; }

	/** The moves each status offers — the same edges as MarketplaceStateMachines.SELLER_ACCOUNT. */
	var MOVES = {
		PENDING_APPROVAL: [['APPROVE', 'ui.js.mktApprove', 'Approve', false], ['REJECT', 'ui.js.mktReject', 'Reject', true]],
		APPROVED: [['SUSPEND', 'ui.js.mktSuspend', 'Suspend', true]],
		SUSPENDED: [['REINSTATE', 'ui.js.mktReinstate', 'Reinstate', false]],
		REJECTED: []
	};
	var status = 'PENDING_APPROVAL';

	function fmt(iso) {
		if (!iso) return '';
		var d = new Date(iso);
		return isNaN(d) ? String(iso) : d.toLocaleString();
	}

	function load() {
		var $list = $('#platMktSellerList').empty().append($('<div class="plat__loading"></div>').text(tr('ui.js.loading', 'Loading…')));
		$.ajax({ url: ctx() + 'platform/mkt/sellers?status=' + encodeURIComponent(status) + '&size=100', dataType: 'json' })
			.done(function (res) {
				if (!ok(res)) { $list.empty().append($('<div class="plat__empty"></div>').text(message(res, ''))); return; }
				var page = data(res) || {}, rows = page.content || [];
				$list.empty();
				if (!rows.length) {
					$list.append($('<div class="plat__empty"></div>').text(tr('ui.js.mktNoSellers', 'No sellers in this list.')));
					return;
				}
				rows.forEach(function (a) { $list.append(row(a)); });
			})
			.fail(function (xhr) {
				$list.empty().append($('<div class="plat__empty"></div>').text(failMessage(xhr, tr('ui.js.loadFailed', 'Could not load.'))));
			});
	}

	function row(a) {
		var $r = $('<div class="plat-row"></div>').attr('data-org', a.organizationId);
		$r.append($('<div class="plat-row__main"></div>')
			.append($('<strong></strong>').text(a.displayName || ''))
			.append($('<span class="plat__hint"></span>').text(' · #' + a.organizationId + ' · ' + fmt(a.appliedAt)))
			.append(a.statusReason ? $('<div class="plat__hint"></div>').text(a.statusReason) : null));
		var $act = $('<div class="plat-row__actions"></div>');
		var $reason = $('<input type="text" class="form-control input-sm" maxlength="500">')
			.attr('placeholder', tr('ui.js.mktReasonPh', 'Reason the seller will see'))
			.attr('aria-label', tr('ui.js.mktReasonPh', 'Reason the seller will see'));
		var needsReason = false;
		(MOVES[a.status] || []).forEach(function (m) {
			if (m[3]) needsReason = true;
			$('<button type="button" class="btn btn-xs"></button>')
				.addClass(m[0] === 'APPROVE' || m[0] === 'REINSTATE' ? 'btn-primary' : 'btn-default')
				.attr('data-decision', m[0]).text(tr(m[1], m[2]))
				.on('click', function () { decide(a, m[0], $reason, $(this), $msg); })
				.appendTo($act);
		});
		if (needsReason) $act.prepend($reason);
		var $msg = $('<span class="plat__hint" role="status"></span>');
		$act.append($msg);
		return $r.append($act);
	}

	function decide(a, decision, $reason, $btn, $msg) {
		var label = $btn.text();
		$btn.prop('disabled', true).text(tr('ui.js.mktSaving', 'Saving…'));
		$.ajax({ url: ctx() + 'platform/mkt/decideSeller', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ organizationId: a.organizationId, decision: decision, reason: $reason.val(), version: a.version }) })
			.done(function (res) {
				if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
				load();
			})
			.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $btn.prop('disabled', false).text(label); });
	}

	function open() {
		$('#platTenants, #platDetail, #platProvision, #platMktMatches').hide();
		$('#platMktSellers').show();
		load();
	}

	$(document).on('click', '#platMktSellersBtn', open);
	$(document).on('click', '#platMktSellersBack', function () {
		$('#platMktSellers').hide();
		$('#platTenants').show();
	});
	$(document).on('click', '#platMktStatus button', function () {
		$('#platMktStatus button').removeClass('is-on');
		$(this).addClass('is-on');
		status = $(this).attr('data-status');
		load();
	});

	global.platMktSellersOpen = open;

	// ── MKT-1b: product matching ──────────────────────────────────────────────────────────────────────

	var matchStatus = 'PENDING_REVIEW';
	/** The decisions each review status offers — the same edges as MarketplaceStateMachines.MATCH. */
	var MATCH_MOVES = {
		PENDING_REVIEW: ['MATCHED', 'NEEDS_CORRECTION', 'REJECTED'],
		MATCHED: ['NEEDS_CORRECTION', 'REJECTED'],
		NEEDS_CORRECTION: []
	};
	var MATCH_LABEL = {
		MATCHED: ['ui.js.mktMatch', 'Match'],
		NEEDS_CORRECTION: ['ui.js.mktNeedsCorrection', 'Needs correction'],
		REJECTED: ['ui.js.mktRejectProposal', 'Reject']
	};

	function loadMatches() {
		var $tb = $('#mktMatchQueue tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/matchQueue?status=' + encodeURIComponent(matchStatus) + '&size=100', dataType: 'json' })
			.done(function (res) {
				if (!ok(res)) { $tb.append($('<tr><td colspan="4"></td></tr>').find('td').text(message(res, '')).end()); return; }
				var rows = (data(res) || {}).content || [];
				if (!rows.length) {
					$tb.append($('<tr><td colspan="4" class="plat__empty"></td></tr>').find('td')
						.text(tr('ui.js.mktNoMatches', 'Nothing to review.')).end());
					return;
				}
				rows.forEach(function (p) { $tb.append(matchRow(p)); });
			})
			.fail(function (xhr) {
				$tb.append($('<tr><td colspan="4"></td></tr>').find('td').text(failMessage(xhr, tr('ui.js.loadFailed', 'Could not load.'))).end());
			});
	}

	function matchRow(p) {
		var $tr = $('<tr></tr>').attr('data-id', p.id);
		$tr.append($('<td></td>')
			.append($('<div></div>').text([p.brand, p.model, p.variant, p.colour, p.size, p.packSize].filter(Boolean).join(' ')))
			.append($('<div class="plat__hint"></div>').text('#' + p.organizationId + ' · ' + (p.sourceProductName || '')
				+ (p.regulated && p.regulated !== 'NONE' ? ' · ' + p.regulated : ''))));
		$tr.append($('<td style="font-family:monospace;font-size:12px"></td>').text(p.proposedIdentityKey || ''));
		$tr.append($('<td></td>').text(p.suggestedProductId
			? tr('ui.js.mktSuggested', 'Same identity as product') + ' #' + p.suggestedProductId
			: tr('ui.js.mktNewProduct', 'New marketplace product')));
		var $act = $('<td></td>'), $msg = $('<div class="plat__hint" role="status"></div>');
		var moves = MATCH_MOVES[p.matchStatus] || [];
		var $note = $('<input type="text" class="form-control input-sm" maxlength="500">')
			.attr('placeholder', tr('ui.js.mktNotePh', 'Note the seller will see'))
			.attr('aria-label', tr('ui.js.mktNotePh', 'Note the seller will see'));
		if (moves.length > 1 || (moves.length && moves[0] !== 'MATCHED')) $act.append($note);
		moves.forEach(function (d) {
			$('<button type="button" class="btn btn-xs"></button>')
				.addClass(d === 'MATCHED' ? 'btn-primary' : 'btn-default')
				.attr('data-decision', d).text(tr(MATCH_LABEL[d][0], MATCH_LABEL[d][1]))
				.on('click', function () { decideMatch(p, d, $note, $(this), $msg); })
				.appendTo($act);
		});
		return $tr.append($act.append($msg));
	}

	function decideMatch(p, decision, $note, $btn, $msg) {
		var label = $btn.text();
		$btn.prop('disabled', true).text(tr('ui.js.mktSaving', 'Saving…'));
		$.ajax({ url: ctx() + 'platform/mkt/decideMatch', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ id: p.id, decision: decision, note: $note.val(), version: p.version }) })
			.done(function (res) {
				if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
				loadMatches();
			})
			.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $btn.prop('disabled', false).text(label); });
	}

	function openMatches() {
		$('#platTenants, #platDetail, #platProvision, #platMktSellers').hide();
		$('#platMktMatches').show();
		loadMatches();
	}

	$(document).on('click', '#platMktMatchesBtn', openMatches);
	$(document).on('click', '#platMktMatchesBack', function () {
		$('#platMktMatches').hide();
		$('#platTenants').show();
	});
	$(document).on('click', '#platMktMatchStatus button', function () {
		$('#platMktMatchStatus button').removeClass('is-on');
		$(this).addClass('is-on');
		matchStatus = $(this).attr('data-status');
		loadMatches();
	});

	global.platMktMatchesOpen = openMatches;
})(window);
