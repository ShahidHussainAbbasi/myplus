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
		syncButton();
		if (v.canSell) mktProposalsLoad();
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
})(window);
