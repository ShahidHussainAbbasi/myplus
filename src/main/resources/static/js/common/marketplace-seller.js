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
		$('#mktTasksBox').toggle(!!a);
		if (a) mktTasksLoad();
		// MKT-1g: the statement — loaded when asked for, it is a page of money, not a glance
		$('#mktStatementBox').toggle(!!a);
		// MKT-2e: the shop's own scorecard, the figures MaxTheService sees for it
		$('#mktPerfBox').toggle(!!a);
		if (a) mktPerfLoad();
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
	function mktIncomingLoad(auto) {
		// MKT-2b: the 15 s refresh never wipes a dispute the seller is writing; it tries again on the next tick
		if (auto === true && $('#mktIncomingOrders .mkt-dispute-form:not([hidden])').length) {
			clearTimeout(incomingTimer);
			incomingTimer = setTimeout(function () { mktIncomingLoad(true); }, 15000);
			return $.Deferred().resolve().promise();
		}
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
			if ($('#MarketplaceDiv').is(':visible')) incomingTimer = setTimeout(function () { mktIncomingLoad(true); }, 15000);
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
		if (so.shortage) $st.append(shortageBox(so.shortage));
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
					soPost('mkt/acceptOrder', { id: so.id, version: so.version, serials: serials }, $(this), $msg, $tr);
				}).appendTo($act);
			var $reason = $('<input type="text" class="form-control input-sm" maxlength="300" style="margin-top:6px">')
				.attr('placeholder', tr('ui.js.mktRejectPh', 'Why you cannot fulfil it'))
				.attr('aria-label', tr('ui.js.mktRejectPh', 'Why you cannot fulfil it'));
			$act.append($reason);
			// MKT-2b: why it cannot be fulfilled — recorded against the seller (R11.4), never debited (R12.4)
			var $cause = $('<select class="form-control input-sm mkt-reject-cause" style="margin-top:4px"></select>')
				.attr('aria-label', tr('ui.js.mktCauseLabel', 'Cause'));
			CAUSES.forEach(function (c) { $cause.append($('<option></option>').val(c[0]).text(tr(c[1], c[2]))); });
			$act.append($cause);
			$('<button type="button" class="btn btn-xs btn-default mkt-reject" style="margin-top:4px"></button>')
				.text(tr('ui.js.mktReject', 'Reject'))
				.on('click', function () {
					soPost('mkt/rejectOrder', { id: so.id, version: so.version, reason: $reason.val(), cause: $cause.val() }, $(this), $msg, $tr);
				})
				.appendTo($act);
			$act.append($msg);
		} else if (so.storeOrderNo) {
			$act.append($('<span class="text-muted" style="font-size:12px"></span>')
				.text(tr('ui.js.mktSoInYourOrders', 'In your orders as {0}').replace('{0}', so.storeOrderNo)));
		}
		$tr.append($act);
		return $tr;
	}

	/** MKT-2b — the causes a seller can name when it rejects; the clock records NO_RESPONSE itself. */
	var CAUSES = [['MERCHANT_STALE_STOCK', 'ui.js.mktCauseMerchant', 'Out of stock in my shop'],
		['SUPPLIER_STALE_STOCK', 'ui.js.mktCauseSupplier', 'My supplier could not deliver'],
		['PLATFORM_SYNC_DEFECT', 'ui.js.mktCausePlatform', 'MaxTheService showed the wrong stock']];
	var CAUSE_LABEL = { NO_RESPONSE: ['ui.js.mktCauseNoResponse', 'Not accepted in time'] };
	CAUSES.forEach(function (c) { CAUSE_LABEL[c[0]] = [c[1], c[2]]; });
	var SH_STATUS = {
		RECORDED: ['ui.js.mktShRecorded', 'Recorded'], DISPUTED: ['ui.js.mktShDisputed', 'Disputed: MaxTheService is reviewing it'],
		UPHELD: ['ui.js.mktShUpheld', 'Upheld by MaxTheService'], OVERTURNED: ['ui.js.mktShOverturned', 'Overturned by MaxTheService']
	};

	/** The record kept of an unfulfilled part: its cause, and a dispute while it is only recorded. Nothing is charged. */
	function shortageBox(sh) {
		var c = CAUSE_LABEL[sh.cause] || [null, sh.cause], st = SH_STATUS[sh.status] || [null, sh.status];
		var $b = $('<div class="mkt-so-shortage" style="font-size:12px;margin-top:4px"></div>').attr('data-shortage-status', sh.status);
		$b.append($('<div></div>').text(tr('ui.js.mktShCause', 'Cause: {0}').replace('{0}', c[0] ? tr(c[0], c[1]) : c[1])
			+ ' · ' + (st[0] ? tr(st[0], st[1]) : st[1])));
		if (sh.disputeNote) $b.append($('<div class="text-muted"></div>').text(tr('ui.js.mktShYouSaid', 'You said: {0}').replace('{0}', sh.disputeNote)));
		if (sh.decisionNote) $b.append($('<div class="text-muted"></div>').text(tr('ui.js.mktShTheySaid', 'MaxTheService: {0}').replace('{0}', sh.decisionNote)));
		if (sh.canDispute) {
			var $form = $('<div class="mkt-dispute-form" hidden></div>');
			var $note = $('<textarea class="form-control input-sm mkt-dispute-note" maxlength="500" rows="2"></textarea>')
				.attr('aria-label', tr('ui.js.mktDisputeWhy', 'Why this cause is wrong'))
				.attr('placeholder', tr('ui.js.mktDisputeWhy', 'Why this cause is wrong'));
			var $msg = $('<div role="status"></div>');
			var $send = $('<button type="button" class="btn btn-xs btn-primary mkt-dispute-send" style="margin-top:4px"></button>')
				.text(tr('ui.js.mktDisputeSend', 'Send dispute'));
			$send.on('click', function () {
				$send.prop('disabled', true);
				$msg.text('').css('color', '');
				$.ajax({ url: ctx() + 'mkt/shortageDispute', type: 'POST', contentType: 'application/json', dataType: 'json',
					data: JSON.stringify({ id: sh.id, note: $note.val() }) })
					.done(function (res) {
						if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
						$b.replaceWith(shortageBox(data(res)).append($('<div role="status" style="color:#1b5e20"></div>').text(message(res, ''))));
					})
					.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
					.always(function () { $send.prop('disabled', false); });
			});
			$form.append($note).append($send).append($msg);
			$('<button type="button" class="btn btn-xs btn-default mkt-dispute" style="margin-top:4px"></button>')
				.text(tr('ui.js.mktDispute', 'Dispute this cause'))
				.on('click', function () { $form.prop('hidden', false); $(this).remove(); $note.trigger('focus'); })
				.appendTo($b);
			$b.append($form);
		}
		return $b;
	}

	/**
	 * Only the clicked button is disabled; the server's sentence is shown as written (standard 8a). On success the row
	 * is redrawn IN PLACE from the server's answer, with its sentence: reloading the "Waiting for you" filter made an
	 * accepted order simply vanish, so the seller never saw it go through (found by the MKT-1e gate on a live stack).
	 * The 15 s refresh reconciles the list afterwards.
	 */
	function soPost(path, body, $b, $msg, $tr) {
		var label = $b.text();
		$b.prop('disabled', true);
		$msg.text('').css('color', '');
		$.ajax({ url: ctx() + path, type: 'POST', contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
			.done(function (res) {
				if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
				var $now = incomingRow(data(res));
				$now.find('td:last').append($('<div role="status" style="font-size:12px;color:#1b5e20"></div>').text(message(res, '')));
				$tr.replaceWith($now);
			})
			.fail(function (xhr) { $msg.css('color', '#b3261e').text(failMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))); })
			.always(function () { $b.prop('disabled', false).text(label); });
	}

	// ── MKT-1f: tasks from MaxTheService ───────────────────────────────────────────────────────────────

	var OUTCOMES = [['RESTOCK', 'ui.js.mktOutRestock', 'Restock: back on the shelf'],
		['QUARANTINE', 'ui.js.mktOutQuarantine', 'Quarantine: faulty, not sellable'],
		['WRITE_OFF', 'ui.js.mktOutWriteOff', 'Write off: unusable']];

	function mktTasksLoad() {
		return $.ajax({ url: ctx() + 'mkt/sellerTasks', dataType: 'json' }).done(function (res) {
			var $box = $('#mktTasks').empty();
			if (!ok(res)) { $box.append($('<p style="color:#b3261e"></p>').text(message(res, tr('ui.js.loadFailed', 'Could not load.')))); return; }
			var tasks = data(res) || [];
			if (!tasks.length) { $box.append($('<p class="text-muted"></p>').text(tr('ui.js.mktNoTasks', 'Nothing from MaxTheService right now.'))); return; }
			tasks.forEach(function (t) { $box.append(taskPanel(t)); });
		});
	}

	function taskPanel(t) {
		var $p = $('<div class="panel panel-default mkt-task" style="padding:10px 12px;margin-bottom:10px"></div>').attr('data-case-no', t.caseNo);
		$('<div style="font-weight:700"></div>').text(t.orderNo + ' · ' + t.caseNo).appendTo($p);
		$('<div style="font-size:13px"></div>').text([t.customerName, t.customerPhone, t.address, t.city].filter(Boolean).join(' · ')).appendTo($p);
		var $ul = $('<ul style="list-style:none;padding:0;margin:8px 0;font-size:13px"></ul>').appendTo($p);
		(t.messages || []).forEach(function (m) {
			$('<li style="margin-bottom:4px"></li>').append($('<b></b>').text(m.from + ': ')).append(document.createTextNode(m.body)).appendTo($ul);
		});
		var $msg = $('<div role="status" style="font-size:12px;min-height:1.2em"></div>');
		(t.returns || []).filter(function (r) { return r.status === 'APPROVED'; }).forEach(function (r) {
			var $r = $('<div class="form-inline mkt-return" style="margin:6px 0"></div>').attr('data-return-no', r.returnNo);
			$('<span style="margin-right:8px"></span>').text(r.returnNo + ' · ' + r.quantity + ' × ' + (r.productName || '')).appendTo($r);
			var $out = $('<select class="form-control input-sm mkt-outcome"></select>').attr('aria-label', tr('ui.js.mktOutcome', 'What happens to the item')).appendTo($r);
			OUTCOMES.forEach(function (o) { $('<option></option>').val(o[0]).text(tr(o[1], o[2])).appendTo($out); });
			var $cash = null;
			if (t.paymentMode !== 'CARD') {
				var $l = $('<label style="margin:0 8px;font-weight:400"></label>');
				$cash = $('<input type="checkbox" class="mkt-cash">').appendTo($l);
				$l.append(document.createTextNode(' ' + tr('ui.js.mktCashBack', 'Cash handed back') + ' (' + money(r.refundAmount) + ')')).appendTo($r);
			}
			$('<button type="button" class="btn btn-xs btn-success mkt-received"></button>').text(tr('ui.js.mktReceived', 'Item received'))
				.on('click', function () {
					var $b = $(this);
					taskPost('mkt/returnReceived', { returnNo: r.returnNo, outcome: $out.val(), cashHandedBack: $cash ? $cash.is(':checked') : null }, $b, $msg);
				}).appendTo($r);
			$p.append($r);
		});
		var $ta = $('<textarea class="form-control input-sm mkt-task-reply" rows="2" maxlength="2000"></textarea>')
			.attr('aria-label', tr('ui.js.mktTaskAnswer', 'Your answer to MaxTheService')).appendTo($p);
		$('<button type="button" class="btn btn-xs btn-primary mkt-task-send" style="margin-top:6px"></button>').text(tr('ui.js.mktCaseSend', 'Send'))
			.on('click', function () { taskPost('mkt/sellerTaskReply', { caseNo: t.caseNo, body: $ta.val() }, $(this), $msg); }).appendTo($p);
		$p.append($msg);
		return $p;
	}

	/** POST, then show the server's sentence and redraw the tasks (a finished return leaves the list). */
	function taskPost(path, body, $b, $msg) {
		var label = $b.text();
		$b.prop('disabled', true);
		$msg.text('').css('color', '');
		$.ajax({ url: ctx() + path, type: 'POST', contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
			.done(function (res) {
				if (!ok(res)) { $msg.css('color', '#b3261e').text(message(res, tr('ui.js.saveFailed', 'Save failed'))); return; }
				var said = message(res, '');
				mktTasksLoad().done(function () { $('#mktTasks').prepend($('<p role="status" style="color:#1b5e20;font-size:13px"></p>').text(said)); });
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

	// ── MKT-1g: settlement statement (read-only) ─────────────────────────────────────────────────────

	var STATUS_TEXT = {
		NOT_ELIGIBLE: ['ui.js.mktSetNotDelivered', 'Not delivered yet'],
		PENDING_RETURN_WINDOW: ['ui.js.mktSetWindow', 'Return days running'],
		ON_HOLD: ['ui.js.mktSetHold', 'On hold: a return is open'],
		ELIGIBLE: ['ui.js.mktSetEligible', 'Payable'],
		APPROVED: ['ui.js.mktSetApproved', 'Payout approved'],
		PROCESSING: ['ui.js.mktSetProcessing', 'Being paid'],
		PAID: ['ui.js.mktSetPaid', 'Paid'],
		REVERSED: ['ui.js.mktSetReversed', 'Reversed'],
		DISPUTED: ['ui.js.mktSetDisputed', 'Disputed']
	};

	function amountCell(cls, v) { return $('<td class="text-right"></td>').addClass(cls).text(money(v)); }

	function statementRow(s) {
		var st = STATUS_TEXT[s.status] || [null, s.status];
		var other = [s.delivery, s.fees, s.tax, s.reserve, s.adjustment].reduce(function (a, b) { return a + Number(b || 0); }, 0);
		var $tr = $('<tr class="mkt-line"></tr>').attr('data-line-id', s.id).attr('data-status', s.status);
		$('<td></td>').append($('<b></b>').text(s.orderNo || ''))
			.append($('<div class="text-muted" style="font-size:12px"></div>').text((s.quantity || 1) + ' × ' + (s.productName || '')
				+ (s.collectedBy === 'SELLER' ? ' · ' + tr('ui.js.mktSetCod', 'cash on delivery') : '')))
			.appendTo($tr);
		$('<td></td>').text(st[0] ? tr(st[0], st[1]) : st[1])
			.append(s.payoutNo ? $('<div class="text-muted" style="font-size:12px"></div>').text(s.payoutNo) : null).appendTo($tr);
		$('<td class="mkt-eligible-on"></td>').text(s.eligibleOn || '—').appendTo($tr);
		$tr.append(amountCell('mkt-customer-amount', s.customerAmount)).append(amountCell('mkt-commission', s.commission));
		// the five smaller deductions share one column; each keeps its own value for whoever adds the line up
		var $d = $('<td class="text-right"></td>').text(money(other)).appendTo($tr);
		[['mkt-delivery', s.delivery], ['mkt-fees', s.fees], ['mkt-tax', s.tax], ['mkt-reserve', s.reserve], ['mkt-adjustment', s.adjustment]]
			.forEach(function (p) { $('<span hidden></span>').addClass(p[0]).text(Number(p[1] || 0).toFixed(2)).appendTo($d); });
		$tr.append(amountCell('mkt-payable', s.payable));
		return $tr;
	}

	function emptyRow(cols, text, muted) {
		return $('<tr></tr>').append($('<td></td>').attr('colspan', cols).toggleClass('text-muted', !!muted).text(text));
	}

	function mktStatementLoad() {
		$('#mktStatementBody').show();
		var $tb = $('#mktStatementTable tbody').empty();
		var a = $.ajax({ url: ctx() + 'mkt/statement?size=50', dataType: 'json' }).done(function (res) {
			if (!ok(res)) { $tb.append(emptyRow(7, message(res, tr('ui.js.loadFailed', 'Could not load.')))); return; }
			var rows = (data(res) || {}).content || [];
			if (!rows.length) $tb.append(emptyRow(7, tr('ui.js.mktSetEmpty', 'No marketplace sales yet.'), true));
			rows.forEach(function (s) { $tb.append(statementRow(s)); });
		}).fail(function (xhr) { $tb.append(emptyRow(7, failMessage(xhr, tr('ui.js.loadFailed', 'Could not load.')))); });
		var b = $.ajax({ url: ctx() + 'mkt/settlementAccount', dataType: 'json' }).done(function (res) {
			if (!ok(res)) return;
			var v = data(res) || {}, bal = Number(v.balance || 0);
			$('#mktBalance').text(bal >= 0
				? tr('ui.js.mktBalanceOwed', 'MaxTheService owes you Rs {0}.').replace('{0}', money(bal))
				: tr('ui.js.mktBalanceOwing', 'You owe MaxTheService Rs {0} in commission.').replace('{0}', money(-bal)))
				.append(v.openPayout ? $('<span class="text-muted" style="font-weight:400"></span>')
					.text(' ' + tr('ui.js.mktPayoutOpen', 'Payout {0}: {1}.').replace('{0}', v.openPayout.payoutNo)
						.replace('{1}', v.openPayout.status)) : null);
			// MKT-2d: cash orders: what the seller owes MaxTheService, by when, and whether its cash orders are stopped
			var c = v.cod || {}, $cod = $('#mktCodStanding').empty().hide();
			if (Number(c.owed || 0) > 0) {
				$cod.show().toggleClass('alert-danger', !!c.overdue).toggleClass('alert-warning', !c.overdue)
					.append($('<span></span>').text((c.overdue
						? tr('ui.js.mktCodSellerOverdue', 'Overdue: please pay MaxTheService Rs {0} for your cash orders. It was due by {1}.')
						: tr('ui.js.mktCodSellerDue', 'Please pay MaxTheService Rs {0} for your cash orders by {1}.'))
						.replace('{0}', money(c.owed)).replace('{1}', c.payBy || '')));
				if (c.codStopped) $('<div style="margin-top:4px;font-weight:600"></div>')
					.text(tr('ui.js.mktCodSellerStopped', 'Customers cannot choose cash on delivery from you until you pay.')).appendTo($cod);
			}
			var $lt = $('#mktLedgerTable tbody').empty();
			(v.entries || []).forEach(function (e) {
				$('<tr class="mkt-entry"></tr>').attr('data-entry-type', e.entryType)
					.append($('<td></td>').text((e.effectiveAt || '').substring(0, 10)))
					.append($('<td></td>').text(e.entryType).append(e.memo ? $('<div class="text-muted" style="font-size:12px"></div>').text(e.memo) : null))
					.append($('<td></td>').text(e.ref || ''))
					.append($('<td class="text-right"></td>').text(Number(e.credit) ? money(e.credit) : ''))
					.append($('<td class="text-right"></td>').text(Number(e.debit) ? money(e.debit) : ''))
					.appendTo($lt);
			});
		});
		return $.when(a, b);
	}

	// ── MKT-2e: your performance ──────────────────────────────────────────────────────────────────────
	var PERF_FLAG = {
		LOW_ACCEPTANCE: ['ui.js.mktPerfFlagAcceptance', 'Accepts fewer than 80% of its orders'],
		LATE_DELIVERY: ['ui.js.mktPerfFlagLate', 'Delivers fewer than 90% on time'],
		COD_OVERDUE: ['ui.js.mktPerfFlagCod', 'Late paying for cash orders']
	};
	function perfRate(rate, part, whole) {
		if (rate === null || rate === undefined) return tr('ui.js.mktPerfNotEnough', 'Not enough orders yet');
		return tr('ui.js.mktPerfRate', '{0}% ({1} of {2})').replace('{0}', Math.round(rate * 100)).replace('{1}', part).replace('{2}', whole);
	}
	function mktPerfLoad() {
		return $.ajax({ url: ctx() + 'mkt/myPerformance?days=30', dataType: 'json' }).done(function (res) {
			var $f = $('#mktPerfFlags').empty();
			if (!ok(res)) { $f.text(message(res, tr('ui.js.loadFailed', 'Could not load.'))); return; }
			var r = ((data(res) || {}).sellers || [])[0] || {};
			$('#mktPerfAccept').text(perfRate(r.acceptanceRate, r.accepted || 0, (r.accepted || 0) + (r.missed || 0)));
			var m = r.avgMinutesToAccept;
			$('#mktPerfSpeed').text(m === null || m === undefined ? '—' : m < 1 ? tr('ui.js.mktPerfUnderMinute', 'Under a minute on average')
				: tr('ui.js.mktPerfMinutes', '{0} min on average').replace('{0}', m));
			$('#mktPerfOnTime').text(perfRate(r.onTimeRate, r.onTime || 0, r.due || 0));
			$('#mktPerfReturns').text(String(r.sellerFaultReturns || 0));
			if (r.disputed) $('<div class="text-muted" style="font-size:13px"></div>')
				.text(tr('ui.js.mktPerfMineDisputed', '{0} unfulfilled orders you disputed are not counted until MaxTheService decides.').replace('{0}', r.disputed)).appendTo($f);
			(r.flags || []).forEach(function (k) {
				var v = PERF_FLAG[k];
				$('<div class="alert alert-warning mkt-perf-flag" style="padding:6px 12px;margin:6px 0 0"></div>').attr('data-flag', k)
					.text(tr('ui.js.mktPerfNoted', 'MaxTheService has noted: {0}.').replace('{0}', v ? tr(v[0], v[1]) : k)).appendTo($f);
			});
		}).fail(function (xhr) { $('#mktPerfFlags').text(failMessage(xhr, tr('ui.js.loadFailed', 'Could not load.'))); });
	}

	$(document).on('click', '#mktStatementTab', function () { mktStatementLoad(); });
	$(document).on('change', '#mktIncomingStatus', mktIncomingLoad);
	// Bound here, NOT as inline onclick: inside a <form>, an inline handler resolves names through the form's named
	// elements first, so onclick="mktOfferSave(...)" on <button id="mktOfferSave"> called the BUTTON, not this
	// function — both buttons were dead on the real dashboard (found by the MKT-1c gate on a live stack).
	$(document).on('click', '#mktOfferSave', function () { mktOfferSave(this, false); });
	$(document).on('click', '#mktOfferSubmit', function () { mktOfferSave(this, true); });

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
	global.mktTasksLoad = mktTasksLoad;
	global.mktStatementLoad = mktStatementLoad;
	global.mktPerfLoad = mktPerfLoad;
})(window);
