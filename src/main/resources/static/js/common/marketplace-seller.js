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
		syncButton();
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
})(window);
