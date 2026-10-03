/**
 * MKT-1d — the public marketplace page (/marketplace). Contract: microservices/docs/slices/mkt-1d-public-catalogue.md
 *
 * Two views on one page, driven by the URL so every state can be shared, bookmarked and reached with Back:
 *   ?q=&city=            search results: one card per product, "Available from N sellers · From Rs. …"
 *   ?product=&city=&sort= one product's offers, sorted as the customer chose; nothing pre-selected
 *
 * No jQuery: an anonymous shopper's first page should be light. DOM is built with textContent only, never
 * innerHTML with data, so a seller's display name can never inject markup. Superseded requests are aborted, so a
 * slow answer can never overwrite a newer one.
 */
(function (global, doc) {
	'use strict';

	var CTX = doc.body.getAttribute('data-ctx') || '/';
	var CITY_KEY = 'mkt.city';
	var DEBOUNCE_MS = 300;

	function tr(key, fallback) {
		var args = Array.prototype.slice.call(arguments, 2);
		var has = typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key);
		var s = has ? global.t(key) : fallback;
		return s.replace(/\{(\d+)\}/g, function (m, i) { return args[i] === undefined ? m : String(args[i]); });
	}
	function ok(res) { return typeof global.apiOk === 'function' ? global.apiOk(res) : !!(res && res.success === true); }
	function data(res) { return typeof global.apiData === 'function' ? global.apiData(res) : (res && res.data); }
	function message(res, fb) { return typeof global.apiMessage === 'function' ? global.apiMessage(res, fb) : ((res && res.message) || fb); }
	function $(id) { return doc.getElementById(id); }
	function el(tag, cls, text) {
		var n = doc.createElement(tag);
		if (cls) n.className = cls;
		if (text !== undefined && text !== null) n.textContent = text;
		return n;
	}

	// ── formatting ─────────────────────────────────────────────────────────────────────────────────────
	var NUM = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
	function money(v) { return tr('ui.js.mktMoney', 'Rs. {0}', NUM.format(Number(v || 0))); }
	function delivery(hours) {
		if (hours === null || hours === undefined) return '';
		if (hours < 24) return hours === 1 ? tr('ui.js.mktDeliveryHour', 'Delivery in 1 hour')
			: tr('ui.js.mktDeliveryHours', 'Delivery in {0} hours', hours);
		var d = Math.ceil(hours / 24);
		return d === 1 ? tr('ui.js.mktDeliveryDay', 'Delivery in 1 day') : tr('ui.js.mktDeliveryDays', 'Delivery in {0} days', d);
	}
	function checked(seconds) {
		if (seconds === null || seconds === undefined) return '';
		if (seconds < 60) return tr('ui.js.mktCheckedNow', 'Stock checked just now');
		return tr('ui.js.mktCheckedMin', 'Stock checked {0} min ago', Math.floor(seconds / 60));
	}

	// ── state: the URL is the source of truth ──────────────────────────────────────────────────────────
	function readState() {
		var p = new URLSearchParams(global.location.search);
		return { q: p.get('q') || '', city: p.get('city') || storedCity(), product: p.get('product') || '', sort: p.get('sort') || '',
			checkout: p.get('checkout') || '', order: p.get('order') || '', phone: p.get('phone') || '' };
	}
	function writeState(s, push, marker) {
		var p = new URLSearchParams();
		if (s.order) {
			p.set('order', s.order);              // never the phone: it lives in sessionStorage, not in history or logs
		} else if (s.product) {
			p.set('product', s.product);
			if (s.sort) p.set('sort', s.sort);
			if (s.checkout) p.set('checkout', s.checkout);
		} else if (s.q) {
			p.set('q', s.q);
		}
		if (s.city && !s.order) p.set('city', s.city);
		var lang = new URLSearchParams(global.location.search).get('lang');
		if (lang) p.set('lang', lang);
		var url = global.location.pathname + (p.toString() ? '?' + p.toString() : '');
		if (push) global.history.pushState(marker || null, '', url); else global.history.replaceState(global.history.state, '', url);
		syncLangLinks();
	}
	function storedCity() { try { return global.localStorage.getItem(CITY_KEY) || ''; } catch (e) { return ''; } }
	function storeCity(c) { try { if (c) global.localStorage.setItem(CITY_KEY, c); } catch (e) { /* private window */ } }

	/** Language links keep the page the shopper is on (?lang= is handled server-side). */
	function syncLangLinks() {
		Array.prototype.forEach.call(doc.querySelectorAll('.mkt-lang'), function (a) {
			var p = new URLSearchParams(global.location.search);
			p.set('lang', a.getAttribute('data-lang'));
			a.setAttribute('href', global.location.pathname + '?' + p.toString());
		});
	}

	// ── fetch with cancellation ────────────────────────────────────────────────────────────────────────
	var inflight = {};
	function getJson(channel, path, params) {
		if (inflight[channel]) inflight[channel].abort();
		var ctl = typeof AbortController === 'function' ? new AbortController() : null;
		inflight[channel] = ctl;
		var qs = new URLSearchParams();
		Object.keys(params || {}).forEach(function (k) {
			if (params[k] !== '' && params[k] !== null && params[k] !== undefined) qs.set(k, params[k]);
		});
		return fetch(CTX + path + (qs.toString() ? '?' + qs.toString() : ''), {
			headers: { Accept: 'application/json' }, signal: ctl ? ctl.signal : undefined, credentials: 'same-origin'
		}).then(function (r) {
			// a server fault is never shown as its internals ("InternalError"): the shopper gets a sentence
			if (r.status >= 500) return { success: false, message: tr('ui.js.loadFailed', 'Could not load.') };
			return r.json().catch(function () { return { success: false }; });
		});
	}
	function aborted(e) { return e && e.name === 'AbortError'; }

	// ── search view ────────────────────────────────────────────────────────────────────────────────────
	var page = 0;

	function search(state, append) {
		if (!append) page = 0;
		var status = $('mktResultCount');
		status.classList.remove('err');
		if (!append) status.textContent = tr('ui.js.mktSearching', 'Searching…');
		return getJson('search', 'marketplace/public/products', { q: state.q, city: state.city, page: page, size: 24 })
			.then(function (res) {
				var list = $('mktResults');
				if (!append) list.textContent = '';
				if (!ok(res)) {
					status.classList.add('err');
					status.textContent = message(res, tr('ui.js.loadFailed', 'Could not load.'));
					$('mktMore').hidden = true;
					return;
				}
				var pageData = data(res) || {};
				(pageData.content || []).forEach(function (c) { list.appendChild(card(c, state.city)); });
				var shown = list.children.length;
				status.textContent = shown === 0
					? (state.city ? tr('ui.js.mktNoResultsCity', 'No products match in {0}. Try fewer words or another city.', state.city)
						: tr('ui.js.mktNoResults', 'No products match. Try fewer words.'))
					: (shown === 1 ? tr('ui.js.mktOneProduct', '1 product') : tr('ui.js.mktProducts', '{0} products', shown));
				$('mktMore').hidden = pageData.last !== false;
			})
			.catch(function (e) {
				if (aborted(e)) return;
				status.classList.add('err');
				status.textContent = tr('ui.js.mktOffline', 'The marketplace is not reachable. Check your connection and try again.');
			});
	}

	function card(c, city) {
		var li = el('li', 'mkt-product-card');
		li.setAttribute('data-product-id', c.id);
		var a = el('a');
		var p = new URLSearchParams();
		p.set('product', c.id);
		if (city) p.set('city', city);
		a.href = global.location.pathname + '?' + p.toString();
		a.addEventListener('click', function (ev) {
			if (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.button === 1) return;   // new tab still works
			ev.preventDefault();
			var s = readState();
			s.product = String(c.id);
			s.sort = '';
			writeState(s, true, { fromResults: true });   // Back on the product page can then truly go back
			render();
		});
		if (c.brand) a.appendChild(el('span', 'card-brand', c.brand));
		a.appendChild(el('span', 'card-name', c.name));
		a.appendChild(el('span', 'mkt-from-price', tr('ui.js.mktFromPrice', 'From {0}', money(c.fromPrice))));
		a.appendChild(el('span', 'mkt-offer-count', c.offerCount === 1
			? tr('ui.js.mktFromSellerOne', 'Available from 1 seller')
			: tr('ui.js.mktFromSellers', 'Available from {0} sellers', c.offerCount)));
		if (c.fastestPromiseHours !== null && c.fastestPromiseHours !== undefined) {
			a.appendChild(el('span', 'card-fast', delivery(c.fastestPromiseHours)));
		}
		li.appendChild(a);
		return li;
	}

	// ── product view ───────────────────────────────────────────────────────────────────────────────────
	var product = null;
	var chosen = null;

	function openProduct(state) {
		chosen = null;
		syncBuy();
		$('mktOffers').textContent = '';
		$('mktProductName').textContent = '';
		$('mktProductAttrs').textContent = '';
		// never under the shopper's fingers: a page still loading wrote the old city back into a box being typed in,
		// turning "Lahore" into "KarachiLahore" (found by the recorded manual walk, M-1d-04)
		if (doc.activeElement !== $('mktOfferCity')) $('mktOfferCity').value = state.city || '';
		var status = $('mktOfferStatus');
		status.classList.remove('err');
		status.textContent = tr('ui.js.mktLoadingOffers', 'Loading sellers…');
		return getJson('product', 'marketplace/public/products/' + encodeURIComponent(state.product), {})
			.then(function (res) {
				if (!ok(res)) {
					product = null;
					status.classList.add('err');
					status.textContent = message(res, tr('ui.js.loadFailed', 'Could not load.'));
					$('mktOfferSet').hidden = true;
					return;
				}
				product = data(res);
				$('mktOfferSet').hidden = false;
				$('mktProductName').textContent = product.name;
				$('mktProductAttrs').textContent = [product.brand, product.variant, product.colour, product.size,
					product.packSize, product.condition].filter(Boolean).join(' · ');
				doc.title = product.name + ' · ' + tr('ui.js.mktTitle', 'MaxTheService Marketplace');
				var sorts = product.sorts || [];
				var sort = sorts.indexOf(String(state.sort).toUpperCase()) >= 0 ? String(state.sort).toUpperCase() : product.defaultSort;
				Array.prototype.forEach.call($('mktOfferSort').options, function (o) { o.hidden = sorts.indexOf(o.value) < 0; });
				$('mktOfferSort').value = sort;
				$('mktProductName').focus();
				return loadOffers(state.city, sort);
			})
			.catch(function (e) {
				if (aborted(e)) return;
				status.classList.add('err');
				status.textContent = tr('ui.js.mktOffline', 'The marketplace is not reachable. Check your connection and try again.');
			});
	}

	function loadOffers(city, sort) {
		var status = $('mktOfferStatus');
		return getJson('offers', 'marketplace/public/products/' + encodeURIComponent(product.id) + '/offers', { city: city, sort: sort })
			.then(function (res) {
				var list = $('mktOffers');
				list.textContent = '';
				chosen = null;
				syncBuy();
				if (!ok(res)) {
					status.classList.add('err');
					status.textContent = message(res, tr('ui.js.loadFailed', 'Could not load.'));
					return;
				}
				status.classList.remove('err');
				var offers = (typeof global.apiList === 'function' ? global.apiList(res) : data(res)) || [];
				offers.forEach(function (o) { list.appendChild(offerRow(o)); });
				status.textContent = offers.length === 0
					? (city ? tr('ui.js.mktNoSellerCity', 'No seller delivers this product to {0} yet.', city)
						: tr('ui.js.mktNoSeller', 'No seller has this product available right now.'))
					: (offers.length === 1 ? tr('ui.js.mktFromSellerOne', 'Available from 1 seller')
						: tr('ui.js.mktFromSellers', 'Available from {0} sellers', offers.length));
			})
			.catch(function (e) {
				if (aborted(e)) return;
				status.classList.add('err');
				status.textContent = tr('ui.js.mktOffline', 'The marketplace is not reachable. Check your connection and try again.');
			});
	}

	function offerRow(o) {
		var li = el('li', 'mkt-offer-row');
		li.setAttribute('data-offer-id', o.offerId);
		li.setAttribute('data-seller', o.sellerName || '');
		var label = el('label');
		var radio = el('input', 'mkt-choose-offer');
		radio.type = 'radio';
		radio.name = 'mktOffer';
		radio.value = o.offerId;
		radio.addEventListener('change', function () { chosen = o; syncBuy(); });
		label.appendChild(radio);
		label.appendChild(el('span', 'o-seller', o.sellerName));
		label.appendChild(el('span', 'o-price', money(o.price)));
		var facts = el('span', 'o-facts');
		facts.appendChild(el('span', null, delivery(o.promiseHours)));
		var w = el('span');
		if (o.warrantyMonths) {
			w.appendChild(el('b', null, tr('ui.js.mktWarrantyMonths', '{0} months warranty', o.warrantyMonths)));
			if (o.warrantyProvider) w.appendChild(doc.createTextNode(' · ' + o.warrantyProvider));
		} else {
			w.textContent = tr('ui.js.mktNoWarranty', 'No warranty');
		}
		facts.appendChild(w);
		facts.appendChild(el('span', null, o.returnDays
			? tr('ui.js.mktReturnsDays', 'Returns within {0} days', o.returnDays) : tr('ui.js.mktNoReturns', 'No returns')));
		facts.appendChild(el('span', null, o.rating ? tr('ui.js.mktRating', 'Rated {0} / 5', o.rating)
			: tr('ui.js.mktNoRating', 'No ratings yet')));
		facts.appendChild(el('span', 'mkt-stock-checked', checked(o.checkedSecondsAgo)));
		label.appendChild(facts);
		li.appendChild(label);
		return li;
	}

	/** The cheapest is never chosen for the customer (§7.3): disabled until a row is chosen, then names the seller. */
	function syncBuy() {
		var b = $('mktBuyBtn');
		if (chosen) {
			b.disabled = false;
			b.textContent = tr('ui.js.mktBuyFrom', 'Buy from {0}', chosen.sellerName);
		} else {
			b.disabled = true;
			b.textContent = tr('ui.js.mktChooseFirst', 'Choose a seller first');
		}
	}

	// ── MKT-1e: checkout (cash on delivery) ────────────────────────────────────────────────────────────

	var coOffer = null;

	function csrfHeaders() {
		var token = doc.querySelector('meta[name="_csrf"]'), header = doc.querySelector('meta[name="_csrf_header"]');
		var h = { 'Content-Type': 'application/json', Accept: 'application/json' };
		if (token && token.content) h[(header && header.content) || 'X-XSRF-TOKEN'] = token.content;
		return h;
	}

	function session(op, key, value) {
		try {
			if (op === 'get') return global.sessionStorage.getItem(key);
			if (op === 'set') global.sessionStorage.setItem(key, value);
			if (op === 'del') global.sessionStorage.removeItem(key);
		} catch (e) { /* private window: the flow still works, a refresh just starts a new attempt */ }
		return null;
	}
	function rememberPhone(orderNo, phone) { session('set', 'mkt.ph.' + orderNo, phone); }

	/**
	 * One idempotency key per checkout attempt, kept across a refresh or a retry after a timeout, so "Place order"
	 * pressed twice — or pressed again after a network error that hid a success — is still ONE order.
	 */
	function attemptKey(offerId) {
		var k = session('get', 'mkt.co.' + offerId);
		if (!k) {
			k = (global.crypto && global.crypto.randomUUID) ? global.crypto.randomUUID()
				: 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
			session('set', 'mkt.co.' + offerId, k);
		}
		return k;
	}

	function openCheckout(s) {
		coOffer = null;
		$('mktCoError').textContent = '';
		$('mktCoPlace').disabled = true;
		if (!s.city) {
			$('mktCoError').textContent = tr('ui.js.mktCityFirst', 'Choose the delivery city first.');
			return null;
		}
		$('mktCoCity').value = s.city;
		return Promise.all([
			getJson('product', 'marketplace/public/products/' + encodeURIComponent(s.product), {}),
			getJson('offers', 'marketplace/public/products/' + encodeURIComponent(s.product) + '/offers', { city: s.city })
		]).then(function (both) {
			var p = data(both[0]) || {};
			var offers = (typeof global.apiList === 'function' ? global.apiList(both[1]) : data(both[1])) || [];
			coOffer = offers.filter(function (o) { return String(o.offerId) === String(s.checkout); })[0] || null;
			if (!coOffer) {
				$('mktCoError').textContent = tr('ui.js.mktOfferGone', 'This offer is no longer available. Please choose another offer.');
				return;
			}
			$('mktCoProduct').textContent = p.name || '';
			$('mktCoUnit').textContent = money(coOffer.price);
			$('mktCoSeller').textContent = coOffer.sellerName;
			var max = Math.max(1, Math.min(10, Math.floor(Number(coOffer.availableQty || 1))));
			var q = $('mktCoQty');
			q.textContent = '';
			for (var i = 1; i <= max; i++) { var opt = el('option', null, String(i)); opt.value = i; q.appendChild(opt); }
			coTotal();
			$('mktCoPlace').disabled = false;
			$('mktCoTitle').focus();
		}).catch(function (e) {
			if (aborted(e)) return;
			$('mktCoError').textContent = tr('ui.js.mktOffline', 'The marketplace is not reachable. Check your connection and try again.');
		});
	}

	function coTotal() {
		if (!coOffer) return;
		$('mktCoTotal').textContent = money(Number(coOffer.price) * Number($('mktCoQty').value || 1));
	}

	/** Money is never optimistic: the button says what is happening, the result says what the SERVER decided. */
	function placeOrder(ev) {
		ev.preventDefault();
		if (!coOffer) return;
		var name = $('mktCoName').value.trim(), phone = $('mktCoPhone').value.trim(), address = $('mktCoAddress').value.trim();
		if (!name || !phone || !address) {
			$('mktCoError').textContent = tr('ui.js.mktCoFixFields', 'Fill in your name, phone and address.');
			return;
		}
		var b = $('mktCoPlace'), label = b.textContent, offerId = coOffer.offerId;
		b.disabled = true;
		b.textContent = tr('ui.js.mktPlacing', 'Placing your order…');
		$('mktCoError').textContent = '';
		fetch(CTX + 'marketplace/public/checkout', {
			// manual: a lapsed security token makes Spring REDIRECT an anonymous shopper to /login. Followed, that read
			// as an unreadable answer; the "page expired" sentence below was never shown (recorded manual walk, M-1e-11)
			method: 'POST', credentials: 'same-origin', headers: csrfHeaders(), redirect: 'manual',
			body: JSON.stringify({ offerId: offerId, quantity: Number($('mktCoQty').value || 1), expectedPrice: coOffer.price,
				customerName: name, customerPhone: phone, address: address, city: $('mktCoCity').value,
				idempotencyKey: attemptKey(offerId) })
		}).then(function (r) {
			if (r.status === 403 || r.type === 'opaqueredirect') {   // refused before it reached the order: certain, not unknown
				return { success: false, message: tr('ui.js.mktSessionExpired', 'This page expired. Please reload it and try again.') };
			}
			// A 5xx, an unreadable body or the proxy's UNKNOWN is NOT a "no": the order may exist. Keep the key (catch).
			if (r.status >= 500) throw new Error('unknown outcome');
			return r.json().catch(function () { throw new Error('unreadable answer'); });
		}).then(function (res) {
			if (res && res.outcome === 'UNKNOWN') throw new Error('unknown outcome');
			if (!ok(res)) {
				session('del', 'mkt.co.' + offerId);           // the server answered "no": a retry is a new attempt
				$('mktCoError').textContent = message(res, tr('ui.js.saveFailed', 'Save failed'));
				return;
			}
			var o = data(res);
			session('del', 'mkt.co.' + offerId);
			rememberPhone(o.orderNo, phone);
			global.history.pushState(null, '', global.location.pathname + '?order=' + encodeURIComponent(o.orderNo));
			syncLangLinks();
			render();
		}).catch(function () {
			// unknown outcome: keep the key, so pressing again returns the order if it was in fact placed
			$('mktCoError').textContent = tr('ui.js.mktCoRetry', 'We could not confirm your order. Press the button again; it will not be placed twice.');
		}).then(function () { b.disabled = false; b.textContent = label; });
	}

	// ── MKT-1e: one order ──────────────────────────────────────────────────────────────────────────────

	var pollTimer = null, countdownTimer = null;
	function stopPolling() { clearTimeout(pollTimer); clearInterval(countdownTimer); }

	function openOrder(orderNo) {
		stopPolling();
		var phone = session('get', 'mkt.ph.' + orderNo);
		$('mktOrderError').textContent = '';
		$('mktTrackForm').hidden = !!phone;
		if (!phone) {
			$('mktCheckoutStatus').textContent = tr('ui.js.mktTrackAsk', 'Enter the phone number you ordered with to see this order.');
			$('mktCheckoutStatus').className = 'state';
			return null;
		}
		return getJson('order', 'marketplace/public/orders/' + encodeURIComponent(orderNo), { phone: phone }).then(function (res) {
			if (!ok(res)) {
				$('mktTrackForm').hidden = false;
				$('mktOrderError').textContent = message(res, tr('ui.js.loadFailed', 'Could not load.'));
				return;
			}
			showOrder(data(res));
		}).catch(function (e) {
			if (aborted(e)) return;
			$('mktOrderError').textContent = tr('ui.js.mktOffline', 'The marketplace is not reachable. Check your connection and try again.');
			pollTimer = setTimeout(function () { openOrder(orderNo); }, 10000);
		});
	}

	function showOrder(o) {
		var st = $('mktCheckoutStatus'), detail = $('mktOrderDetail'), seller = o.sellerName || tr('ui.js.mktTheSeller', 'the seller');
		$('mktCoOrderNo').textContent = o.orderNo;
		$('mktOrderTotal').textContent = money(o.total);
		var lines = $('mktOrderLines');
		lines.textContent = '';
		(o.lines || []).forEach(function (l) {
			lines.appendChild(el('span', null, l.productName + ' × ' + l.quantity + ' · ' + money(l.lineTotal)));
			var terms = [];
			if (l.warrantyMonths) terms.push(tr('ui.js.mktWarrantyMonths', '{0} months warranty', l.warrantyMonths)
				+ (l.warrantyProvider ? ' · ' + l.warrantyProvider : ''));
			terms.push(l.returnDays ? tr('ui.js.mktReturnsDays', 'Returns within {0} days', l.returnDays) : tr('ui.js.mktNoReturns', 'No returns'));
			lines.appendChild(el('span', null, terms.join(' · ')));
		});
		clearInterval(countdownTimer);
		if (o.status === 'CONFIRMED') {
			st.className = 'state ok';
			st.textContent = tr('ui.js.mktConfirmedBy', 'Confirmed by {0}', seller);
			detail.textContent = tr('ui.js.mktConfirmedCod', '{0} will deliver and collect {1} in cash.', seller, money(o.total));
		} else if (o.status === 'CANCELLED') {
			st.className = 'state bad';
			st.textContent = tr('ui.js.mktCancelledState', 'Cancelled');
			detail.textContent = o.cancelReason || '';
		} else {
			st.className = 'state wait';
			st.textContent = tr('ui.js.mktWaitingFor', 'Waiting for {0} to confirm', seller);
			var deadline = o.secondsToAccept === null || o.secondsToAccept === undefined ? null : Date.now() + o.secondsToAccept * 1000;
			var paint = function () {
				var left = deadline === null ? null : Math.max(0, Math.round((deadline - Date.now()) / 1000));
				detail.textContent = left === null ? tr('ui.js.mktStockHeld', 'Your stock is held while the seller answers.')
					: tr('ui.js.mktHasTime', '{0} has {1} to confirm. Your stock is held.', seller,
						Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2));
			};
			paint();
			countdownTimer = setInterval(paint, 1000);
			pollTimer = setTimeout(function () { openOrder(o.orderNo); }, 10000);   // the seller's answer, when it comes
		}
	}

	// ── render from the URL ────────────────────────────────────────────────────────────────────────────
	function render() {
		var s = readState();
		stopPolling();
		var view = s.order ? 'order' : (s.product && s.checkout ? 'checkout' : (s.product ? 'product' : 'search'));
		$('mktSearchView').hidden = view !== 'search';
		$('mktProductView').hidden = view !== 'product';
		$('mktCheckoutView').hidden = view !== 'checkout';
		$('mktOrderView').hidden = view !== 'order';
		$('mktBuyBar').hidden = view !== 'product';
		$('mktSearch').value = s.q;
		$('mktCity').value = s.city;
		if (view === 'order') {
			if (s.phone) {                                     // a shared link may carry it once: keep it, drop it from the URL
				rememberPhone(s.order, s.phone);
				writeState(s, false);
			}
			return openOrder(s.order);
		}
		if (view === 'checkout') return openCheckout(s);
		if (view === 'product') return openProduct(s);
		doc.title = tr('ui.js.mktTitle', 'MaxTheService Marketplace');
		return search(s, false);
	}

	var timer = null;
	function debounced(fn) {
		return function () { clearTimeout(timer); timer = setTimeout(fn, DEBOUNCE_MS); };
	}

	function submitSearch(ev) {
		if (ev) ev.preventDefault();
		clearTimeout(timer);
		var s = { q: $('mktSearch').value.trim(), city: $('mktCity').value.trim(), product: '', sort: '' };
		storeCity(s.city);
		writeState(s, !!ev);
		search(s, false);
	}

	function init() {
		$('mktSearchForm').addEventListener('submit', submitSearch);
		$('mktSearch').addEventListener('input', debounced(function () { submitSearch(null); }));
		$('mktCity').addEventListener('input', debounced(function () { submitSearch(null); }));
		$('mktMore').addEventListener('click', function () { page += 1; search(readState(), true); });
		$('mktBack').addEventListener('click', function (ev) {
			ev.preventDefault();
			if (global.history.state && global.history.state.fromResults) { global.history.back(); return; }
			var s = readState();
			s.product = '';
			s.sort = '';
			writeState(s, true);
			render();
		});
		$('mktOfferSort').addEventListener('change', function () {
			var s = readState();
			s.sort = this.value;
			writeState(s, false);
			loadOffers(s.city, s.sort);
		});
		$('mktOfferCity').addEventListener('input', debounced(function () {
			var s = readState();
			s.city = $('mktOfferCity').value.trim();
			storeCity(s.city);
			writeState(s, false);
			loadOffers(s.city, $('mktOfferSort').value);
		}));
		$('mktBuyBtn').addEventListener('click', function () {
			if (!chosen) return;
			var s = readState();
			s.checkout = String(chosen.offerId);
			writeState(s, true);
			render();
		});
		$('mktCoBack').addEventListener('click', function (ev) {
			ev.preventDefault();
			var s = readState();
			s.checkout = '';
			writeState(s, true);
			render();
		});
		$('mktCoQty').addEventListener('change', coTotal);
		$('mktCoForm').addEventListener('submit', placeOrder);
		$('mktTrackForm').addEventListener('submit', function (ev) {
			ev.preventDefault();
			var no = readState().order, ph = $('mktTrackPhone').value.trim();
			if (!ph) return;
			rememberPhone(no, ph);
			openOrder(no);
		});
		global.addEventListener('popstate', render);
		syncLangLinks();
		render();
	}

	if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', init); else init();
})(window, document);
