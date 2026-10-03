/**
 * MKT-0a — Sell on the MaxTheService marketplace: seller onboarding screen.
 * Markup: templates/fragments/marketplace-seller.html. Server: marketplace-service via the monolith's /mkt/** proxy.
 *
 * Standards this file is built to (microservices/docs/slices/mkt-0a-seller-onboarding.md):
 *   - 8c: the envelope is read through api-response.js (apiOk / apiMessage / apiData / apiFailMessage), never by hand.
 *   - 8d: the server's sentence wins; the strings here are fallbacks for when it sent none.
 *   - 0c: only the pressed button is disabled; the server makes a repeated accept a no-op (UNIQUE per version).
 *   - Every server string is rendered with escHtml / text(), never as HTML.
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

	var lastView = null;

	function line(sel, good, text) {
		$(sel).empty()
			.append($('<span class="glyphicon"></span>').addClass(good ? 'glyphicon-ok text-success' : 'glyphicon-time text-warning'))
			.append(document.createTextNode(' ' + text));
	}

	function render(v) {
		lastView = v;
		line('#mktStatusCapability', v.capabilityOn, v.capabilityOn
			? tr('ui.js.mktCapOn', 'Marketplace selling is switched on.')
			: tr('ui.js.mktCapOff', 'Marketplace selling is switched off. An owner can switch it on in Settings → Configuration.'));
		line('#mktStatusAgreements', v.agreementsCurrent, (v.agreementsCurrent
			? tr('ui.js.mktAgreed', 'Agreements accepted (version {0}).')
			: tr('ui.js.mktNotAgreed', 'Agreements not yet accepted (version {0}).')).replace('{0}', v.requiredVersion));
		var a = v.account, txt;
		if (!a) txt = tr('ui.js.mktNoAccount', 'No seller account yet. Accept the agreements below to apply.');
		else if (a.status === 'APPROVED') txt = tr('ui.js.mktApproved', 'Approved by MaxTheService. You can list products.');
		else if (a.status === 'PENDING_APPROVAL') txt = tr('ui.js.mktPending', 'MaxTheService is reviewing your seller account.');
		else txt = (a.status === 'REJECTED' ? tr('ui.js.mktRejected', 'Not approved') : tr('ui.js.mktSuspended', 'Suspended'))
			+ (a.statusReason ? ': ' + a.statusReason : '.');
		line('#mktStatusAccount', !!a && a.status === 'APPROVED', txt);
		$('#mktAgreementVersion').text('(' + v.requiredVersion + ')');
		var needsApplication = !a || a.status === 'REJECTED';
		$('#mktDisplayNameGroup').toggle(needsApplication);
		if (a && a.displayName && !$('#mktDisplayName').val()) $('#mktDisplayName').val(a.displayName);
		$('#mktAgreementBox').toggle(v.capabilityOn && (!v.agreementsCurrent || needsApplication));
		$('#mktProductsBox').toggle(!!v.canSell);
		$('#mktOffersBox').toggle(!!v.canSell);
		syncButton();
		if (v.canSell) { mktProposalsLoad(); mktOffersLoad(); }
		// MKT-1e: shown to any seller with an account — a suspended seller must still see and reject open orders
		$('#mktIncomingBox').toggle(!!a);
		if (a) mktIncomingLoad();
	}

	// ── MKT-1b: products to publish ───────────────────────────────────────────────────────────────────

	var STATUS = {
		PENDING_REVIEW: ['ui.js.mktMatchPending', 'Waiting for review', 'label-warning'],
		MATCHED: ['ui.js.mktMatchMatched', 'On the marketplace', 'label-success'],
		NEEDS_CORRECTION: ['ui.js.mktMatchFix', 'Needs correction', 'label-danger'],
		REJECTED: ['ui.js.mktMatchRejected', 'Not accepted', 'label-default']
	};

	function mktProposalsLoad() {
		return $.ajax({ url: ctx() + 'mkt/myProposals?size=100', dataType: 'json' }).done(function (res) {
			var $tb = $('#mktProposalsTable tbody').empty();
			var rows = ok(res) ? ((data(res) || {}).content || []) : [];
			if (!ok(res)) {
				$tb.append($('<tr><td colspan="4"></td></tr>').find('td').text(message(res, '')).end());
				return;
			}
			if (!rows.length) {
				$tb.append($('<tr><td colspan="4" class="text-muted"></td></tr>').find('td')
					.text(tr('ui.js.mktNoProposals', 'Nothing proposed yet.')).end());
				return;
			}
			rows.forEach(function (p) {
				var s = STATUS[p.matchStatus] || [null, p.matchStatus, 'label-default'];
				var $tr = $('<tr></tr>').attr('data-id', p.id);
				$tr.append($('<td></td>').text((p.sourceProductName || '') + ' — ' + [p.brand, p.model, p.variant, p.colour].filter(Boolean).join(' ')));
				$tr.append($('<td style="font-family:monospace;font-size:12px"></td>').text(p.proposedIdentityKey || ''));
				$tr.append($('<td></td>').append($('<span class="label"></span>').addClass(s[2])
					.attr('data-status', p.matchStatus).text(s[0] ? tr(s[0], s[1]) : s[1])));
				$tr.append($('<td></td>').text(p.reviewNote || ''));
				$tb.append($tr);
			});
		});
	}

	function mktProposeToggle() {
		var $f = $('#mktProposeForm').toggle();
		if (!$f.is(':visible') || $('#mktProposeProduct option').length) return;
		$('#mktProposeProduct').append($('<option value=""></option>').text(tr('ui.js.mktChooseProduct', 'Choose a product…')));
		$.ajax({ url: ctx() + 'getUserProduct', dataType: 'json' }).done(function (res) {
			var list = typeof global.apiList === 'function' ? global.apiList(res) : ((res && res.collection) || []);
			(list || []).forEach(function (p) {
				$('#mktProposeProduct').append($('<option></option>').attr('value', p.id).text(p.name || ('#' + p.id)));
			});
		});
	}

	function mktPropose(btn) {
		var $b = $(btn), label = $b.html();
		var body = {
			sourceProductId: Number($('#mktProposeProduct').val()) || null,
			brand: $('#mktBrand').val(), model: $('#mktModel').val(), variant: $('#mktVariant').val(),
			colour: $('#mktColour').val(), size: $('#mktSize').val(), packSize: $('#mktPackSize').val(),
			condition: $('#mktCondition').val(), warrantyType: $('#mktWarranty').val(), gtin: $('#mktGtin').val()
		};
		$b.prop('disabled', true).text(tr('ui.js.mktSaving', 'Saving…'));
		$('#mktProposeMsg').text('').css('color', '');
		$.ajax({ url: ctx() + 'mkt/proposeProduct', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify(body) }).done(function (res) {
			if (!ok(res)) { $('#mktProposeMsg').css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
			$('#mktProposeMsg').css('color', '#1f7a4d').text(message(res, tr('ui.js.mktProposeSent', 'Sent to MaxTheService for review.')));
			mktProposalsLoad();
		}).fail(function (xhr) {
			$('#mktProposeMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed')));
		}).always(function () { $b.prop('disabled', false).html(label); });
	}

	function syncButton() {
		$('#mktAcceptBtn').prop('disabled', !$('#mktAgreeChk').is(':checked'));
	}

	function mktSellerLoad() {
		return $.ajax({ url: ctx() + 'mkt/seller', dataType: 'json' }).done(function (res) {
			if (!ok(res)) {
				line('#mktStatusCapability', false, message(res, tr('ui.js.mktLoadFailed', 'Could not load your marketplace status.')));
				return;
			}
			render(data(res));
		}).fail(function (xhr) {
			line('#mktStatusCapability', false, failMessage(xhr, tr('ui.js.mktLoadFailed', 'Could not load your marketplace status.')));
		});
	}

	function mktAcceptAgreements(btn) {
		var $b = $(btn), label = $b.html();
		var body = { version: lastView ? lastView.requiredVersion : 'v1', displayName: $('#mktDisplayName').val() };
		$b.prop('disabled', true).text(tr('ui.js.mktSaving', 'Saving…'));
		$('#mktAcceptMsg').text('').css('color', '');
		$.ajax({ url: ctx() + 'mkt/acceptAgreement', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify(body) }).done(function (res) {
			if (!ok(res)) {
				$('#mktAcceptMsg').css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed')));
				return;
			}
			$('#mktAcceptMsg').css('color', '#1f7a4d').text(message(res, tr('ui.js.mktAccepted', 'Accepted.')));
			$('#mktAgreeChk').prop('checked', false);
			mktSellerLoad();
		}).fail(function (xhr) {
			$('#mktAcceptMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed')));
		}).always(function () {
			$b.html(label);
			syncButton();
		});
	}

	// ── MKT-1c: my offers ─────────────────────────────────────────────────────────────────────────────

	var OFFER_STATUS = {
		DRAFT: ['ui.js.mktOfferDraft', 'Draft', 'label-default'],
		PENDING_REVIEW: ['ui.js.mktMatchPending', 'Waiting for review', 'label-warning'],
		APPROVED: ['ui.js.mktOfferLive', 'Live', 'label-success'],
		REJECTED: ['ui.js.mktMatchRejected', 'Not accepted', 'label-danger'],
		SUSPENDED: ['ui.js.mktSuspended', 'Suspended', 'label-danger']
	};
	var matchedProducts = {};

	function money(v) {
		var n = Number(v || 0);
		return n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
	}

	function mktOffersLoad() {
		return $.ajax({ url: ctx() + 'mkt/myOffers?size=100', dataType: 'json' }).done(function (res) {
			var $tb = $('#mktOffersTable tbody').empty();
			if (!ok(res)) { $tb.append($('<tr><td colspan="6"></td></tr>').find('td').text(message(res, '')).end()); return; }
			var rows = (data(res) || {}).content || [];
			if (!rows.length) {
				$tb.append($('<tr><td colspan="6" class="text-muted"></td></tr>').find('td').text(tr('ui.js.mktNoOffers', 'No offers yet.')).end());
				return;
			}
			rows.forEach(function (o) {
				var s = OFFER_STATUS[o.approvalStatus] || [null, o.approvalStatus, 'label-default'];
				var label = s[0] ? tr(s[0], s[1]) : s[1];
				if (o.approvalStatus === 'APPROVED' && o.paused) label = tr('ui.js.mktOfferPaused', 'Paused');
				var $tr = $('<tr></tr>').attr('data-offer-id', o.id);
				$tr.append($('<td></td>').text(o.productName || ('#' + o.mktProductId)));
				$tr.append($('<td style="font-variant-numeric:tabular-nums"></td>').text(money(o.marketplacePrice)));
				$tr.append($('<td></td>').text((o.deliveryAreas || '').split(',').join(', ')));
				$tr.append($('<td></td>').append($('<span class="label"></span>').addClass(s[2]).attr('data-status', o.approvalStatus).text(label)));
				$tr.append($('<td></td>').text(o.reviewNote || ''));
				var $act = $('<td></td>');
				$('<button type="button" class="btn btn-xs btn-default"></button>').text(tr('ui.edit', 'Edit'))
					.on('click', function () { mktOfferEdit(o); }).appendTo($act);
				if (o.approvalStatus === 'APPROVED') {
					$('<button type="button" class="btn btn-xs btn-default mkt-pause" style="margin-left:4px"></button>')
						.text(o.paused ? tr('ui.js.mktResume', 'Resume') : tr('ui.js.mktPause', 'Pause'))
						.on('click', function () { mktOfferPost({ id: o.id, paused: !o.paused, version: o.version }, null, $(this)); })
						.appendTo($act);
				}
				$tb.append($tr.append($act));
			});
		});
	}

	/** Fill the product and policy pickers: matched products only, active warranty/return policies only. */
	function mktOfferPickers() {
		var a = $.ajax({ url: ctx() + 'mkt/myProposals?size=100', dataType: 'json' }).done(function (res) {
			var $s = $('#mktOfferProduct').empty();
			((data(res) || {}).content || []).filter(function (p) { return p.matchStatus === 'MATCHED' && p.mktProductId; })
				.forEach(function (p) {
					matchedProducts[p.mktProductId] = true;
					$s.append($('<option></option>').attr('value', p.mktProductId)
						.text([p.brand, p.model, p.variant, p.colour, p.size, p.packSize].filter(Boolean).join(' ')));
				});
		});
		var b = $.ajax({ url: ctx() + 'mkt/sellerPolicies', dataType: 'json' }).done(function (res) {
			var list = typeof global.apiList === 'function' ? global.apiList(res) : ((res && res.data) || []);
			var $w = $('#mktOfferWarranty').empty(), $r = $('#mktOfferReturn').empty();
			(list || []).forEach(function (p) {
				($(p.policyType === 'WARRANTY' ? $w : $r)).append($('<option></option>').attr('value', p.id).text(p.name));
			});
		});
		return $.when(a, b);
	}

	function mktOfferNew() {
		$('#mktOfferId, #mktOfferVersion, #mktOfferPrice, #mktOfferArea').val('');
		$('#mktOfferProduct').prop('disabled', false);
		$('#mktOfferForm').show();
		mktOfferPickers();
	}

	function mktOfferEdit(o) {
		mktOfferPickers().always(function () {
			$('#mktOfferId').val(o.id);
			$('#mktOfferVersion').val(o.version);
			$('#mktOfferProduct').val(String(o.mktProductId)).prop('disabled', true);
			$('#mktOfferPrice').val(o.marketplacePrice);
			$('#mktOfferArea').val((o.deliveryAreas || '').split(',').join(', '));
			$('#mktOfferPromise').val(o.promiseHours);
			if (o.warrantyPolicyId) $('#mktOfferWarranty').val(String(o.warrantyPolicyId));
			if (o.returnPolicyId) $('#mktOfferReturn').val(String(o.returnPolicyId));
			$('#mktOfferForm').show();
		});
	}

	function mktOfferSave(btn, submit) {
		var id = $('#mktOfferId').val();
		var body = {
			id: id ? Number(id) : null,
			mktProductId: id ? null : (Number($('#mktOfferProduct').val()) || null),
			marketplacePrice: $('#mktOfferPrice').val() === '' ? null : Number($('#mktOfferPrice').val()),
			deliveryAreas: $('#mktOfferArea').val(),
			promiseHours: Number($('#mktOfferPromise').val()) || null,
			warrantyPolicyId: Number($('#mktOfferWarranty').val()) || null,
			returnPolicyId: Number($('#mktOfferReturn').val()) || null,
			version: $('#mktOfferVersion').val() === '' ? null : Number($('#mktOfferVersion').val())
		};
		mktOfferPost(body, submit, $(btn));
	}

	function mktOfferPost(body, submit, $b) {
		var label = $b.html();
		$b.prop('disabled', true).text(tr('ui.js.mktSaving', 'Saving…'));
		$('#mktOfferMsg').text('').css('color', '');
		$.ajax({ url: ctx() + 'mkt/saveOffer', type: 'POST', contentType: 'application/json', dataType: 'json',
			data: JSON.stringify(body) }).then(function (res) {
			if (!ok(res) || !submit) return res;
			return $.ajax({ url: ctx() + 'mkt/submitOffer', type: 'POST', contentType: 'application/json',
				dataType: 'json', data: JSON.stringify({ id: data(res).id }) });
		}).done(function (res) {
			if (!ok(res)) { $('#mktOfferMsg').css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
			$('#mktOfferMsg').css('color', '#1f7a4d').text(message(res, tr('ui.js.mktOfferSaved', 'Offer saved.')));
			var o = data(res);
			if (o && o.id) { $('#mktOfferId').val(o.id); $('#mktOfferVersion').val(o.version); }
			mktOffersLoad();
		}).fail(function (xhr) {
			$('#mktOfferMsg').css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed')));
		}).always(function () { $b.prop('disabled', false).html(label); });
	}

	// ── MKT-1e: incoming marketplace orders ──────────────────────────────────────────────────────────────

	var SO_STATUS = {
		OFFERED: ['ui.js.mktSoWaiting', 'Waiting for you', 'label-warning'],
		ACCEPTED: ['ui.js.mktSoAccepted', 'Accepted', 'label-success'],
		REJECTED: ['ui.js.mktSoRejected', 'Rejected', 'label-default'],
		EXPIRED: ['ui.js.mktSoExpired', 'Expired', 'label-danger'],
		CANCELLED: ['ui.js.mktSoCancelled', 'Cancelled', 'label-default'],
		UNASSIGNED: ['ui.js.mktSoPlacing', 'Being placed', 'label-default']
	};
	var incomingTimer = null, tickTimer = null;

	function mm(sec) {
		sec = Math.max(0, Math.floor(sec));
		return Math.floor(sec / 60) + ':' + ('0' + (sec % 60)).slice(-2);
	}

	/**
	 * The countdown is driven by the SERVER's seconds-left, turned into a local deadline on arrival — so a seller
	 * whose clock is wrong still sees the true time left. Re-read every 15 s while the section is open.
	 */
	function mktIncomingLoad() {
		var status = $('#mktIncomingStatus').val();
		return $.ajax({ url: ctx() + 'mkt/incomingOrders?size=50' + (status ? '&status=' + encodeURIComponent(status) : ''),
			dataType: 'json' }).done(function (res) {
			var $tb = $('#mktIncomingOrders tbody').empty();
			if (!ok(res)) { $tb.append($('<tr><td colspan="5"></td></tr>').find('td').text(message(res, tr('ui.js.loadFailed', 'Could not load.'))).end()); return; }
			var rows = (data(res) || {}).content || [];
			if (!rows.length) {
				$tb.append($('<tr><td colspan="5" class="text-muted"></td></tr>').find('td')
					.text(tr('ui.js.mktNoIncoming', 'No marketplace orders here.')).end());
			}
			rows.forEach(function (so) { $tb.append(incomingRow(so)); });
			tick();
		}).always(function () {
			clearTimeout(incomingTimer);
			if ($('#MarketplaceDiv').is(':visible')) incomingTimer = setTimeout(mktIncomingLoad, 15000);
		});
	}

	function incomingRow(so) {
		var $tr = $('<tr></tr>').attr('data-so-id', so.id);
		$tr.append($('<td></td>').append($('<b></b>').text(so.orderNo || ''))
			.append($('<div class="text-muted" style="font-size:12px"></div>').text(money(so.total))));
		var $items = $('<td></td>');
		(so.lines || []).forEach(function (l) {
			$items.append($('<div></div>').text(l.line.productName + ' × ' + l.line.quantity + ' @ ' + money(l.line.unitPrice)));
		});
		$tr.append($items);
		$tr.append($('<td style="font-size:13px"></td>')
			.append($('<div></div>').text(so.customerName || ''))
			.append($('<div></div>').append($('<a></a>').attr('href', 'tel:' + (so.customerPhone || '')).text(so.customerPhone || '')))
			.append($('<div class="text-muted"></div>').text([so.address, so.city].filter(Boolean).join(', '))));
		var s = SO_STATUS[so.acceptanceStatus] || [null, so.acceptanceStatus, 'label-default'];
		var $st = $('<td></td>').append($('<span class="label"></span>').addClass(s[2]).attr('data-status', so.acceptanceStatus)
			.text(s[0] ? tr(s[0], s[1]) : s[1]));
		if (so.acceptanceStatus === 'OFFERED' && so.secondsLeft !== null && so.secondsLeft !== undefined) {
			$st.append(' ').append($('<b class="mkt-accept-countdown" style="font-variant-numeric:tabular-nums"></b>')
				.attr('data-deadline', Date.now() + so.secondsLeft * 1000).text(mm(so.secondsLeft)));
		}
		if (so.invoiceNo) $st.append($('<div style="font-size:12px"></div>').text(tr('ui.js.mktSoInvoice', 'Invoice {0}').replace('{0}', so.invoiceNo)));
		if (so.rejectReason) $st.append($('<div class="text-muted" style="font-size:12px"></div>').text(so.rejectReason));
		$tr.append($st);

		var $act = $('<td style="min-width:220px"></td>');
		if (so.acceptanceStatus === 'OFFERED') {
			var serialInputs = [];
			(so.lines || []).forEach(function (l) {
				for (var i = 0; i < l.line.quantity; i++) {
					var $in = $('<input type="text" class="form-control input-sm mkt-imei" maxlength="40" autocomplete="off">')
						.attr('placeholder', tr('ui.js.mktImeiPh', 'IMEI / serial (if the item has one)'))
						.attr('aria-label', tr('ui.js.mktImeiPh', 'IMEI / serial (if the item has one)'))
						.attr('data-line-id', l.lineId).css('margin-bottom', '4px');
					serialInputs.push($in);
					$act.append($in);
				}
			});
			var $msg = $('<div role="status" style="font-size:12px;margin-top:4px"></div>');
			$('<button type="button" class="btn btn-xs btn-success mkt-accept"></button>').text(tr('ui.js.mktAccept', 'Accept'))
				.on('click', function () {
					var serials = {};
					serialInputs.forEach(function ($in) {
						var v = $.trim($in.val());
						if (!v) return;
						var k = $in.attr('data-line-id');
						(serials[k] = serials[k] || []).push(v);
					});
					soPost('mkt/acceptOrder', { id: so.id, version: so.version, serials: serials }, $(this), $msg);
				}).appendTo($act);
			var $reason = $('<input type="text" class="form-control input-sm" maxlength="300" style="margin-top:6px">')
				.attr('placeholder', tr('ui.js.mktRejectPh', 'Why you cannot fulfil it'))
				.attr('aria-label', tr('ui.js.mktRejectPh', 'Why you cannot fulfil it'));
			$act.append($reason);
			$('<button type="button" class="btn btn-xs btn-default mkt-reject" style="margin-top:4px"></button>')
				.text(tr('ui.js.mktReject', 'Reject'))
				.on('click', function () { soPost('mkt/rejectOrder', { id: so.id, version: so.version, reason: $reason.val() }, $(this), $msg); })
				.appendTo($act);
			$act.append($msg);
		} else if (so.storeOrderNo) {
			$act.append($('<span class="text-muted" style="font-size:12px"></span>')
				.text(tr('ui.js.mktSoInYourOrders', 'In your orders as {0}').replace('{0}', so.storeOrderNo)));
		}
		$tr.append($act);
		return $tr;
	}

	/** Only the clicked button is disabled; the server's sentence is shown as written (standard 8a). */
	function soPost(path, body, $b, $msg) {
		var label = $b.text();
		$b.prop('disabled', true);
		$msg.text('').css('color', '');
		$.ajax({ url: ctx() + path, type: 'POST', contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
			.done(function (res) {
				if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
				mktIncomingLoad();
			})
			.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false).text(label); });
	}

	function tick() {
		clearInterval(tickTimer);
		tickTimer = setInterval(function () {
			var any = false;
			$('#mktIncomingOrders .mkt-accept-countdown').each(function () {
				var left = (Number($(this).attr('data-deadline')) - Date.now()) / 1000;
				$(this).text(mm(left)).css('color', left < 60 ? '#b3261e' : '');
				any = true;
			});
			if (!any) clearInterval(tickTimer);
		}, 1000);
	}

	$(document).on('change', '#mktIncomingStatus', mktIncomingLoad);

	function showMarketplaceSeller() {
		$('.formDiv').hide();
		$('#MarketplaceDiv').show();
		mktSellerLoad();
	}

	$(document).on('change', '#mktAgreeChk', syncButton);

	global.showMarketplaceSeller = showMarketplaceSeller;
	global.mktSellerLoad = mktSellerLoad;
	global.mktAcceptAgreements = mktAcceptAgreements;
	global.mktProposeToggle = mktProposeToggle;
	global.mktPropose = mktPropose;
	global.mktProposalsLoad = mktProposalsLoad;
	global.mktOfferNew = mktOfferNew;
	global.mktOfferSave = mktOfferSave;
	global.mktOffersLoad = mktOffersLoad;
	global.mktIncomingLoad = mktIncomingLoad;
})(window);
