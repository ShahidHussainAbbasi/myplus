/**
 * MKT-0a — the operator's marketplace seller queue (platformDashboard.html #platMktSellers).
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
		$('#platTenants, #platDetail, #platProvision').hide();
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
})(window);
