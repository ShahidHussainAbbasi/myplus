/*
 * MKT-1e2 — the marketplace customer's account on the public page: sign in / create an account (phone + password),
 * My orders, add an order placed before (number + phone), cancel while the seller has not answered, and the payment
 * choice at checkout.
 *
 * The session is an HttpOnly cookie set by the server: this script never sees the token. Every POST carries the
 * page's CSRF token. Sentences come from the server as written (standard 8a); this file adds only labels.
 */
(function (global) {
	'use strict';
	var doc = global.document;
	var CTX = doc.body.getAttribute('data-ctx') || '/';
	var P = global.mktPage;
	var me = null;                 // {name, phone} when signed in
	var creating = false;

	function $(id) { return doc.getElementById(id); }
	function tr() { return P.tr.apply(null, arguments); }
	function el(tag, cls, text) {
		var n = doc.createElement(tag);
		if (cls) n.className = cls;
		if (text !== undefined && text !== null) n.textContent = text;
		return n;
	}
	function ok(r) { return typeof global.apiOk === 'function' ? global.apiOk(r) : !!(r && r.success === true); }
	function data(r) { return typeof global.apiData === 'function' ? global.apiData(r) : (r && r.data); }
	function list(r) { var d = data(r); return (d && d.content) || []; }
	function msg(r, fb) { return (r && r.message) || fb; }

	function call(method, path, body) {
		return fetch(CTX + path, {
			method: method, credentials: 'same-origin', redirect: 'manual',
			headers: method === 'GET' ? { Accept: 'application/json' } : P.csrfHeaders(),
			body: method === 'GET' ? undefined : JSON.stringify(body || {})
		}).then(function (r) {
			if (r.status === 403 || r.type === 'opaqueredirect')
				return { success: false, message: tr('ui.js.mktSessionExpired', 'This page expired. Please reload it and try again.') };
			if (r.status >= 500) return { success: false, message: tr('ui.js.loadFailed', 'Could not load.') };
			return r.json().catch(function () { return { success: false, message: tr('ui.js.loadFailed', 'Could not load.') }; });
		}).catch(function () {
			return { success: false, message: tr('ui.js.mktOffline', 'The marketplace is not reachable. Check your connection and try again.') };
		});
	}

	// ── who is signed in ───────────────────────────────────────────────────────────────────────────────
	function setMe(m) {
		me = m;
		var b = $('mktAccountBtn');
		b.textContent = me ? me.name : tr('ui.js.mktAccSignIn', 'Sign in');
		b.setAttribute('aria-label', me ? tr('ui.js.mktMyOrders', 'My orders') + ' — ' + me.name : tr('ui.js.mktAccSignIn', 'Sign in'));
		$('mktPayChoice').hidden = !me;
		syncPay();
	}

	function refreshMe() {
		return call('GET', 'marketplace/account/me').then(function (r) { setMe(ok(r) ? data(r) : null); });
	}

	// ── the account view ───────────────────────────────────────────────────────────────────────────────
	function open() {
		doc.title = tr('ui.js.mktMyOrders', 'My orders');
		return refreshMe().then(function () {
			$('mktAccSignIn').hidden = !!me;
			$('mktAccOrdersBox').hidden = !me;
			if (me) {
				$('mktMyOrdersTitle').focus();
				return loadOrders();
			}
			$('mktAccTitle').focus();
			return null;
		});
	}

	function mode(createMode) {
		creating = createMode;
		$('mktAccNameRow').hidden = !creating;
		$('mktAccHint').hidden = !creating;
		$('mktAccPassword').setAttribute('autocomplete', creating ? 'new-password' : 'current-password');
		$('mktAccSubmit').textContent = creating ? tr('ui.js.mktAccCreateBtn', 'Create account') : tr('ui.js.mktAccSignIn', 'Sign in');
		$('mktAccCreate').textContent = creating ? tr('ui.js.mktAccHaveOne', 'Have an account? Sign in')
			: tr('ui.js.mktAccCreateLink', 'New here? Create an account');
		$('mktAccMsg').textContent = '';
	}

	function submit(ev) {
		ev.preventDefault();
		var b = $('mktAccSubmit'), label = b.textContent;
		b.disabled = true;
		$('mktAccMsg').textContent = '';
		var body = { phone: $('mktAccPhone').value, password: $('mktAccPassword').value };
		if (creating) body.name = $('mktAccName').value;
		call('POST', 'marketplace/account/' + (creating ? 'register' : 'login'), body).then(function (r) {
			if (!ok(r)) { $('mktAccMsg').textContent = msg(r, tr('ui.js.saveFailed', 'Save failed')); return null; }
			$('mktAccPassword').value = '';
			setMe(data(r));
			return open();
		}).then(function () { b.disabled = false; b.textContent = label; });
	}

	var ST = {
		SUBMITTED: ['wait', 'ui.js.mktStWaiting', 'Waiting for the seller'],
		PAYMENT_PENDING: ['wait', 'ui.js.mktPayConfirming', 'Confirming your payment'],
		CONFIRMED: ['ok', 'ui.js.mktStConfirmed', 'Confirmed'],
		CANCELLED: ['bad', 'ui.js.mktCancelledState', 'Cancelled']
	};
	var PAY = {
		UNPAID: ['ui.js.mktPayCodShort', 'Cash on delivery'], CAPTURED: ['ui.js.mktPaidOnline', 'Paid online'],
		REFUNDED: ['ui.js.mktRefunded', 'Refunded'], FAILED: ['ui.js.mktPayFailed', 'Payment declined']
	};

	function loadOrders() {
		var ul = $('mktMyOrders');
		return call('GET', 'marketplace/account/orders?size=50').then(function (r) {
			ul.textContent = '';
			if (!ok(r)) { ul.appendChild(el('li', 'err', msg(r, tr('ui.js.loadFailed', 'Could not load.')))); return; }
			var rows = list(r);
			if (!rows.length) ul.appendChild(el('li', 'terms', tr('ui.js.mktNoOrders', 'No orders yet.')));
			rows.forEach(function (o) { ul.appendChild(orderItem(o)); });
		});
	}

	function orderItem(o) {
		var li = el('li');
		li.setAttribute('data-order-no', o.orderNo);
		var s = ST[o.status] || ['wait', null, o.status];
		var top = el('div', 'row');
		top.appendChild(el('b', 'orderno', o.orderNo));
		top.appendChild(el('span', 'st ' + s[0], s[1] ? tr(s[1], s[2]) : s[2]));
		li.appendChild(top);
		(o.lines || []).forEach(function (l) { li.appendChild(el('span', 'terms', l.productName + ' × ' + l.quantity)); });
		var mid = el('div', 'row');
		mid.appendChild(el('span', null, (o.sellerName || '') + (o.city ? ' · ' + o.city : '')));
		var p = PAY[o.paymentStatus] || [null, o.paymentStatus];
		mid.appendChild(el('span', null, P.money(o.total) + ' · ' + (o.paymentMode === 'CARD' && o.paymentStatus === 'UNPAID'
			? tr('ui.js.mktPaidOnline', 'Paid online') : (p[0] ? tr(p[0], p[1]) : p[1]))));
		li.appendChild(mid);
		if (o.cancelReason) li.appendChild(el('span', 'terms', o.cancelReason));
		if (o.canCancel) {
			var b = el('button', 'mkt-cancel', tr('ui.js.mktCancelBtn', 'Cancel order'));
			b.type = 'button';
			b.addEventListener('click', function () { cancel(o, b); });
			li.appendChild(b);
		}
		return li;
	}

	function cancel(o, b) {
		var ask = typeof global.uiPromptConfirm === 'function' ? global.uiPromptConfirm({
			title: tr('ui.js.mktCancelTitle', 'Cancel this order?'),
			message: o.orderNo,
			input: { label: tr('ui.js.mktCancelReason', 'Why (optional)') },
			confirmText: tr('ui.js.mktCancelBtn', 'Cancel order'),
			cancelText: tr('ui.js.mktKeepOrder', 'Keep it')
		}) : Promise.resolve('');
		ask.then(function (reason) {
			if (reason === null) return null;                                  // the shopper kept it
			b.disabled = true;
			return call('POST', 'marketplace/account/orders/' + encodeURIComponent(o.orderNo) + '/cancel', { reason: reason || '' })
				.then(function (r) {
					if (!ok(r)) { b.disabled = false; (global.uiAlert || alert)(msg(r, tr('ui.js.saveFailed', 'Save failed'))); return null; }
					return loadOrders();
				});
		});
	}

	function claim(ev) {
		ev.preventDefault();
		$('mktClaimMsg').textContent = '';
		call('POST', 'marketplace/account/claim', { orderNo: $('mktClaimNo').value, phone: $('mktClaimPhone').value }).then(function (r) {
			if (!ok(r)) { $('mktClaimMsg').textContent = msg(r, tr('ui.js.saveFailed', 'Save failed')); return null; }
			$('mktClaimNo').value = '';
			$('mktClaimPhone').value = '';
			return loadOrders();
		});
	}

	function logout() {
		call('POST', 'marketplace/account/logout', {}).then(function () {
			setMe(null);
			mode(false);
			open();
		});
	}

	// ── checkout: how to pay ───────────────────────────────────────────────────────────────────────────
	function card() { return !!me && $('mktPayCard').checked; }
	function syncPay() {
		var c = card();
		$('mktCardRow').hidden = !c;
		$('mktCardNote').hidden = !c;
		var cod = doc.querySelector('#mktCheckoutView .cod');
		if (cod) cod.hidden = c;
	}

	global.mktAccount = {
		open: open,
		paymentMode: function () { return card() ? 'CARD' : (me ? 'COD' : null); },
		cardToken: function () { return card() ? ($('mktCardToken').value || '').trim() : null; }
	};

	// ── wiring ─────────────────────────────────────────────────────────────────────────────────────────
	$('mktAccountBtn').addEventListener('click', function () {
		var s = P.readState();
		P.writeState({ account: 'orders', city: s.city }, true);
		P.render();
	});
	$('mktAccBack').addEventListener('click', function (e) {
		e.preventDefault();
		if (global.history.length > 1) global.history.back();
		else { P.writeState({}, false); P.render(); }
	});
	$('mktAccForm').addEventListener('submit', submit);
	$('mktAccCreate').addEventListener('click', function () { mode(!creating); });
	$('mktAccLogout').addEventListener('click', logout);
	$('mktClaimForm').addEventListener('submit', claim);
	$('mktPayCod').addEventListener('change', syncPay);
	$('mktPayCard').addEventListener('change', syncPay);
	mode(false);
	refreshMe();
	if (P.readState().account) P.render();                              // a reload on ?account=orders
})(window);
