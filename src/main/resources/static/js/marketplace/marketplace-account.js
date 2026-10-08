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
		DELIVERED: ['ok', 'ui.js.mktStDelivered', 'Delivered'],
		CANCELLED: ['bad', 'ui.js.mktCancelledState', 'Cancelled']
	};
	var PAY = {
		UNPAID: ['ui.js.mktPayCodShort', 'Cash on delivery'], CAPTURED: ['ui.js.mktPaidOnline', 'Paid online'],
		REFUNDED: ['ui.js.mktRefunded', 'Refunded'], FAILED: ['ui.js.mktPayFailed', 'Payment declined'],
		PARTIALLY_REFUNDED: ['ui.js.mktPartRefundedShort', 'Partly refunded']
	};
	/** MKT-2a — one seller's part of an order. */
	var PART = {
		UNASSIGNED: ['ui.js.mktPartWaiting', 'Waiting for confirmation'], OFFERED: ['ui.js.mktPartWaiting', 'Waiting for confirmation'],
		ACCEPTED: ['ui.js.mktStConfirmed', 'Confirmed'], HANDED_OVER: ['ui.js.mktStConfirmed', 'Confirmed'],
		REJECTED: ['ui.js.mktPartRejected', 'The seller could not fulfil this part'], EXPIRED: ['ui.js.mktPartExpired', 'Not confirmed in time'],
		CANCELLED: ['ui.js.mktCancelledState', 'Cancelled']
	};

	// ── MKT-1f: help — one conversation with MaxTheService per order (R8.2) ────────────────────────────────
	var casesByOrder = {};

	var TOPICS = [
		['ORDER_PROBLEM', 'ui.js.mktTopicProblem', 'Something is wrong with my order'],
		['RETURN', 'ui.js.mktTopicReturn', 'Return this item'],
		['WARRANTY', 'ui.js.mktTopicWarranty', 'Warranty claim'],
		['OTHER', 'ui.js.mktTopicOther', 'Something else']
	];
	// The causes a customer can name (source §13), in their words. The cost bearer is decided by the server.
	var REASONS = [
		['WRONG_PRODUCT', 'ui.js.mktRsnWrong', 'Wrong item sent'],
		['DAMAGED_BEFORE_HANDOVER', 'ui.js.mktRsnDamaged', 'Arrived damaged'],
		['DEFECTIVE', 'ui.js.mktRsnDefective', 'Does not work'],
		['NOT_AS_DESCRIBED', 'ui.js.mktRsnNotAsDescribed', 'Not as described'],
		['EXPIRED_OR_UNSAFE', 'ui.js.mktRsnUnsafe', 'Expired or unsafe'],
		['CHANGE_OF_MIND', 'ui.js.mktRsnChangedMind', 'Changed my mind']
	];
	var RET_ST = {
		REQUESTED: 'ui.js.mktRetRequested', APPROVED: 'ui.js.mktRetApproved', REJECTED: 'ui.js.mktRetRejected',
		RECEIVED: 'ui.js.mktRetReceived', REFUNDED: 'ui.js.mktRetRefunded'
	};
	var RET_FB = { REQUESTED: 'Return requested', APPROVED: 'Approved: the rider will collect it', REJECTED: 'Not approved',
		RECEIVED: 'Received: refund in progress', REFUNDED: 'Refunded' };

	function options(sel, list) {
		list.forEach(function (x) { var op = el('option', null, tr(x[1], x[2])); op.value = x[0]; sel.appendChild(op); });
	}
	function field(labelKey, labelFb, input) {
		var l = el('label', 'f');
		l.appendChild(el('span', null, tr(labelKey, labelFb)));
		l.appendChild(input);
		return l;
	}

	function helpForm(o, li, btn) {
		var open = li.querySelector('.help-form');
		if (open) { open.remove(); btn.setAttribute('aria-expanded', 'false'); return; }
		btn.setAttribute('aria-expanded', 'true');
		var f = el('form', 'help-form');
		f.noValidate = true;
		var topic = el('select'); topic.className = 'mkt-help-topic'; options(topic, TOPICS);
		f.appendChild(field('ui.js.mktHelpWhat', 'What do you need help with?', topic));
		var retBox = el('div', 'help-ret'); retBox.hidden = true;
		// MKT-2a: an order from several sellers asks which item, for every topic: each seller handles its own part
		var parts = o.sellerOrders || [], multi = parts.length > 1;
		var item = el('select'); item.className = 'mkt-help-item';
		var helpable = multi ? parts.filter(function (p) { return p.status === 'ACCEPTED' || p.status === 'HANDED_OVER'; })
			.reduce(function (all, p) { return all.concat((p.lines || []).map(function (l) { return { l: l, seller: p.sellerName }; })); }, [])
			: (o.lines || []).map(function (l) { return { l: l, seller: null }; });
		helpable.forEach(function (x) {
			var op = el('option', null, x.l.productName + ' × ' + x.l.quantity + (x.seller ? ' · ' + x.seller : ''));
			op.value = x.l.id; op.setAttribute('data-qty', x.l.quantity); item.appendChild(op);
		});
		var qty = el('input'); qty.type = 'number'; qty.min = '1'; qty.value = '1'; qty.className = 'mkt-help-qty';
		var reason = el('select'); reason.className = 'mkt-help-reason'; options(reason, REASONS);
		if (multi) f.appendChild(field('ui.js.mktHelpItem', 'Item', item));
		else retBox.appendChild(field('ui.js.mktHelpItem', 'Item', item));
		retBox.appendChild(field('ui.js.mktHelpQty', 'How many', qty));
		retBox.appendChild(field('ui.js.mktHelpReason', 'Why are you returning it?', reason));
		f.appendChild(retBox);
		var note = el('textarea'); note.className = 'mkt-help-note'; note.maxLength = 2000;
		f.appendChild(field('ui.js.mktHelpNote', 'Tell us what happened', note));
		var out = el('p', 'err'); out.setAttribute('role', 'alert');
		f.appendChild(out);
		var send = el('button', 'go mkt-help-send', tr('ui.js.mktHelpSend', 'Send to MaxTheService'));
		send.type = 'submit';
		f.appendChild(send);
		topic.addEventListener('change', function () { retBox.hidden = topic.value !== 'RETURN'; });
		f.addEventListener('submit', function (ev) {
			ev.preventDefault();
			out.textContent = '';
			send.disabled = true;
			var body = { topic: topic.value, note: note.value };
			if (topic.value === 'RETURN' || multi) body.lineId = Number(item.value);
			if (topic.value === 'RETURN') { body.quantity = Number(qty.value || 1); body.reason = reason.value; }
			call('POST', 'marketplace/account/orders/' + encodeURIComponent(o.orderNo) + '/cases', body).then(function (r) {
				send.disabled = false;
				if (!ok(r)) { out.textContent = msg(r, tr('ui.js.saveFailed', 'Save failed')); return null; }
				return loadOrders();
			});
		});
		li.appendChild(f);
		topic.focus();
	}

	/** The case under its order: messages (support's are signed MaxTheService), returns, and a reply box. On an order from
	 *  several sellers the title names the seller whose part it is about (MKT-2a); otherwise support stays the one voice. */
	function thread(c, multi) {
		var box = el('div', 'help-form mkt-case');
		box.setAttribute('data-case-no', c.caseNo);
		box.appendChild(el('b', null, tr('ui.js.mktCaseTitle', 'Help request') + ' ' + c.caseNo + (multi && c.sellerName ? ' · ' + c.sellerName : '')));
		(c.returns || []).forEach(function (r) {
			var k = RET_ST[r.status];
			box.appendChild(el('span', 'terms mkt-return', r.returnNo + ' · ' + (k ? tr(k, RET_FB[r.status]) : r.status)
				+ ' · ' + P.money(r.refundAmount) + (Number(r.deduction) > 0 ? ' (' + tr('ui.js.mktRetFee', 'pickup fee') + ' ' + P.money(r.deduction) + ')' : '')));
		});
		var ul = el('ul', 'thread');
		(c.messages || []).forEach(function (m) {
			var li = el('li', m.from === 'You' ? 'me' : null);
			li.appendChild(el('div', 'who', m.from === 'You' ? tr('ui.js.mktYou', 'You') : tr('ui.js.mktSupport', 'MaxTheService support')));
			li.appendChild(el('div', null, m.body));
			ul.appendChild(li);
		});
		box.appendChild(ul);
		if (c.status !== 'RESOLVED') {
			var ta = el('textarea'); ta.className = 'mkt-case-reply'; ta.maxLength = 2000;
			ta.setAttribute('aria-label', tr('ui.js.mktCaseReply', 'Write to MaxTheService support'));
			var b = el('button', 'mkt-help', tr('ui.js.mktCaseSend', 'Send'));
			b.type = 'button';
			var err = el('p', 'err'); err.setAttribute('role', 'alert');
			b.addEventListener('click', function () {
				b.disabled = true;
				call('POST', 'marketplace/account/cases/' + encodeURIComponent(c.caseNo) + '/messages', { body: ta.value }).then(function (r) {
					b.disabled = false;
					if (!ok(r)) { err.textContent = msg(r, tr('ui.js.saveFailed', 'Save failed')); return null; }
					return loadOrders();
				});
			});
			box.appendChild(ta); box.appendChild(b); box.appendChild(err);
		}
		return box;
	}

	// One load at a time: the page's first render and the ?account=orders reload both open My orders, and a second
	// redraw arriving after the shopper pressed "Get help" wiped the form they had just opened (recorded walk M-1f-04).
	var loading = null;

	function loadOrders() {
		if (loading) return loading;
		loading = drawOrders().then(function (x) { loading = null; return x; }, function (e) { loading = null; throw e; });
		return loading;
	}

	function drawOrders() {
		var ul = $('mktMyOrders');
		return call('GET', 'marketplace/account/cases').then(function (cr) {
			casesByOrder = {};
			(ok(cr) && Array.isArray(data(cr)) ? data(cr) : []).forEach(function (c) { (casesByOrder[c.orderNo] = casesByOrder[c.orderNo] || []).push(c); });
			return call('GET', 'marketplace/account/orders?size=50');
		}).then(function (r) {
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
		if (o.status === 'SUBMITTED' && (o.sellerOrders || []).length > 1) s = ['wait', 'ui.js.mktWaitingSellers', 'Waiting for the sellers to confirm'];   // MKT-2a
		if (o.status === 'SUBMITTED' && (o.sellerOrders || []).some(function (pt) { return pt.shortage && pt.shortage.result === 'SUBSTITUTION_REQUESTED' && !pt.shortage.decision; }))
			s = ['wait', 'ui.js.mktShYourAnswer', 'Waiting for your answer'];   // MKT-2b
		var top = el('div', 'row');
		top.appendChild(el('b', 'orderno', o.orderNo));
		top.appendChild(el('span', 'st ' + s[0], s[1] ? tr(s[1], s[2]) : s[2]));
		li.appendChild(top);
		var parts = o.sellerOrders || [];
		if (parts.length > 1 || parts.some(function (pt) { return !!pt.shortage; })) {
			// MKT-2a: each seller's part, with its own state; MKT-2b: a part moved, or offered to another seller
			parts.forEach(function (pt) {
				var row = el('span', 'terms mkt-acc-part');
				row.setAttribute('data-seller', pt.sellerName || '');
				var ps = PART[pt.status] || [null, pt.status];
				var sv = pt.shortage ? P.shortage(o, pt, null, function () { loadOrders(); }) : null;
				row.textContent = (pt.sellerName || '') + ': ' + (pt.lines || []).map(function (l) { return l.productName + ' × ' + l.quantity; }).join(', ')
					+ (sv && !pt.deliveredAt ? '' : ' · ' + (pt.deliveredAt ? tr('ui.js.mktPartDelivered', 'Delivered') : (ps[0] ? tr(ps[0], ps[1]) : ps[1])));
				if (sv) {
					row.appendChild(sv.node);
					if (sv.clock) sv.clock.paint(sv.clock.deadline === null ? null : Math.max(0, Math.round((sv.clock.deadline - Date.now()) / 1000)));
				}
				li.appendChild(row);
			});
		} else {
			(o.lines || []).forEach(function (l) { li.appendChild(el('span', 'terms', l.productName + ' × ' + l.quantity)); });
		}
		var mid = el('div', 'row');
		mid.appendChild(el('span', null, (parts.length > 1 ? '' : (o.sellerName || '')) + (o.city ? (parts.length > 1 ? '' : ' · ') + o.city : '')));
		var p = PAY[o.paymentStatus] || [null, o.paymentStatus];
		mid.appendChild(el('span', null, P.money(o.total) + ' · ' + (o.paymentMode === 'CARD' && o.paymentStatus === 'UNPAID'
			? tr('ui.js.mktPaidOnline', 'Paid online') : (p[0] ? tr(p[0], p[1]) : p[1]))));
		li.appendChild(mid);
		if (o.cancelReason) li.appendChild(el('span', 'terms', o.cancelReason));
		var acts = el('div', 'acts');
		if (o.canCancel) {
			var b = el('button', 'mkt-cancel', tr('ui.js.mktCancelBtn', 'Cancel order'));
			b.type = 'button';
			b.addEventListener('click', function () { cancel(o, b); });
			acts.appendChild(b);
		}
		if (o.canGetHelp) {
			var h = el('button', 'mkt-help', tr('ui.js.mktGetHelp', 'Get help'));
			h.type = 'button';
			h.setAttribute('aria-expanded', 'false');
			h.addEventListener('click', function () { helpForm(o, li, h); });
			acts.appendChild(h);
		}
		if (acts.childNodes.length) li.appendChild(acts);
		(casesByOrder[o.orderNo] || []).forEach(function (c) { li.appendChild(thread(c, parts.length > 1)); });   // MKT-2a: one per seller
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
