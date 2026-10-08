/**
 * MKT-0a — the operator's marketplace seller queue (platformDashboard.html #platMktSellers), and
 * MKT-1b — the product match review queue (#platMktMatches), and
 * MKT-1c — offer approvals (#platMktOffers) and the append-only policies (#platMktPolicies).
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
		$('.plat__panel').hide();   // one rule for every panel — never a hand-kept list to forget a new one in
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
		$('.plat__panel').hide();
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

	// ── MKT-1c: offer approvals ───────────────────────────────────────────────────────────────────────

	var offerStatus = 'PENDING_REVIEW';
	/** The same edges as MarketplaceStateMachines.OFFER (operator moves only). */
	var OFFER_MOVES = { PENDING_REVIEW: ['APPROVE', 'REJECT'], APPROVED: ['SUSPEND'], SUSPENDED: ['REINSTATE'] };
	var OFFER_LABEL = {
		APPROVE: ['ui.js.mktApprove', 'Approve'], REJECT: ['ui.js.mktReject', 'Reject'],
		SUSPEND: ['ui.js.mktSuspend', 'Suspend'], REINSTATE: ['ui.js.mktReinstate', 'Reinstate']
	};

	function money(v) {
		return Number(v || 0).toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
	}

	function loadOffers() {
		var $tb = $('#mktOfferQueue tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/offerQueue?status=' + encodeURIComponent(offerStatus) + '&size=100', dataType: 'json' })
			.done(function (res) {
				if (!ok(res)) { $tb.append($('<tr><td colspan="4"></td></tr>').find('td').text(message(res, '')).end()); return; }
				var rows = (data(res) || {}).content || [];
				if (!rows.length) {
					$tb.append($('<tr><td colspan="4" class="plat__empty"></td></tr>').find('td').text(tr('ui.js.mktNoMatches', 'Nothing to review.')).end());
					return;
				}
				rows.forEach(function (o) {
					var $tr = $('<tr></tr>').attr('data-offer-id', o.id);
					$tr.append($('<td></td>').append($('<div></div>').text(o.productName || ('#' + o.mktProductId)))
						.append($('<div class="plat__hint"></div>').text('#' + o.sellerOrganizationId)));
					$tr.append($('<td style="font-variant-numeric:tabular-nums"></td>').text(money(o.marketplacePrice)));
					$tr.append($('<td></td>').text((o.deliveryAreas || '').split(',').join(', ')));
					var $act = $('<td></td>'), $msg = $('<div class="plat__hint" role="status"></div>');
					var moves = OFFER_MOVES[o.approvalStatus] || [];
					var $note = $('<input type="text" class="form-control input-sm" maxlength="500">')
						.attr('placeholder', tr('ui.js.mktReasonPh', 'Reason the seller will see'))
						.attr('aria-label', tr('ui.js.mktReasonPh', 'Reason the seller will see'));
					if (moves.indexOf('REJECT') >= 0 || moves.indexOf('SUSPEND') >= 0) $act.append($note);
					moves.forEach(function (d) {
						$('<button type="button" class="btn btn-xs"></button>')
							.addClass(d === 'APPROVE' || d === 'REINSTATE' ? 'btn-primary' : 'btn-default')
							.attr('data-decision', d).text(tr(OFFER_LABEL[d][0], OFFER_LABEL[d][1]))
							.on('click', function () {
								var $b = $(this), label = $b.text();
								$b.prop('disabled', true);
								$.ajax({ url: ctx() + 'platform/mkt/decideOffer', type: 'POST', contentType: 'application/json',
									dataType: 'json', data: JSON.stringify({ id: o.id, decision: d, note: $note.val(), version: o.version }) })
									.done(function (r) {
										if (!ok(r)) { $msg.css('color', '#b3261e').text(message(r, tr('ui.js.saveFailed', 'Save failed'))); return; }
										loadOffers();
									})
									.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
									.always(function () { $b.prop('disabled', false).text(label); });
							}).appendTo($act);
					});
					$tb.append($tr.append($act.append($msg)));
				});
			});
	}

	// ── MKT-1c: policies ──────────────────────────────────────────────────────────────────────────────

	function terms(p) {
		if (p.policyType === 'WARRANTY') return p.warrantyMonths + ' · ' + (p.warrantyProvider || '') + (p.warrantyCovers ? ' · ' + p.warrantyCovers : '');
		if (p.policyType === 'RETURN') return p.returnDays + ' ' + tr('ui.js.mktDays', 'days');
		return p.commissionBasis === 'FIXED' ? 'Rs. ' + money(p.commissionFixed)
			: (Number(p.commissionRate || 0) * 100).toFixed(2).replace(/\.?0+$/, '') + '% · ' + p.commissionBasis;
	}

	function loadPolicies() {
		var $tb = $('#mktPolicyTable tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/policies', dataType: 'json' }).done(function (res) {
			var list = typeof global.apiList === 'function' ? global.apiList(res) : ((res && res.data) || []);
			(list || []).forEach(function (p) {
				var $tr = $('<tr></tr>').attr('data-policy-id', p.id).toggleClass('text-muted', !p.active);
				$tr.append($('<td></td>').text(p.policyType + (p.isDefault ? ' · ' + tr('ui.js.mktDefault', 'default') : '')));
				$tr.append($('<td></td>').text(p.name));
				$tr.append($('<td></td>').text(terms(p)));
				var $act = $('<td></td>');
				if (p.active) {
					$('<button type="button" class="btn btn-xs btn-default"></button>').text(tr('ui.js.mktDeactivate', 'Deactivate'))
						.on('click', function () {
							// the server's sentence either way (standard 8a): a refused deactivation was silent before
							$.ajax({ url: ctx() + 'platform/mkt/deactivatePolicy', type: 'POST', contentType: 'application/json',
								dataType: 'json', data: JSON.stringify({ id: p.id }) })
								.done(function (res) {
									$('#mktPolMsg').css('color', ok(res) ? '' : '#b3261e')
										.text(message(res, ok(res) ? '' : tr('ui.js.saveFailed', 'Save failed')));
								})
								.fail(function (xhr) { $('#mktPolMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
								.always(loadPolicies);
						}).appendTo($act);
				}
				$tb.append($tr.append($act));
			});
		});
	}

	function createPolicy() {
		var type = $('#mktPolType').val(), rate = $('#mktPolRate').val();
		var basis = $('#mktPolBasis').val();
		var body = { policyType: type, name: $('#mktPolName').val() };
		if (type === 'WARRANTY') {
			body.warrantyProvider = $('#mktPolProvider').val();
			body.warrantyMonths = $('#mktPolMonths').val() === '' ? null : Number($('#mktPolMonths').val());
			body.warrantyCovers = $('#mktPolCovers').val();
			body.warrantyExcludes = $('#mktPolExcludes').val();
		} else if (type === 'RETURN') {
			body.returnDays = $('#mktPolDays').val() === '' ? null : Number($('#mktPolDays').val());
		} else {
			body.commissionBasis = basis;
			body.isDefault = $('#mktPolDefault').is(':checked');
			// the screen takes a PERCENT; the server stores a fraction (10 → 0.10)
			if (basis === 'FIXED') body.commissionFixed = rate === '' ? null : Number(rate);
			else body.commissionRate = rate === '' ? null : Number(rate) / 100;
		}
		var $b = $('#mktPolCreate').prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/createPolicy', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify(body) }).done(function (res) {
			$('#mktPolMsg').css('color', ok(res) ? '' : '#b3261e').text(message(res, ok(res) ? '' : tr('ui.js.saveFailed', 'Save failed')));
			if (ok(res)) { $('#mktPolicyForm input[type=text], #mktPolicyForm input[type=number]').val(''); loadPolicies(); }
		}).fail(function (xhr) {
			$('#mktPolMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed')));
		}).always(function () { $b.prop('disabled', false); });
	}

	// ── MKT-1d: the order customers see first ────────────────────────────────────────────────────────
	function loadDefaultSort() {
		$('#mktDefaultSortMsg').text('').css('color', '');
		$.ajax({ url: ctx() + 'platform/mkt/defaultSort', dataType: 'json' }).done(function (res) {
			if (!ok(res)) { $('#mktDefaultSortMsg').css('color', '#b3261e').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))); return; }
			$('#mktDefaultSort').val((data(res) || {}).sort || 'RECOMMENDED');
		});
	}

	function saveDefaultSort() {
		var $b = $('#mktDefaultSortSave').prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/defaultSort', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ sort: $('#mktDefaultSort').val() }) })
			.done(function (res) {
				$('#mktDefaultSortMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e')
					.text(message(res, ok(res) ? tr('ui.js.mktOfferSaved', 'Saved.') : tr('ui.js.saveFailed', 'Save failed')));
			})
			.fail(function (xhr) { $('#mktDefaultSortMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	// ── MKT-1e: marketplace orders + the acceptance window ───────────────────────────────────────────
	var orderStatus = 'SUBMITTED';
	var ORD_LABEL = { SUBMITTED: ['ui.js.mktOrdWaiting', 'Waiting for a seller'], CONFIRMED: ['ui.js.mktOrdConfirmed', 'Confirmed'],
		CANCELLED: ['ui.js.mktOrdCancelled', 'Cancelled'] };

	function loadOrders() {
		var $tb = $('#mktOrderList tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/orders?size=100' + (orderStatus ? '&status=' + encodeURIComponent(orderStatus) : ''),
			dataType: 'json' }).done(function (res) {
			if (!ok(res)) { $tb.append($('<tr><td colspan="5"></td></tr>').find('td').text(message(res, '')).end()); return; }
			var rows = (data(res) || {}).content || [];
			if (!rows.length) {
				$tb.append($('<tr><td colspan="5" class="plat__empty"></td></tr>').find('td').text(tr('ui.js.mktNoMatches', 'Nothing to review.')).end());
				return;
			}
			rows.forEach(function (o) {
				var lbl = ORD_LABEL[o.status] || [null, o.status];
				$tb.append($('<tr></tr>').attr('data-order-no', o.orderNo)
					.append($('<td></td>').append($('<b></b>').text(o.orderNo)).append($('<div class="plat__hint"></div>').text(money(o.total))))
					.append($('<td></td>').text(o.sellerName || ''))
					.append($('<td></td>').text((o.lines || []).map(function (l) { return l.productName + ' × ' + l.quantity; }).join(', ')))
					.append($('<td></td>').text(o.city || ''))
					.append($('<td></td>').append($('<span></span>').text(lbl[0] ? tr(lbl[0], lbl[1]) : lbl[1]))
						.append(o.cancelReason ? $('<div class="plat__hint"></div>').text(o.cancelReason) : null)));
			});
		});
	}

	function loadAcceptWindow() {
		// MKT-2a: the switch stays disabled until its saved state is known, so a tick made before the answer arrives is
		// neither overwritten by it nor saved as the opposite of what the operator sees
		$('#mktMultiSeller, #mktMultiSellerSave, #mktReroute, #mktRerouteSave').prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/acceptWindow', dataType: 'json' }).done(function (res) {
			if (ok(res)) {
				$('#mktAcceptWindow').val((data(res) || {}).minutes);
				$('#mktMultiSeller').prop('checked', (data(res) || {}).multiSeller === true).prop('disabled', false);
				$('#mktMultiSellerSave').prop('disabled', false);
				$('#mktReroute').prop('checked', (data(res) || {}).reroute === true).prop('disabled', false);   // MKT-2b
				$('#mktRerouteSave').prop('disabled', false);
			}
		});
	}

	/** MKT-2c — how sellers are asked for stock: the limits in force, who is not being asked, and the test switch. */
	function loadRouting() {
		$.ajax({ url: ctx() + 'platform/mkt/routing', dataType: 'json' }).done(function (res) {
			if (ok(res)) renderRouting(data(res) || {});
			else $('#mktRoutingMsg').css('color', '#b3261e').text(message(res, tr('ui.js.loadFailed', 'Could not load.')));
		});
	}

	function renderRouting(v) {
		$('#mktRoutingLimits').text(tr('ui.js.mktRoutingLimits',
			'Each seller has {0} ms to answer and a checkout {1} ms in all. A seller that does not answer {2} times in a row is not asked for {3} seconds.')
			.replace('{0}', v.holdTimeoutMs).replace('{1}', v.deadlineMs).replace('{2}', v.breakerFailures).replace('{3}', v.breakerOpenSeconds));
		var $open = $('#mktRoutingOpen').empty();
		var list = v.notAnswering || [];
		if (!list.length) $open.append($('<div class="plat__hint mkt-routing-none"></div>').text(tr('ui.js.mktRoutingAllAnswering', 'Every seller is being asked.')));
		list.forEach(function (c) {
			var until = String(c.until || '').replace('T', ' ').substring(11, 19);
			$open.append($('<div class="mkt-routing-row" style="margin:6px 0 12px"></div>').attr('data-seller', c.sellerOrganizationId)
				.append($('<span></span>').text(tr('ui.js.mktRoutingNotAsked', '{0} is not answering: not asked until {1}.')
					.replace('{0}', c.sellerName).replace('{1}', until)))
				.append(' ')
				.append($('<button type="button" class="btn btn-default btn-xs mkt-routing-close"></button>')
					.attr('data-seller', c.sellerOrganizationId).text(tr('ui.js.mktRoutingAskAgain', 'Ask it again now'))));
		});
		$('#mktRoutingTest').toggle(v.testSwitch === true);
		if (v.testSwitch === true) {
			var $sel = $('#mktRoutingSeller').empty();
			(v.sellers || []).forEach(function (s) {
				$sel.append($('<option></option>').val(s.sellerOrganizationId).text(s.name || ('#' + s.sellerOrganizationId)));
			});
			if (v.slowSellerOrganizationId) $sel.val(String(v.slowSellerOrganizationId));
			$('#mktRoutingDelay').val(v.slowDelayMs || 0);
		}
	}

	function closeCircuit() {
		var $b = $(this).prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/routingClose', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ sellerOrganizationId: Number($b.attr('data-seller')) }) })
			.done(function (res) {
				$('#mktRoutingMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed')));
				if (ok(res)) renderRouting(data(res) || {});
			})
			.fail(function (xhr) { $('#mktRoutingMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	function saveRoutingTest() {
		var $b = $('#mktRoutingTestSave').prop('disabled', true);
		var delay = Number($('#mktRoutingDelay').val() || 0);
		$.ajax({ url: ctx() + 'platform/mkt/routingTest', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ sellerOrganizationId: delay > 0 ? Number($('#mktRoutingSeller').val()) : null, delayMs: delay }) })
			.done(function (res) {
				$('#mktRoutingMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed')));
				if (ok(res)) renderRouting(data(res) || {});
			})
			.fail(function (xhr) { $('#mktRoutingMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	/** MKT-2a — the multi-seller checkout switch; it rides on the acceptance-window endpoint, sending only itself. */
	function saveMultiSeller() {
		var $b = $('#mktMultiSellerSave').prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/acceptWindow', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ multiSeller: $('#mktMultiSeller').is(':checked') }) })
			.done(function (res) {
				$('#mktMultiSellerMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e')
					.text(message(res, ok(res) ? tr('ui.js.mktOfferSaved', 'Saved.') : tr('ui.js.saveFailed', 'Save failed')));
				if (ok(res)) $('#mktMultiSeller').prop('checked', (data(res) || {}).multiSeller === true);
			})
			.fail(function (xhr) { $('#mktMultiSellerMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	/** MKT-2b — the shortage reroute switch; it rides on the same endpoint, sending only itself. */
	function saveReroute() {
		var $b = $('#mktRerouteSave').prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/acceptWindow', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ reroute: $('#mktReroute').is(':checked') }) })
			.done(function (res) {
				$('#mktRerouteMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e')
					.text(message(res, ok(res) ? tr('ui.js.mktOfferSaved', 'Saved.') : tr('ui.js.saveFailed', 'Save failed')));
				if (ok(res)) $('#mktReroute').prop('checked', (data(res) || {}).reroute === true);
			})
			.fail(function (xhr) { $('#mktRerouteMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	// ── MKT-2b: unfulfilled parts — the cause recorded against a party, and the disputes to decide ──────────
	var shortageStatus = 'DISPUTED';
	var SH_CAUSE = {
		MERCHANT_STALE_STOCK: ['ui.js.mktCauseMerchant', 'Out of stock in my shop'],
		SUPPLIER_STALE_STOCK: ['ui.js.mktCauseSupplier', 'My supplier could not deliver'],
		PLATFORM_SYNC_DEFECT: ['ui.js.mktCausePlatform', 'MaxTheService showed the wrong stock'],
		NO_RESPONSE: ['ui.js.mktCauseNoResponse', 'Not accepted in time']
	};
	var SH_RESULT = {
		PENDING: ['ui.js.mktShRPending', 'Looking for another seller'], REASSIGNED: ['ui.js.mktShRMoved', 'Moved to another seller'],
		SUBSTITUTION_REQUESTED: ['ui.js.mktShRAsked', 'Customer asked to choose'], LINE_CANCELLED: ['ui.js.mktShRLine', 'Items cancelled'],
		ORDER_CANCELLED: ['ui.js.mktShROrder', 'Order cancelled']
	};
	var SH_STATE = {
		RECORDED: ['ui.js.mktShRecorded', 'Recorded'], DISPUTED: ['ui.js.mktShDisputedOp', 'Disputed by the seller'],
		UPHELD: ['ui.js.mktShUpheld', 'Upheld by MaxTheService'], OVERTURNED: ['ui.js.mktShOverturned', 'Overturned by MaxTheService']
	};
	function lbl(map, k) { var v = map[k]; return v ? tr(v[0], v[1]) : (k || ''); }

	function loadShortages() {
		var $tb = $('#mktShortageList tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/shortages?size=100' + (shortageStatus ? '&status=' + encodeURIComponent(shortageStatus) : ''), dataType: 'json' })
			.done(function (res) {
				if (!ok(res)) { $tb.append($('<tr><td colspan="5"></td></tr>').find('td').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))).end()); return; }
				var rows = (data(res) || {}).content || [];
				if (!rows.length) $tb.append($('<tr><td colspan="5" class="text-muted"></td></tr>').find('td').text(tr('ui.js.mktShNoneOp', 'Nothing here.')).end());
				rows.forEach(function (r) { $tb.append(shortageRow(r)); });
			})
			.fail(function (xhr) { $tb.append($('<tr><td colspan="5"></td></tr>').find('td').text(failMessage(xhr, tr('ui.js.loadFailed', 'Could not load.'))).end()); });
	}

	function shortageRow(r) {
		var $tr = $('<tr class="mkt-shortage-row"></tr>').attr('data-shortage-id', r.id).attr('data-order-no', r.orderNo);
		$tr.append($('<td></td>').append($('<b></b>').text(r.orderNo || '')));
		$tr.append($('<td></td>').text(r.sellerName || ''));
		var $cause = $('<td></td>').append($('<div></div>').text(lbl(SH_CAUSE, r.cause)));
		if (r.evidence) $cause.append($('<div class="text-muted" style="font-size:12px"></div>').text(r.evidence));
		if (r.disputeNote) $cause.append($('<div style="font-size:12px"></div>').text(tr('ui.js.mktShSellerSaid', 'Seller: {0}').replace('{0}', r.disputeNote)));
		$tr.append($cause);
		$tr.append($('<td></td>').text(lbl(SH_RESULT, r.result)
			+ (r.minutesToResolve !== null && r.minutesToResolve !== undefined ? ' · ' + tr('ui.js.mktShMinutes', '{0} min').replace('{0}', r.minutesToResolve) : '')));
		var $st = $('<td></td>').append($('<span class="mkt-shortage-status"></span>').attr('data-status', r.status).text(lbl(SH_STATE, r.status)));
		if (r.decisionNote) $st.append($('<div class="text-muted" style="font-size:12px"></div>').text(r.decisionNote));
		if (r.status === 'DISPUTED') {
			var $note = $('<input type="text" class="form-control input-sm mkt-shortage-note" maxlength="500" style="margin-top:4px">')
				.attr('placeholder', tr('ui.js.mktShWhy', 'Your reason (the seller sees it)'))
				.attr('aria-label', tr('ui.js.mktShWhy', 'Your reason (the seller sees it)'));
			var $msg = $('<div role="status" style="font-size:12px"></div>');
			var decide = function (outcome, $b) {
				$b.prop('disabled', true);
				$msg.text('').css('color', '');
				$.ajax({ url: ctx() + 'platform/mkt/shortageDecide', type: 'POST', contentType: 'application/json', dataType: 'json',
					data: JSON.stringify({ id: r.id, outcome: outcome, note: $note.val() }) })
					.done(function (res) {
						if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
						var $now = shortageRow(data(res));
						$now.find('td:last').append($('<div role="status" style="font-size:12px;color:#1f7a4d"></div>').text(message(res, '')));
						$tr.replaceWith($now);
					})
					.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
					.always(function () { $b.prop('disabled', false); });
			};
			var $up = $('<button type="button" class="btn btn-xs btn-default mkt-shortage-uphold" style="margin:4px 4px 0 0"></button>')
				.text(tr('ui.js.mktShUphold', 'Uphold the cause'));
			$up.on('click', function () { decide('UPHELD', $up); });
			var $over = $('<button type="button" class="btn btn-xs btn-default mkt-shortage-overturn" style="margin-top:4px"></button>')
				.text(tr('ui.js.mktShOverturn', 'Overturn it'));
			$over.on('click', function () { decide('OVERTURNED', $over); });
			$st.append($note).append($up).append($over).append($msg);
		}
		$tr.append($st);
		return $tr;
	}

	// ── MKT-2e: seller performance ────────────────────────────────────────────────────────────────────
	// Read from the orders placed in the window; a flag is a note for the operator, never a sanction (R12.4).
	var perfDays = 30;
	var PERF_FLAG = {
		LOW_ACCEPTANCE: ['ui.js.mktPerfFlagAcceptance', 'Accepts fewer than 80% of its orders'],
		LATE_DELIVERY: ['ui.js.mktPerfFlagLate', 'Delivers fewer than 90% on time'],
		COD_OVERDUE: ['ui.js.mktPerfFlagCod', 'Late paying for cash orders']
	};
	function perfRate(rate, part, whole) {
		if (rate === null || rate === undefined) return tr('ui.js.mktPerfNotEnough', 'Not enough orders yet');
		return tr('ui.js.mktPerfRate', '{0}% ({1} of {2})').replace('{0}', Math.round(rate * 100)).replace('{1}', part).replace('{2}', whole);
	}
	function perfSpeed(m) {
		if (m === null || m === undefined) return '—';
		return m < 1 ? tr('ui.js.mktPerfUnderMinute', 'Under a minute on average')
			: tr('ui.js.mktPerfMinutes', '{0} min on average').replace('{0}', m);
	}
	function loadPerformance() {
		var $tb = $('#mktPerfList tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/sellerPerformance?days=' + perfDays, dataType: 'json' })
			.done(function (res) {
				if (!ok(res)) { $tb.append($('<tr><td colspan="7"></td></tr>').find('td').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))).end()); return; }
				var rows = (data(res) || {}).sellers || [];
				if (!rows.length) $tb.append($('<tr><td colspan="7" class="text-muted"></td></tr>').find('td').text(tr('ui.js.mktPerfNone', 'No marketplace orders in this window.')).end());
				rows.forEach(function (r) { $tb.append(perfRow(r)); });
			})
			.fail(function (xhr) { $tb.append($('<tr><td colspan="7"></td></tr>').find('td').text(failMessage(xhr, tr('ui.js.loadFailed', 'Could not load.'))).end()); });
	}
	function perfRow(r) {
		var $tr = $('<tr class="mkt-perf-row"></tr>').attr('data-org', r.organizationId)
			.attr('data-flags', (r.flags || []).join(','));
		$tr.append($('<td></td>').append($('<b></b>').text(r.sellerName || ('#' + r.organizationId))));
		var $f = $('<td class="mkt-perf-flags"></td>');
		if (!(r.flags || []).length) $f.addClass('text-muted').text(tr('ui.js.mktPerfFlagNone', 'Nothing'));
		(r.flags || []).forEach(function (k) {
			$('<div class="mkt-perf-flag" style="color:#b3261e;font-weight:600"></div>').attr('data-flag', k).text(lbl(PERF_FLAG, k)).appendTo($f);
		});
		$tr.append($f);
		$tr.append($('<td class="mkt-perf-accept"></td>').text(perfRate(r.acceptanceRate, r.accepted, r.accepted + r.missed)));
		$tr.append($('<td class="mkt-perf-speed"></td>').text(perfSpeed(r.avgMinutesToAccept)));
		$tr.append($('<td class="mkt-perf-ontime"></td>').text(perfRate(r.onTimeRate, r.onTime, r.due)));
		var $miss = $('<td class="mkt-perf-missed"></td>').text(String(r.missed));
		if (r.disputed) $miss.append($('<div class="text-muted" style="font-size:12px"></div>').text(tr('ui.js.mktPerfDisputed', '{0} under dispute').replace('{0}', r.disputed)));
		if (r.excused) $miss.append($('<div class="text-muted" style="font-size:12px"></div>').text(tr('ui.js.mktPerfExcused', "{0} not the seller's fault").replace('{0}', r.excused)));
		$tr.append($miss);
		$tr.append($('<td class="mkt-perf-returns"></td>').text(String(r.sellerFaultReturns || 0)));
		return $tr;
	}

	function saveAcceptWindow() {
		var $b = $('#mktAcceptWindowSave').prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/acceptWindow', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ minutes: Number($('#mktAcceptWindow').val()) || null }) })
			.done(function (res) {
				$('#mktAcceptWindowMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e')
					.text(message(res, ok(res) ? tr('ui.js.mktOfferSaved', 'Saved.') : tr('ui.js.saveFailed', 'Save failed')));
			})
			.fail(function (xhr) { $('#mktAcceptWindowMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	// ── MKT-1f: support cases and returns ──────────────────────────────────────────────────────────────

	var caseStatus = '';
	var CASE_ST = { OPEN: ['ui.js.mktCaseOpen', 'Open'], WAITING_SELLER: ['ui.js.mktCaseWaitSeller', 'Waiting for seller'],
		WAITING_CUSTOMER: ['ui.js.mktCaseWaitCustomer', 'Waiting for customer'], RESOLVED: ['ui.js.mktCaseResolved', 'Resolved'] };

	function loadCases() {
		$.ajax({ url: ctx() + 'platform/mkt/cases?size=100' + (caseStatus ? '&status=' + encodeURIComponent(caseStatus) : ''), dataType: 'json' })
			.done(function (res) {
				var $tb = $('#mktCaseList tbody').empty();
				if (!ok(res)) { $tb.append($('<tr><td colspan="5"></td></tr>').find('td').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))).end()); return; }
				var rows = (data(res) || {}).content || [];
				if (!rows.length) $tb.append($('<tr><td colspan="5" class="text-muted"></td></tr>').find('td').text(tr('ui.js.mktNoCases', 'No cases here.')).end());
				rows.forEach(function (c) {
					var st = CASE_ST[c.status] || [null, c.status];
					var $tr = $('<tr class="mkt-case-row" style="cursor:pointer" tabindex="0"></tr>').attr('data-case-no', c.caseNo);
					var $c = $('<td></td>').text(c.caseNo).appendTo($tr);
					if (c.urgent) $c.append(' ').append($('<span class="label label-danger"></span>').text(tr('ui.js.mktUrgent', 'URGENT')));
					$('<td></td>').text(c.orderNo || '').appendTo($tr);
					$('<td></td>').text(c.sellerName || '').appendTo($tr);
					$('<td></td>').text(c.topic).appendTo($tr);
					$('<td></td>').text(st[0] ? tr(st[0], st[1]) : st[1]).appendTo($tr);
					$tb.append($tr);
				});
			});
		$.ajax({ url: ctx() + 'platform/mkt/settings/changeOfMindFee', dataType: 'json' }).done(function (res) {
			if (ok(res)) $('#mktFee').val(Number((data(res) || {}).amount));
		});
	}

	function openCase(caseNo) {
		$.ajax({ url: ctx() + 'platform/mkt/caseView?caseNo=' + encodeURIComponent(caseNo), dataType: 'json' }).done(function (res) {
			var $d = $('#mktCaseDetail').empty();
			if (!ok(res)) { $d.append($('<p style="color:#b3261e"></p>').text(message(res, tr('ui.js.loadFailed', 'Could not load.')))); return; }
			var c = data(res);
			var $p = $('<div class="panel panel-default" style="padding:12px"></div>').attr('data-case-no', c.caseNo).appendTo($d);
			// MKT-2a: the seller whose part the case is about (one case per seller part)
			$('<h4 style="margin-top:0"></h4>').text(c.caseNo + ' · ' + c.orderNo + (c.sellerName ? ' · ' + c.sellerName : '') + (c.urgent ? ' · ' + tr('ui.js.mktUrgent', 'URGENT') : '')).appendTo($p);
			var $ul = $('<ul style="list-style:none;padding:0;font-size:13px"></ul>').appendTo($p);
			(c.messages || []).forEach(function (m) {
				$('<li style="margin-bottom:4px"></li>').append($('<b></b>').text(m.from + (m.internal ? ' (' + tr('ui.js.mktInternal', 'internal') + ')' : '') + ': '))
					.append(document.createTextNode(m.body)).appendTo($ul);
			});
			var $msg = $('<div role="status" style="font-size:12px;min-height:1.2em"></div>');
			(c.returns || []).forEach(function (r) {
				var $r = $('<div class="mkt-op-return" style="margin:6px 0"></div>').attr('data-return-no', r.returnNo).appendTo($p);
				$('<div></div>').text(r.returnNo + ' · ' + r.status + ' · ' + r.reason + ' · ' + tr('ui.js.mktBearer', 'Cost bearer') + ': '
					+ r.bearerRole + (r.bearerOrgName ? ' (' + r.bearerOrgName + ')' : '') + ' · ' + tr('ui.js.mktRefund', 'Refund') + ' ' + r.refundAmount
					+ (Number(r.deduction) > 0 ? ' (−' + r.deduction + ')' : '') + (r.creditNoteNo ? ' · ' + r.creditNoteNo : '')).appendTo($r);
				if (r.status === 'REQUESTED') {
					var $n = $('<input class="form-control input-sm" style="display:inline-block;width:auto;margin-right:6px" maxlength="500">')
						.attr('placeholder', tr('ui.js.mktDecisionNote', 'Note to the customer')).appendTo($r);
					['APPROVED', 'REJECTED'].forEach(function (d) {
						$('<button type="button" class="btn btn-xs" style="margin-right:4px"></button>').addClass(d === 'APPROVED' ? 'btn-success mkt-approve' : 'btn-danger mkt-reject')
							.text(d === 'APPROVED' ? tr('ui.js.mktApprove', 'Approve') : tr('ui.js.mktReject', 'Reject'))
							.on('click', function () { casePost('platform/mkt/returnDecision', { returnNo: r.returnNo, decision: d, note: $n.val() }, $(this), $msg, c.caseNo); })
							.appendTo($r);
					});
				}
			});
			var $ta = $('<textarea class="form-control input-sm mkt-op-reply" rows="2" maxlength="2000"></textarea>')
				.attr('aria-label', tr('ui.js.mktReplyLabel', 'Reply or note')).appendTo($p);
			var $int = $('<input type="checkbox" class="mkt-op-internal">');
			$('<label style="font-weight:400;margin:6px 8px 0 0"></label>').append($int).append(document.createTextNode(' ' + tr('ui.js.mktInternalNote', 'Internal note (the customer does not see it)'))).appendTo($p);
			$('<button type="button" class="btn btn-xs btn-primary mkt-op-send" style="margin:6px 4px 0 0"></button>').text(tr('ui.js.mktCaseSend', 'Send'))
				.on('click', function () { casePost('platform/mkt/caseReply', { caseNo: c.caseNo, body: $ta.val(), internal: $int.is(':checked') }, $(this), $msg, c.caseNo); }).appendTo($p);
			$('<button type="button" class="btn btn-xs btn-default mkt-op-task" style="margin:6px 4px 0 0"></button>').text(tr('ui.js.mktTaskSeller', 'Task the seller'))
				.on('click', function () { casePost('platform/mkt/caseTask', { caseNo: c.caseNo, note: $ta.val() }, $(this), $msg, c.caseNo); }).appendTo($p);
			$('<button type="button" class="btn btn-xs btn-default mkt-op-resolve" style="margin:6px 4px 0 0"></button>').text(tr('ui.js.mktResolve', 'Resolve'))
				.on('click', function () { casePost('platform/mkt/caseResolve', { caseNo: c.caseNo, note: $ta.val() }, $(this), $msg, c.caseNo); }).appendTo($p);
			$p.append($msg);
		});
	}

	function casePost(path, body, $b, $msg, caseNo) {
		$b.prop('disabled', true);
		$.ajax({ url: ctx() + path, type: 'POST', contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
			.done(function (res) {
				if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
				var said = message(res, '');
				loadCases();
				openCase(caseNo);
				setTimeout(function () { $('#mktCaseDetail [role=status]').css('color', '#1f7a4d').text(said); }, 300);
			})
			.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	}

	$(document).on('click', '#platMktCasesBtn', function () { openPanel('#platMktCases', loadCases); });
	$(document).on('click', '#platMktCaseStatus button', function () {
		$('#platMktCaseStatus button').removeClass('is-on');
		$(this).addClass('is-on');
		caseStatus = $(this).attr('data-status');
		$('#mktCaseDetail').empty();
		loadCases();
	});
	$(document).on('click keydown', '.mkt-case-row', function (e) {
		if (e.type === 'keydown' && e.key !== 'Enter') return;
		openCase($(this).attr('data-case-no'));
	});
	$(document).on('click', '#mktFeeSave', function () {
		var $b = $(this).prop('disabled', true);
		$.ajax({ url: ctx() + 'platform/mkt/settings/changeOfMindFee', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify({ amount: $('#mktFee').val() === '' ? null : Number($('#mktFee').val()) }) })
			.done(function (res) { $('#mktFeeMsg').css('color', ok(res) ? '#1f7a4d' : '#b3261e').text(message(res, '')); })
			.fail(function (xhr) { $('#mktFeeMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false); });
	});

	// ── MKT-1g: settlement and payouts ─────────────────────────────────────────────────────────────────

	function key(prefix) { return prefix + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10); }

	function say($el, res, fallback) {
		$el.css('color', ok(res) ? '#1f7a4d' : '#b3261e').text(message(res, fallback));
	}

	function opsPost(path, body, $b, $msg, after) {
		var label = $b.text();
		$b.prop('disabled', true);
		return $.ajax({ url: ctx() + path, type: 'POST', contentType: 'application/json', dataType: 'json', data: JSON.stringify(body || {}) })
			.done(function (res) { say($msg, res, ok(res) ? tr('ui.js.mktOfferSaved', 'Saved.') : tr('ui.js.saveFailed', 'Save failed')); if (ok(res) && after) after(res); })
			.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false).text(label); });
	}

	function loadSettlementSettings() {
		$.ajax({ url: ctx() + 'platform/mkt/settlementSettings', dataType: 'json' }).done(function (res) {
			if (!ok(res)) return;
			var v = data(res) || {};
			$('#mktTPlus').val(v.tPlusDays);
			$('#mktUseMyBooksWrap').toggle(!v.booksAreMine);   // the wrapper: a .btn cannot be hidden (theme.css !important)
			if (!v.booksOrganizationId) $('#mktSetMsg').css('color', '#8a5a00')
				.text(tr('ui.js.mktNoBooks', 'No books chosen yet: the first settlement books commission in your organisation.'));
		});
	}

	function loadAccounts() {
		var $tb = $('#mktAccountList tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/settlementAccounts', dataType: 'json' }).done(function (res) {
			if (!ok(res)) { $tb.append($('<tr><td colspan="4"></td></tr>').find('td').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))).end()); return; }
			var rows = data(res) || [];
			if (!rows.length) $tb.append($('<tr><td colspan="4" class="text-muted"></td></tr>').find('td').text(tr('ui.js.mktNoBalances', 'No seller has a settled line yet.')).end());
			rows.forEach(function (a) { $tb.append(accountRow(a)); });
		});
	}

	function accountRow(a) {
		var $tr = $('<tr class="mkt-account"></tr>').attr('data-org', a.organizationId);
		var $msg = $('<div role="status" style="font-size:12px"></div>');
		$('<td></td>').text(a.sellerName || ('#' + a.organizationId)).appendTo($tr);
		$('<td class="text-right mkt-balance"></td>').text(money(a.balance)).css('color', Number(a.balance) < 0 ? '#b3261e' : '').appendTo($tr);
		var p = a.openPayout;
		$('<td></td>').text(p ? p.payoutNo + ' · ' + money(p.requestedAmount) + ' · ' + p.status : '—').appendTo($tr);
		var $act = $('<td></td>').appendTo($tr);
		if (!p && Number(a.balance) > 0) {
			$('<button type="button" class="btn btn-xs btn-primary mkt-request-payout"></button>').text(tr('ui.js.mktRequestPayout', 'Request payout'))
				.on('click', function () { opsPost('platform/mkt/requestPayout', { organizationId: a.organizationId, idempotencyKey: key('po') }, $(this), $msg, loadAccounts); })
				.appendTo($act);
		}
		if (p && p.status === 'REQUESTED') {
			$('<button type="button" class="btn btn-xs btn-success mkt-approve-payout"></button>').text(tr('ui.js.mktApprovePayout', 'Approve'))
				.prop('title', p.requestedByMe ? tr('ui.js.mktNotYourOwn', 'Another operator must approve a payout you requested.') : '')
				.on('click', function () { opsPost('platform/mkt/approvePayout', { id: p.id }, $(this), $msg, loadAccounts); })
				.appendTo($act);
		}
		if (p && p.status === 'APPROVED') {
			var $ref = $('<input type="text" maxlength="80" class="form-control input-sm mkt-bank-ref" style="display:inline-block;width:140px;margin-right:6px">')
				.attr('placeholder', tr('ui.js.mktBankRef', 'Bank reference')).attr('aria-label', tr('ui.js.mktBankRef', 'Bank reference')).appendTo($act);
			$('<button type="button" class="btn btn-xs btn-success mkt-mark-paid"></button>').text(tr('ui.js.mktMarkPaid', 'Mark paid'))
				.on('click', function () { opsPost('platform/mkt/markPayoutPaid', { id: p.id, bankReference: $ref.val() }, $(this), $msg, loadAccounts); })
				.appendTo($act);
		}
		$('<button type="button" class="btn btn-xs btn-default mkt-ledger" style="margin-left:6px"></button>').text(tr('ui.js.mktLedger', 'Ledger'))
			.on('click', function () { openLedger(a.organizationId); }).appendTo($act);
		$act.append($msg);
		return $tr;
	}

	function openLedger(org) {
		var $box = $('#mktOpsLedger').empty();
		$.ajax({ url: ctx() + 'platform/mkt/settlementAccount?organizationId=' + encodeURIComponent(org), dataType: 'json' }).done(function (res) {
			if (!ok(res)) { $box.text(message(res, tr('ui.js.loadFailed', 'Could not load.'))); return; }
			var v = data(res) || {};
			$('<h4 style="font-weight:700"></h4>').text((v.sellerName || ('#' + org)) + ' · ' + money(v.balance)).appendTo($box);
			var $t = $('<table class="table table-condensed mkt-ops-ledger"><thead><tr><th></th><th></th><th></th><th class="text-right"></th><th class="text-right"></th></tr></thead><tbody></tbody></table>').appendTo($box);
			var heads = [tr('ui.js.mktColDate', 'Date'), tr('ui.js.mktColEntry', 'Entry'), tr('ui.js.mktColRef', 'Reference'),
				tr('ui.js.mktColCredit', 'Owed to seller'), tr('ui.js.mktColDebit', 'Owed by seller')];
			$t.find('th').each(function (i) { $(this).text(heads[i]); });
			(v.entries || []).forEach(function (e) {
				$t.find('tbody').append($('<tr class="mkt-entry"></tr>').attr('data-entry-type', e.entryType)
					.append($('<td></td>').text((e.effectiveAt || '').substring(0, 10)))
					.append($('<td></td>').text(e.entryType).append(e.memo ? $('<div class="plat__hint"></div>').text(e.memo) : null))
					.append($('<td></td>').text(e.ref || ''))
					.append($('<td class="text-right"></td>').text(Number(e.credit) ? money(e.credit) : ''))
					.append($('<td class="text-right"></td>').text(Number(e.debit) ? money(e.debit) : '')));
			});
			// a correction is a NEW line (R15.6): there is no edit control on any row above
			var $f = $('<div class="form-inline" style="margin-top:8px"></div>').appendTo($box);
			var $amt = $('<input type="number" step="0.01" class="form-control input-sm" id="mktAdjAmount" style="width:120px;margin-right:6px">')
				.attr('placeholder', tr('ui.js.mktAdjAmount', 'Rs, minus to take back')).attr('aria-label', tr('ui.js.mktAdjAmount', 'Rs, minus to take back')).appendTo($f);
			var $why = $('<input type="text" maxlength="300" class="form-control input-sm" id="mktAdjReason" style="width:260px;margin-right:6px">')
				.attr('placeholder', tr('ui.js.mktAdjReason', 'Reason, shown to the seller')).attr('aria-label', tr('ui.js.mktAdjReason', 'Reason, shown to the seller')).appendTo($f);
			var $m = $('<span role="status" style="margin-left:8px"></span>');
			var adjKey = key('adj');
			$('<button type="button" class="btn btn-sm btn-default" id="mktAdjSave"></button>').text(tr('ui.js.mktAdjSave', 'Record correction'))
				.on('click', function () {
					opsPost('platform/mkt/adjustLedger', { organizationId: org, amount: $amt.val() === '' ? null : Number($amt.val()),
						reason: $why.val(), idempotencyKey: adjKey }, $(this), $m, function () { loadAccounts(); openLedger(org); });
				}).appendTo($f);
			$f.append($m);
		});
	}

	// ── MKT-2d: cash orders: what sellers owe, and the money they pay ──────────────────────────────────────

	function loadCod() {
		var $tb = $('#mktCodList tbody').empty();
		$.ajax({ url: ctx() + 'platform/mkt/codReconciliation', dataType: 'json' }).done(function (res) {
			if (!ok(res)) { $tb.append($('<tr><td colspan="6"></td></tr>').find('td').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))).end()); return; }
			var rows = data(res) || [];
			if (!rows.length) $tb.append($('<tr><td colspan="6" class="text-muted"></td></tr>').find('td').text(tr('ui.js.mktCodNone', 'No seller has collected cash for a marketplace order yet.')).end());
			rows.forEach(function (r) { $tb.append(codRow(r)); });
		});
	}

	function codRow(r) {
		var st = r.standing || {}, owed = Number(st.owed || 0);
		var $tr = $('<tr class="mkt-cod-row"></tr>').attr('data-org', r.organizationId).toggleClass('danger', !!st.overdue);
		$('<td></td>').text(r.sellerName || ('#' + r.organizationId)).appendTo($tr);
		$('<td class="text-right mkt-cod-collected"></td>').text(money(r.cashCollected)).appendTo($tr);
		$('<td class="text-right mkt-cod-paid"></td>').text(money(r.remitted)).appendTo($tr);
		$('<td class="text-right mkt-cod-owed"></td>').text(owed ? money(owed) : '—').css('color', owed ? '#b3261e' : '').appendTo($tr);
		var $when = $('<td class="mkt-cod-payby"></td>').appendTo($tr);
		if (owed) {
			$('<span style="white-space:nowrap"></span>').text(st.payBy || '').appendTo($when);
			$('<div class="plat__hint" style="min-width:150px"></div>').text((st.overdue ? tr('ui.js.mktCodOverdue', 'Overdue. Owed since {0}.') : tr('ui.js.mktCodSince', 'Owed since {0}.'))
				.replace('{0}', st.owedSince || '')).appendTo($when);
			if (st.codStopped) $('<div class="plat__hint mkt-cod-stopped" style="color:#b3261e"></div>')
				.text(tr('ui.js.mktCodStopped', 'Cash on delivery is stopped for this seller.')).appendTo($when);
		} else $when.text('—');
		var $act = $('<td></td>').appendTo($tr);
		if (owed) {
			var payKey = key('rem');
			var $amt = $('<input type="number" step="0.01" min="0.01" class="form-control input-sm mkt-cod-amount" style="display:inline-block;width:110px;margin:0 6px 4px 0">')
				.val(owed.toFixed(2)).attr('aria-label', tr('ui.js.mktCodAmount', 'Amount paid, Rs')).attr('placeholder', tr('ui.js.mktCodAmount', 'Amount paid, Rs')).appendTo($act);
			var $ref = $('<input type="text" maxlength="80" class="form-control input-sm mkt-cod-ref" style="display:inline-block;width:150px;margin:0 6px 4px 0">')
				.attr('aria-label', tr('ui.js.mktCodRef', 'Bank or receipt reference')).attr('placeholder', tr('ui.js.mktCodRef', 'Bank or receipt reference')).appendTo($act);
			var $note = $('<input type="text" maxlength="200" class="form-control input-sm mkt-cod-note" style="display:inline-block;width:200px;margin:0 6px 4px 0">')
				.attr('aria-label', tr('ui.js.mktCodNote', 'Why less than owed (if it is)')).attr('placeholder', tr('ui.js.mktCodNote', 'Why less than owed (if it is)')).appendTo($act);
			var $msg = $('<div role="status" class="mkt-cod-msg" style="font-size:12px"></div>');
			$('<button type="button" class="btn btn-xs btn-primary mkt-cod-record"></button>').text(tr('ui.js.mktCodRecord', 'Record payment'))
				.on('click', function () {
					opsPost('platform/mkt/recordRemittance', { organizationId: r.organizationId, amount: $amt.val() === '' ? null : Number($amt.val()),
						reference: $ref.val(), note: $note.val(), idempotencyKey: payKey }, $(this), $msg, function () {
						loadAccounts();
						setTimeout(function () {
							loadCod();
							$('#mktCodMsg').css('color', '#1f7a4d').text(tr('ui.js.mktCodRecorded', 'Payment recorded for {0}.')
								.replace('{0}', r.sellerName || ('#' + r.organizationId)));
						}, 0);
					});
				}).appendTo($act);
			$act.append($msg);
		}
		return $tr;
	}

	function loadCodSettings() {
		$.ajax({ url: ctx() + 'platform/mkt/settlementSettings', dataType: 'json' }).done(function (res) {
			if (!ok(res)) return;
			var v = data(res) || {};
			$('#mktCodDays').val(v.codRemitDays);
			$('#mktCodStop').prop('checked', !!v.codStopWhenOverdue);
		});
	}

	$(document).on('click', '#mktCodSave', function () {
		opsPost('platform/mkt/settlementSettings', { codRemitDays: $('#mktCodDays').val() === '' ? null : Number($('#mktCodDays').val()),
			codStopWhenOverdue: $('#mktCodStop').prop('checked') }, $(this), $('#mktCodMsg'), function () { loadCodSettings(); loadCod(); });
	});

	$(document).on('click', '#platMktPayoutsBtn', function () {
		$('#mktOpsLedger').empty();
		$('#mktSetMsg').text('');
		$('#mktCodMsg').text('');
		openPanel('#platMktPayouts', function () { loadSettlementSettings(); loadAccounts(); loadCodSettings(); loadCod(); });
	});
	$(document).on('click', '#mktSetSave', function () {
		opsPost('platform/mkt/settlementSettings', { tPlusDays: $('#mktTPlus').val() === '' ? null : Number($('#mktTPlus').val()) },
			$(this), $('#mktSetMsg'), loadSettlementSettings);
	});
	$(document).on('click', '#mktUseMyBooks', function () {
		opsPost('platform/mkt/settlementSettings', { useMyBooks: true }, $(this), $('#mktSetMsg'), loadSettlementSettings);
	});
	$(document).on('click', '#mktRunSettlement', function () {
		opsPost('platform/mkt/runSettlement', {}, $(this), $('#mktSetMsg'), function (res) {
			// say what the run did, not "Saved.": the operator pressed it to learn how many lines became payable
			var r = data(res) || {};
			$('#mktSetMsg').text(tr('ui.js.mktRunDone', 'Settled {0} line(s). {1} still wait for their payable date; {2} are on hold for a return.')
				.replace('{0}', r.settled || 0).replace('{1}', r.waiting || 0).replace('{2}', r.onHold || 0));
			loadAccounts();
			loadSettlementSettings();
			loadCod();                                         // a settled cash line is a new debt (MKT-2d)
		});
	});

	function openPanel(id, loader) {
		$('.plat__panel').hide();
		$(id).show();
		loader();
	}

	$(document).on('click', '#platMktOffersBtn', function () { openPanel('#platMktOffers', loadOffers); });
	$(document).on('click', '#platMktPoliciesBtn', function () { openPanel('#platMktPolicies', function () { loadPolicies(); loadDefaultSort(); loadAcceptWindow(); loadRouting(); }); });
	$(document).on('click', '#platMktOrdersBtn', function () { openPanel('#platMktOrders', loadOrders); });
	$(document).on('click', '#platMktOrderStatus button', function () {
		$('#platMktOrderStatus button').removeClass('is-on');
		$(this).addClass('is-on');
		orderStatus = $(this).attr('data-status');
		loadOrders();
	});
	$(document).on('click', '#mktAcceptWindowSave', saveAcceptWindow);
	$(document).on('click', '#mktMultiSellerSave', saveMultiSeller);
	$(document).on('click', '#mktRerouteSave', saveReroute);
	$(document).on('click', '.mkt-routing-close', closeCircuit);                  // MKT-2c
	$(document).on('click', '#mktRoutingTestSave', saveRoutingTest);
	$(document).on('click', '#platMktShortagesBtn', function () { openPanel('#platMktShortages', loadShortages); });
	$(document).on('click', '#platMktShortageStatus button', function () {
		$('#platMktShortageStatus button').removeClass('is-on');
		$(this).addClass('is-on');
		shortageStatus = $(this).attr('data-status');
		loadShortages();
	});
	$(document).on('click', '#platMktPerformanceBtn', function () { openPanel('#platMktPerformance', loadPerformance); });   // MKT-2e
	$(document).on('click', '#platMktPerfDays button', function () {
		$('#platMktPerfDays button').removeClass('is-on');
		$(this).addClass('is-on');
		perfDays = Number($(this).attr('data-days')) || 30;
		loadPerformance();
	});
	$(document).on('click', '.plat-mkt-back', function () {
		$('.plat__panel').hide();
		$('#platTenants').show();
	});
	$(document).on('click', '#platMktOfferStatus button', function () {
		$('#platMktOfferStatus button').removeClass('is-on');
		$(this).addClass('is-on');
		offerStatus = $(this).attr('data-status');
		loadOffers();
	});
	$(document).on('change', '#mktPolType', function () {
		$('.mkt-pol').hide();
		$('.mkt-pol-' + $(this).val()).show();
	});
	$(document).on('click', '#mktPolCreate', createPolicy);
	$(document).on('click', '#mktDefaultSortSave', saveDefaultSort);
})(window);
