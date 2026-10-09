/**
 * EX-1 — Expenses screen. ONE file for every dashboard that offers the module (DRY rule: common → /js/common).
 * Markup: templates/fragments/expense.html. Server: expense-service via the monolith's /expense/** proxy.
 *
 * Standards this file is built to (microservices/docs/slices/ex-1-direct-expense-voucher.md):
 *   - §0b NEVER OPTIMISTIC ABOUT MONEY. A saved expense shows "Posting…" until the LEDGER has answered, then
 *     "In the books" or "Not posted" with the reason. Nothing here claims the books on the client's word.
 *   - §0c BLOCK THE RISKY ACTION, NOT THE UI. Only the pressed Save button is disabled and relabelled; the
 *     backend carries the real guard (Idempotency-Key + UNIQUE index), so a double click or a retry after a
 *     dropped connection replays the first save instead of making a second expense.
 *   - Every server string is rendered with escHtml (XSS-safe rendering); dates travel as ISO, display as dd-MM-yyyy.
 */
(function (global) {
	'use strict';

	function tr(key, fallback) {
		return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
			? global.t(key) : fallback;
	}
	function esc(v) { return typeof global.escHtml === 'function' ? global.escHtml(v) : String(v == null ? '' : v); }
	function ctx() { return typeof global.serverContext === 'string' ? global.serverContext : '/'; }

	function pad(n) { return (n < 10 ? '0' : '') + n; }
	function isoOf(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
	function showDate(iso) {
		if (!iso) return '';
		var p = String(iso).split('-');
		return p.length === 3 ? p[2] + '-' + p[1] + '-' + p[0] : esc(iso);
	}
	function money(v) {
		var n = Number(v || 0);
		return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
	}
	function newKey() {
		return 'exp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
	}
	/** Sets both the hidden ISO field and its visible dd-MM-yyyy twin (the date-picker wire contract). */
	function setDate(isoSel, iso) {
		$(isoSel).val(iso);
		$(isoSel + 'Temp').val(showDate(iso));
	}

	/** The status a person reads. Posting state decides it until the voucher is voided. */
	function chip(v) {
		// EX-6 — a claim is not in the books until an owner or admin approves it; until then its own state is shown
		if (v.claimStatus === 'SUBMITTED') return '<span class="label label-warning" data-cy="claim-waiting">' + esc(tr('ui.js.claimWaiting', 'Waiting for approval')) + '</span>';
		if (v.claimStatus === 'REJECTED') {
			return '<span class="label label-danger" data-cy="claim-rejected">' + esc(tr('ui.js.claimRejected', 'Rejected')) + '</span>'
				+ '<div class="text-danger" data-cy="claim-reason" style="font-size:12px;margin-top:3px;white-space:normal">' + esc(v.decisionNote || '') + '</div>';
		}
		if (v.claimStatus === 'WITHDRAWN') return '<span class="label label-default" data-cy="claim-withdrawn">' + esc(tr('ui.js.claimWithdrawn', 'Withdrawn')) + '</span>';
		if (v.status === 'VOIDED') {
			return '<span class="label label-default" title="' + esc(v.voidReason || '') + '">' + esc(tr('ui.js.expVoided', 'Void')) + '</span>';
		}
		if (v.postingStatus === 'POSTED_GL') return '<span class="label label-success">' + esc(tr('ui.js.expInBooks', 'In the books')) + '</span>';
		if (v.postingStatus === 'FAILED') {
			return '<span class="label label-danger" title="' + esc(v.postingError || '') + '">' + esc(tr('ui.js.expFailed', 'Not posted')) + '</span>';
		}
		return '<span class="label label-warning" title="' + esc(v.postingError || '') + '">' + esc(tr('ui.js.expPosting', 'Posting…')) + '</span>';
	}

	/**
	 * EX-1b — what the books refused, in their own words, and the way out. Shown on the row (a tooltip hid it): a
	 * refused posting, or a void whose reversal has not reached the books. Post again re-sends the same posting — after
	 * the cause is fixed (a period reopened) it lands, and it can never be booked twice.
	 */
	function refusal(v) {
		// A void whose REVERSAL has not landed is POSTED_GL with an error. A voucher voided after its posting FAILED is
		// FAILED: it never reached the books, nothing is owed to them, and the server would refuse to send it again.
		var refused = (v.status === 'POSTED' && v.postingStatus === 'FAILED')
			|| (v.status === 'VOIDED' && v.postingStatus === 'POSTED_GL' && v.postingError);
		if (!refused) return '';
		return '<div class="text-danger" data-cy="expense-posting-error" style="font-size:12px;margin-top:3px;white-space:normal">'
			+ esc(v.postingError || tr('ui.js.expNotPostedWhy', 'The books did not take it.')) + '</div>'
			+ '<button type="button" class="btn btn-xs btn-warning" data-cy="post-again" data-id="' + esc(v.id) + '" style="margin-top:3px">'
			+ esc(tr('ui.js.expPostAgain', 'Post again')) + '</button>'
			+ (v.status === 'POSTED'
				? '<div class="text-muted" style="font-size:11.5px;white-space:normal">' + esc(tr('ui.js.expPostAgainHint',
					'Fix the cause first — reopen the period in Finance → Period Close — or void it and record it again in an open period.')) + '</div>'
				: '');
	}

	var canVoid = false;

	/**
	 * FP-3 — what a BILL still owes, and its Pay button. Pay is offered only once the bill is IN THE BOOKS: paying a
	 * bill whose posting failed would take money out against a debt the ledger never recorded (the server refuses it).
	 */
	function billState(v) {
		// EX-7a — an approved claim is owed too: to the member who paid it, and paid back (owner/admin) from here
		var isClaim = v.paidFrom === 'EMPLOYEE' && v.claimStatus === 'APPROVED';
		if ((v.paidFrom !== 'AP' && !isClaim) || v.status !== 'POSTED') return '';
		var open = Number(v.openAmount || 0);
		// FP-3b — a bill with payments lists them (owner/admin), each reversible from there
		var paysBtn = (canVoid && Number(v.paidAmount || 0) > 0)
			? ' <button type="button" class="btn btn-xs btn-default" data-cy="bill-payments" data-id="' + esc(v.id) + '" aria-expanded="false">'
				+ esc(tr('ui.js.expPayments', 'Payments')) + '</button>' : '';
		if (open <= 0) return ' <span class="label label-info" data-cy="expense-bill-paid">'
			+ esc(isClaim ? tr('ui.js.claimPaidBack', 'Paid back') : tr('ui.js.expPaid', 'Paid')) + '</span>' + paysBtn;
		var out = ' <span class="text-warning" data-cy="expense-bill-owes" style="font-variant-numeric:tabular-nums">'
			+ esc(isClaim ? tr('ui.js.claimOwed', 'Owed to the member') : tr('ui.js.expOwes', 'Owes')) + ' ' + esc(money(open)) + '</span>';
		if (isClaim && v.postingStatus === 'POSTED_GL' && canVoid) {
			// owner/admin only; the server also refuses paying your own claim back
			out += ' <button type="button" class="btn btn-xs btn-primary" data-cy="pay-claim" data-id="' + esc(v.id)
				+ '" data-user="' + esc(v.userId == null ? '' : v.userId)
				+ '" data-no="' + esc(v.voucherNo || '') + '" data-open="' + esc(open) + '">' + esc(tr('ui.js.claimPayBack', 'Pay back')) + '</button>';
		} else if (!isClaim && v.postingStatus === 'POSTED_GL') {
			out += ' <button type="button" class="btn btn-xs btn-primary" data-cy="pay-bill" data-id="' + esc(v.id)
				+ '" data-no="' + esc(v.voucherNo || '') + '" data-open="' + esc(open) + '">' + esc(tr('ui.pay', 'Pay')) + '</button>';
		}
		return out + paysBtn;
	}   // set from the table header: the Actions column is rendered only for owner/admin

	/** EX-5 — "Receipts (n)", or "Add receipt" on an expense that still takes one. */
	/** A finished document takes no new receipts: voided, or (EX-6) a claim that was rejected or withdrawn. */
	function closedForReceipts(v) {
		return v.status === 'VOIDED' || v.claimStatus === 'REJECTED' || v.claimStatus === 'WITHDRAWN';
	}
	function receiptsBtn(v) {
		var n = Number(v.receipts || 0);
		if (!n && closedForReceipts(v)) return '';
		return ' <button type="button" class="btn btn-xs btn-default" data-cy="expense-receipts" data-id="' + esc(v.id) + '">'
			+ '<span class="glyphicon glyphicon-paperclip"></span> '
			+ esc(n ? tr('ui.js.expReceipts', 'Receipts') + ' (' + n + ')' : tr('ui.js.expReceiptAdd', 'Add a receipt')) + '</button>';
	}

	/**
	 * EX-6 — a waiting claim: the claimant (or an owner/admin) may withdraw it; an owner or admin approves or rejects
	 * it. The server is the guard — nobody decides their own claim, and it says so in words.
	 */
	function claimWithdrawBtn(v) {
		if (v.claimStatus !== 'SUBMITTED') return '';
		return ' <button type="button" class="btn btn-xs btn-default" data-cy="claim-withdraw" data-id="' + esc(v.id) + '">'
			+ esc(tr('ui.js.claimWithdraw', 'Withdraw')) + '</button>';
	}
	function claimDecideBtns(v) {
		if (v.claimStatus !== 'SUBMITTED') return '';
		return '<button type="button" class="btn btn-xs btn-success" data-cy="claim-approve" data-id="' + esc(v.id) + '">'
			+ esc(tr('ui.js.claimApprove', 'Approve')) + '</button> '
			+ '<button type="button" class="btn btn-xs btn-danger" data-cy="claim-reject" data-id="' + esc(v.id) + '">'
			+ esc(tr('ui.js.claimReject', 'Reject')) + '</button>';
	}

	function row(v) {
		// EX-2b — a tagged line reads "Fuel and transport · Bus (LEA-123)": the category, then what it was for.
		var cats = (v.lines || []).map(function (l) {
			return l.categoryName ? (l.categoryName + (l.tagLabel ? ' · ' + l.tagLabel : '')) : null;
		}).filter(Boolean).join(', ');
		var tds = '<td>' + esc(v.voucherNo || '') + '</td>'
			+ '<td>' + showDate(v.voucherDate) + '</td>'
			+ '<td>' + esc(cats) + '</td>'
			+ '<td>' + esc(v.paidFrom === 'BANK' ? tr('ui.js.expBank', 'Bank')
				: v.paidFrom === 'DRAWER' ? tr('ui.js.expTill', 'Till')
				: v.paidFrom === 'AP' ? tr('ui.js.expBill', 'Bill')
				: v.paidFrom === 'EMPLOYEE' ? tr('ui.js.claimBy', 'Claim') + (v.claimantName ? ' · ' + v.claimantName : '')
				: tr('ui.js.expCash', 'Cash')) + '</td>'
			+ '<td>' + esc(v.payeeName || '') + '</td>'
			+ '<td style="text-align:right;font-variant-numeric:tabular-nums">' + esc(money(v.total)) + '</td>'
			+ '<td class="exp-chip">' + chip(v) + billState(v) + refusal(v) + receiptsBtn(v) + claimWithdrawBtn(v) + '</td>';
		if (canVoid) {
			// EX-3 — a till pay-out is corrected at the till (the server refuses its void), so no button here.
			// FP-3 — a bill with payments is voided only after its payments are reversed (the server refuses it too).
			var voidable = v.status === 'POSTED' && v.postingStatus !== 'PENDING' && v.source !== 'DRAWER'
				&& !((v.paidFrom === 'AP' || v.paidFrom === 'EMPLOYEE') && Number(v.paidAmount || 0) > 0);
			tds += '<td>' + (voidable
				? '<button type="button" class="btn btn-xs btn-default" data-cy="void-expense" data-id="' + esc(v.id)
					+ '" data-no="' + esc(v.voucherNo || '') + '">' + esc(tr('ui.js.expVoid', 'Void')) + '</button>'
				: '') + claimDecideBtns(v) + '</td>';
		}
		return '<tr data-id="' + esc(v.id) + '" class="expense-row' + (v.status === 'VOIDED' ? ' row-voided' : '')
			+ (closedForReceipts(v) ? ' row-no-receipts' : '') + '">' + tds + '</tr>';
	}

	function msg(text, tone) {
		var $m = $('#expMsg');
		$m.text(text || '').css('color', tone === 'bad' ? '#b3261e' : (tone === 'ok' ? '#2E7D32' : ''));
	}

	function loadCategories() {
		return $.ajax({ url: ctx() + 'expense/categories', dataType: 'json' }).done(function (res) {
			var $s = $('#expCategory').empty();
			$s.append($('<option>').val('').text(tr('ui.js.selectOne', 'Select one')));
			(res && res.data || []).filter(function (c) { return c.active; }).forEach(function (c) {
				$s.append($('<option>').val(c.id).text(c.name));
			});
			if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($s[0]);
		});
	}

	// ── EX-2e / E6 — Categories (owner/admin) ─────────────────────────────────────────────────────────────────
	// The panel lists every category, switched off ones included (the form lists only those switched on). Each row
	// saves on its own button; the server is the guard (expense accounts only, unique names, one stays on), and its
	// words are shown as they come. After any change the form's Category list is re-read.
	var expAccounts = [];
	function catMsg(text, kind) {
		$('#expCatMsg').text(text || '').css('color', kind === 'bad' ? '#b3261e' : (kind === 'ok' ? '#1b7f3b' : ''));
	}
	function accountOptions(selected) {
		return expAccounts.map(function (a) {
			return '<option value="' + esc(a.code) + '"' + (a.code === selected ? ' selected' : '') + '>'
				+ esc(a.code + ' ' + a.name) + '</option>';
		}).join('');
	}
	function catRow(c) {
		return '<tr data-id="' + esc(c.id) + '" data-cy="cat-row">'
			+ '<td><input type="text" class="form-control input-sm" data-cy="cat-name" maxlength="120" value="' + esc(c.name)
			+ '" aria-label="' + esc(tr('ui.js.expCatName', 'Category name')) + '"></td>'
			+ '<td><select class="form-control input-sm" data-cy="cat-account" data-no-search="true" aria-label="'
			+ esc(tr('ui.js.expCatAccount', 'Account')) + '">' + accountOptions(c.accountCode) + '</select></td>'
			+ '<td><input type="checkbox" data-cy="cat-on"' + (c.active ? ' checked' : '') + ' aria-label="'
			+ esc(tr('ui.js.expCatOn', 'Switched on')) + '"></td>'
			+ '<td><button type="button" class="btn btn-default btn-sm" data-cy="cat-save" onclick="expenseCategorySave(this)">'
			+ esc(tr('ui.js.expCatSave', 'Save')) + '</button></td></tr>';
	}
	function loadCategoryPanel() {
		catMsg('');
		var accounts = $.ajax({ url: ctx() + 'expense/categories/accounts', dataType: 'json' });
		var cats = $.ajax({ url: ctx() + 'expense/categories', dataType: 'json' });
		return $.when(accounts, cats).done(function (a, c) {
			expAccounts = (a[0] && a[0].data) || [];
			$('#expCatNewAccount').html(accountOptions(''));
			$('#expCatTable tbody').html(((c[0] && c[0].data) || []).map(catRow).join(''));
		}).fail(function (xhr) {
			catMsg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.expLoadFailed', 'Could not load expenses.'))
				: tr('ui.js.expLoadFailed', 'Could not load expenses.'), 'bad');
		});
	}
	function expenseCategoriesToggle() {
		var $p = $('#expCatPanel'), open = !$p.is(':visible');
		$p.toggle(open);
		$('#expCatOpen').attr('aria-expanded', String(open));
		if (open) loadCategoryPanel();
	}
	/** One request per press; only that button is busy. The answer — saved or the server's reason — is shown. */
	function sendCategory(btn, method, url, body, done) {
		var $b = $(btn), label = $b.html();
		$b.prop('disabled', true).text(tr('ui.js.expSaving', 'Saving…'));
		catMsg('');
		return $.ajax({ url: url, type: method, contentType: 'application/json', dataType: 'json', data: JSON.stringify(body) })
			.done(function (res) {
				if (!res || res.success !== true) { catMsg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad'); return; }
				catMsg(tr('ui.js.expCatSaved', 'Category saved'), 'ok');
				if (done) done(res.data);
				loadCategories();
			})
			.fail(function (xhr) {
				catMsg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
					: tr('ui.js.saveFailed', 'Save failed'), 'bad');
			})
			.always(function () { $b.prop('disabled', false).html(label); });
	}
	function expenseCategorySave(btn) {
		var $tr = $(btn).closest('tr');
		var name = String($tr.find('[data-cy=cat-name]').val() || '').trim();
		if (!name) { catMsg(tr('ui.js.expCatNameRequired', 'Give the category a name.'), 'bad'); return; }
		sendCategory(btn, 'PATCH', ctx() + 'expense/categories/' + encodeURIComponent($tr.data('id')), {
			name: name, accountCode: $tr.find('[data-cy=cat-account]').val(), active: $tr.find('[data-cy=cat-on]').is(':checked')
		}, function (c) { $tr.replaceWith(catRow(c)); });
	}
	function expenseCategoryAdd(btn) {
		var name = String($('#expCatNewName').val() || '').trim();
		if (!name) { catMsg(tr('ui.js.expCatNameRequired', 'Give the category a name.'), 'bad'); $('#expCatNewName').focus(); return; }
		sendCategory(btn, 'POST', ctx() + 'expense/categories', { name: name, accountCode: $('#expCatNewAccount').val() }, function (c) {
			$('#expCatTable tbody').append(catRow(c));
			$('#expCatNewName').val('');
		});
	}

	/**
	 * EX-2b — the "For" list, offered only when this dashboard's section declares a tag source
	 * (fragment parameter → data-tag-source). The options come from the module that owns them, and the server
	 * confirms the choice again on save, so this list is a convenience, never the control.
	 */
	function loadTags() {
		var source = String($('#ExpenseDiv').attr('data-tag-source') || '');
		var $g = $('#expTagGroup');
		if (!source) { $g.hide(); return $.Deferred().resolve().promise(); }
		return $.ajax({ url: ctx() + 'expense/tags?source=' + encodeURIComponent(source), dataType: 'json' })
			.done(function (res) {
				var list = (res && res.success === true && res.data) || [];
				var $s = $('#expTag').empty().append($('<option>').val('').text(tr('ui.js.expForNone', 'Not specified')));
				list.forEach(function (tg) {
					$s.append($('<option>').val(tg.type + ':' + tg.id).text(tg.label));
				});
				if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($s[0]);
				$g.toggle(list.length > 0);
			})
			.fail(function () { $g.hide(); });
	}

	/**
	 * FP-3 — the suppliers a bill can be owed to. "Bill (pay later)" is offered only when there is someone to owe:
	 * a school or a farm with no suppliers keeps Cash and Bank, unchanged.
	 */
	function loadSuppliers() {
		return $.ajax({ url: ctx() + 'expense/suppliers', dataType: 'json' })
			.done(function (res) {
				var list = (res && res.success === true && res.data) || [];
				var $pf = $('#expPaidFrom');
				$pf.find('option[value="AP"]').remove();
				if (!list.length) { $('#expBillGroup').hide(); return; }
				$pf.append($('<option>').val('AP').text(tr('ui.js.expBillOption', 'Bill (pay later)')));
				var $s = $('#expSupplier').empty().append($('<option>').val('').text(tr('ui.js.selectOne', 'Select one')));
				list.forEach(function (sp) { $s.append($('<option>').val(sp.id).text(sp.label)); });
				if (typeof global.refreshSearchableSelect === 'function') {
					global.refreshSearchableSelect($s[0]);
					global.refreshSearchableSelect($pf[0]);
				}
				toggleBill();
			});
	}

	function toggleBill() {
		$('#expBillGroup').toggle($('#expPaidFrom').val() === 'AP');
		// EX-6 — money from your own pocket is SENT FOR APPROVAL, not posted: the button says which will happen
		var claim = $('#expPaidFrom').val() === 'EMPLOYEE';
		var $l = $('#expSave .exp-save-label');
		if ($l.attr('data-post') === undefined) $l.attr('data-post', $l.text());   // the page's own (translated) words
		$l.text(claim ? tr('ui.js.claimSend', 'Send for approval') : $l.attr('data-post'));
		$('#expClaimHint').toggle(claim);
	}

	/** EX-6 — "Me (claim it back)" is offered only while Expense claims is switched on for this business. */
	function claimsOn() { return !!(global.CAPS && global.CAPS.expenseClaims === true); }
	function applyClaimOption() {
		var $s = $('#expPaidFrom'), has = $s.find('option[value="EMPLOYEE"]').length > 0;
		if (claimsOn() && !has) $s.append($('<option>').val('EMPLOYEE').text(tr('ui.js.claimPaidFrom', 'Me — claim it back')));
		if (!claimsOn() && has) { $s.find('option[value="EMPLOYEE"]').remove(); if (!$s.val()) $s.val('CASH'); }
		if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($s[0]);
		toggleBill();
		$('#expAdvOpen').toggle(claimsOn());          // EX-7b — advances belong to Expense claims
		loadAdvances();
	}

	// ── EX-7b — advances to staff ─────────────────────────────────────────────────────────────────────────────
	// What each member holds of the business's money (1300). An owner or admin gives and takes back from the Advances
	// panel; a member sees their own. The server decides who is staff, who may act, and how much is held.
	var advBalances = {};
	function advMsg(text, kind) {
		$('#expAdvMsg').text(text || '').css('color', kind === 'bad' ? '#b3261e' : (kind === 'ok' ? '#1b7f3b' : ''));
	}
	function loadAdvances() {
		advBalances = {};
		if (!claimsOn()) { $('#expMyAdvance').hide(); return $.Deferred().resolve().promise(); }
		return $.ajax({ url: ctx() + 'expense/advances', dataType: 'json' }).done(function (res) {
			var list = (res && res.success === true && res.data) || [];
			list.forEach(function (b) { advBalances[b.userId] = Number(b.balance || 0); });
			if (!canVoid) {
				var mine = list[0], held = mine ? Number(mine.balance || 0) : 0;
				$('#expMyAdvance').toggle(held > 0).text(held > 0 ? tr('ui.js.advYouHold', 'You hold an advance of {0}. Spend it on the business and claim it, or hand back what is left.').replace('{0}', money(held)) : '');
			}
			if ($('#expAdvPanel').is(':visible')) drawAdvances(list);
		});
	}
	function drawAdvances(list) {
		var rows = (list || []).filter(function (b) { return Number(b.balance || 0) > 0; });
		$('#expAdvTable tbody').html(rows.length ? rows.map(function (b) {
			return '<tr data-cy="adv-row" data-user="' + esc(b.userId) + '"><td>' + esc(b.memberName || '') + '</td>'
				+ '<td style="text-align:right;font-variant-numeric:tabular-nums" data-cy="adv-holds">' + esc(money(b.balance)) + '</td>'
				+ '<td><button type="button" class="btn btn-xs btn-default" data-cy="adv-take-back" data-user="' + esc(b.userId)
				+ '" data-name="' + esc(b.memberName || '') + '" data-holds="' + esc(b.balance) + '">' + esc(tr('ui.js.advTakeBack', 'Take back')) + '</button></td></tr>';
		}).join('') : '<tr><td colspan="3" class="text-muted">' + esc(tr('ui.js.advNone', 'Nobody holds an advance.')) + '</td></tr>');
	}
	function expenseAdvancesToggle() {
		var $p = $('#expAdvPanel'), open = !$p.is(':visible');
		$p.toggle(open);
		$('#expAdvOpen').attr('aria-expanded', String(open));
		if (!open) return;
		advMsg('');
		$p.data('idemKey', newKey());                 // ONE key per opening: a retried Give is the same advance
		$.ajax({ url: ctx() + 'expense/advances/staff', dataType: 'json' }).done(function (res) {
			var $s = $('#expAdvMember').empty().append($('<option>').val('').text(tr('ui.js.selectOne', 'Select one')));
			((res && res.success === true && res.data) || []).forEach(function (m) {
				$s.append($('<option>').val(m.userId).text(m.name + (m.email && m.email !== m.name ? ' (' + m.email + ')' : '')));
			});
		}).fail(function (xhr) { advMsg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, '') : '', 'bad'); });
		$.ajax({ url: ctx() + 'expense/advances', dataType: 'json' }).done(function (res) { drawAdvances((res && res.data) || []); });
	}
	function advanceSend(btn, action, body, key) {
		var $b = $(btn), label = $b.html();
		$b.prop('disabled', true).text(tr('ui.js.expSaving', 'Saving…'));
		advMsg('');
		return $.ajax({ url: ctx() + 'expense/advances/' + action, type: 'POST', contentType: 'application/json', dataType: 'json',
			headers: { 'Idempotency-Key': key }, data: JSON.stringify(body) })
			.done(function (res) {
				if (!res || res.success !== true) { advMsg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad'); return; }
				advMsg((res.message || '') + (res.data && res.data.receiptNo ? ' — ' + res.data.receiptNo : ''), 'ok');
				$('#expAdvPanel').data('idemKey', newKey());
				$('#expAdvAmount').val('');
				loadAdvances().always(function () {
					$.ajax({ url: ctx() + 'expense/advances', dataType: 'json' }).done(function (r2) { drawAdvances((r2 && r2.data) || []); });
				});
			})
			.fail(function (xhr) {
				// the key is KEPT: pressing again is the same advance, never a second one
				advMsg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
					: tr('ui.js.saveFailed', 'Save failed'), 'bad');
			})
			.always(function () { $b.prop('disabled', false).html(label); });
	}
	function expenseAdvanceGive(btn) {
		var userId = Number($('#expAdvMember').val()), amount = Number($('#expAdvAmount').val());
		if (!userId) { advMsg(tr('ui.js.advChooseMember', 'Choose the member.'), 'bad'); return; }
		if (!(amount > 0)) { advMsg(tr('ui.js.expAmountRequired', 'Enter an amount greater than zero.'), 'bad'); return; }
		advanceSend(btn, 'give', { userId: userId, amount: amount, method: $('#expAdvMethod').val() }, $('#expAdvPanel').data('idemKey'));
	}
	$(document).on('click', '#expAdvTable [data-cy="adv-take-back"]', function () {
		var btn = this, userId = Number($(this).attr('data-user')), holds = Number($(this).attr('data-holds'));
		var ask = typeof global.uiPromptConfirm === 'function' ? global.uiPromptConfirm : null;
		if (!ask) return;
		ask({ title: tr('ui.js.advTakeBackTitle', 'Take back from {0}?').replace('{0}', $(this).attr('data-name')),
			input: { label: tr('ui.js.advTakeBackAmount', 'Amount handed back (holds {0}); in the way chosen beside Give').replace('{0}', money(holds)), value: holds.toFixed(2) } })
			.then(function (v) {
				if (v === null) return;
				var amount = Number(String(v).trim());
				if (!(amount > 0)) { advMsg(tr('ui.js.expAmountRequired', 'Enter an amount greater than zero.'), 'bad'); return; }
				advanceSend(btn, 'take-back', { userId: userId, amount: amount, method: $('#expAdvMethod').val() }, newKey());
			});
	});

	// ── EX-8a — the report over the list's From/To. The server's figures, rendered as they come: it adds up exactly as
	// the profit and loss does (an expense on its own date, a void as a minus on the day it was voided).
	function reportQuery() {
		var q = [];
		if ($('#expFrom').val()) q.push('from=' + encodeURIComponent($('#expFrom').val()));
		if ($('#expTo').val()) q.push('to=' + encodeURIComponent($('#expTo').val()));
		return q;
	}
	function expenseReportToggle() {
		var $p = $('#expReportPanel'), open = !$p.is(':visible');
		$p.toggle(open);
		$('#expReportOpen').attr('aria-expanded', String(open));
		if (open) expenseReportRun();
	}
	function expenseReportRun(btn) {
		var q = reportQuery(), $m = $('#expReportMsg').text('');
		$('#expReportCsv').attr('href', ctx() + 'expense/reports/expenses.csv' + (q.length ? '?' + q.join('&') : ''));
		q.push('by=' + encodeURIComponent($('#expReportBy').val() || 'category'));
		var $b = btn ? $(btn).prop('disabled', true) : null;
		return $.ajax({ url: ctx() + 'expense/reports/summary?' + q.join('&'), dataType: 'json' })
			.done(function (res) {
				var d = res && res.success === true && res.data;
				if (!d) { $m.css('color', '#b3261e').text((res && res.message) || tr('ui.js.expLoadFailed', 'Could not load expenses.')); return; }
				$('#expReportTable tbody').html(d.groups.length ? d.groups.map(function (g) {
					return '<tr data-cy="report-row"><td>' + esc(g.label) + '</td>'
						+ '<td style="text-align:right">' + esc(g.count) + '</td>'
						+ '<td style="text-align:right;font-variant-numeric:tabular-nums">' + esc(money(g.amount)) + '</td></tr>';
				}).join('') : '<tr><td colspan="3" class="text-muted">' + esc(tr('ui.js.expNone', 'No expenses in this period.')) + '</td></tr>');
				$('#expReportTotal').text(money(d.total));
			})
			.fail(function (xhr) {
				$m.css('color', '#b3261e').text(typeof global.apiFailMessage === 'function'
					? global.apiFailMessage(xhr, tr('ui.js.expLoadFailed', 'Could not load expenses.')) : tr('ui.js.expLoadFailed', 'Could not load expenses.'));
			})
			.always(function () { if ($b) $b.prop('disabled', false); });
	}

	/** EX-6 — owner/admin: how many claims wait for a decision, and a way to see only those. */
	var claimFilter = '';
	function loadClaimsWaiting() {
		var $n = $('#expClaimsWaiting');
		if (!canVoid || !claimsOn()) { $n.hide(); return; }
		$.ajax({ url: ctx() + 'expense/vouchers?claim=SUBMITTED&page=0&size=1', dataType: 'json' }).done(function (res) {
			var n = Number((res && res.data && res.data.totalElements) || 0);
			if (!n && !claimFilter) { $n.hide(); return; }
			$('#expClaimsWaitingText').text(n === 1 ? tr('ui.js.claimsWaitingOne', '1 claim is waiting for approval.')
				: tr('ui.js.claimsWaitingN', '{0} claims are waiting for approval.').replace('{0}', n));
			$('#expClaimsShow').text(claimFilter ? tr('ui.js.claimsShowAll', 'Show every expense') : tr('ui.js.claimsShow', 'Show them'));
			$n.css('display', 'flex');
		});
	}
	function expenseClaimsFilter() {
		claimFilter = claimFilter ? '' : 'SUBMITTED';
		expenseLoad();
	}

	/**
	 * EX-2d / E4 — the list is read a page at a time (PAGE rows), with "Showing a–b of N", Previous/Next, and the
	 * period's total underneath. The total is the SERVER's sum over every row the filter holds for this caller, not
	 * the page on screen — before EX-2d the list silently stopped at 200 rows and had no total at all.
	 * expenseLoad()      → back to the first page (Search, a new expense: it lands on top)
	 * expenseLoad(page)  → that page (Previous/Next; a void or a payment redraws the page you are on)
	 */
	var PAGE = 50, expPage = 0;
	function filterQuery() {
		var q = [];
		if ($('#expFrom').val()) q.push('from=' + encodeURIComponent($('#expFrom').val()));
		if ($('#expTo').val()) q.push('to=' + encodeURIComponent($('#expTo').val()));
		return q;
	}
	function listQuery() {
		var q = filterQuery();
		if (claimFilter) q.push('claim=' + claimFilter);   // EX-6 — the approver's "waiting" view
		return q;
	}
	function expenseLoad(page) {
		expPage = Math.max(0, Number(page) || 0);
		var q = listQuery();
		q.push('page=' + expPage, 'size=' + PAGE);
		var $tb = $('#tableExpense tbody');
		loadTotals();
		loadClaimsWaiting();
		return $.ajax({ url: ctx() + 'expense/vouchers?' + q.join('&'), dataType: 'json' })
			.done(function (res) {
				var pg = (res && res.data) || {};
				var rows = pg.content || [];
				if (!rows.length && expPage > 0) { expenseLoad(expPage - 1); return; }   // the last row of a page went away
				var cols = $('#tableExpense thead th').length;
				$tb.html(rows.length ? rows.map(row).join('')
					: '<tr><td colspan="' + cols + '" class="text-muted">' + esc(tr('ui.js.expNone', 'No expenses in this period.')) + '</td></tr>');
				pager(pg, rows.length);
				// Anything still posting is re-read until the ledger answers — bounded, never a busy loop.
				rows.filter(function (v) { return v.status === 'POSTED' && v.postingStatus === 'PENDING'; })
					.forEach(function (v) { watch(v.id, 15); });
			})
			.fail(function (xhr) {
				$('#expPager').hide();
				$tb.html('<tr><td colspan="8" class="text-danger">'
					+ esc(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.expLoadFailed', 'Could not load expenses.'))
						: tr('ui.js.expLoadFailed', 'Could not load expenses.')) + '</td></tr>');
			});
	}
	function pager(pg, shown) {
		var total = Number(pg.totalElements || 0);
		if (!total) { $('#expPager').hide(); return; }
		var first = expPage * PAGE + 1, last = expPage * PAGE + shown;
		$('#expShowing').text(tr('ui.js.expShowing', 'Showing {0}–{1} of {2}')
			.replace('{0}', first).replace('{1}', last).replace('{2}', total));
		$('#expPrev').prop('disabled', expPage === 0);
		$('#expNext').prop('disabled', pg.last !== false);
		$('#expPrev, #expNext').toggle(total > PAGE);
		$('#expPager').css('display', 'flex');
	}
	/** The period's total — posted expenses only; a void or a draft is not money spent. */
	function loadTotals() {
		var $t = $('#expTotal').text('');
		return $.ajax({ url: ctx() + 'expense/vouchers/totals?' + filterQuery().join('&'), dataType: 'json' })
			.done(function (res) {
				var d = res && res.success === true && res.data;
				if (!d) return;
				$t.text(tr('ui.js.expPeriodTotal', 'Total spent: {0} ({1} expenses; voided ones not counted)')
					.replace('{0}', money(d.total)).replace('{1}', d.count));
			});
	}
	function expensePage(step) { expenseLoad(expPage + step); }

	/** Re-read one voucher every 1.5 s until the ledger answers, then redraw its row. */
	function watch(id, tries) {
		if (tries <= 0) return;
		setTimeout(function () {
			$.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(id), dataType: 'json' }).done(function (res) {
				var v = res && res.data;
				if (!v) return;
				var $tr = $('#tableExpense tbody tr[data-id="' + id + '"]');
				if ($tr.length) $tr.replaceWith(row(v));
				if (v.status === 'POSTED' && v.postingStatus === 'PENDING') watch(id, tries - 1);
			});
		}, 1500);
	}

	function expenseSave(btn) {
		var $f = $('#ExpenseForm');
		var amount = Number($('#expAmount').val());
		var categoryId = $('#expCategory').val();
		if (!categoryId) { msg(tr('ui.js.expCategoryRequired', 'Choose a category.'), 'bad'); return; }
		if (!(amount > 0)) { msg(tr('ui.js.expAmountRequired', 'Enter an amount greater than zero.'), 'bad'); $('#expAmount').focus(); return; }
		var isBill = $('#expPaidFrom').val() === 'AP', isClaim = $('#expPaidFrom').val() === 'EMPLOYEE';
		if (isBill && !$('#expSupplier').val()) { msg(tr('ui.js.expSupplierRequired', 'Choose the supplier this bill is owed to.'), 'bad'); return; }

		// One key per FORM FILL: a retry of the same fill replays the first save on the server.
		if (!$f.data('idemKey')) $f.data('idemKey', newKey());
		var body = {
			voucherDate: $('#expDate').val() || isoOf(new Date()),
			paidFrom: $('#expPaidFrom').val(),
			payeeName: $('#expPayee').val(),
			note: $('#expNote').val(),
			supplierId: isBill ? Number($('#expSupplier').val()) : null,
			dueDate: isBill ? ($('#expDue').val() || null) : null,
			lines: [(function () {
				var line = { categoryId: Number(categoryId), amount: amount, description: $('#expNote').val() };
				var tag = String($('#expTag').val() || '');
				if (tag.indexOf(':') > 0) { line.tagType = tag.split(':')[0]; line.tagId = Number(tag.split(':')[1]); }
				return line;
			})()]
		};
		var $b = $(btn), label = $b.html();
		$b.prop('disabled', true).html('<span class="glyphicon glyphicon-hourglass"></span> ' + esc(tr('ui.js.expSaving', 'Saving…')));
		msg('');
		// EX-5 — the receipt goes first (compressed here), so the save carries it and the owner's rule is checked there
		formReceipt().then(function (ids) {
			body.receiptIds = ids;
			return $.ajax({
				url: ctx() + (isClaim ? 'expense/claims' : 'expense/vouchers?post=true'), type: 'POST', contentType: 'application/json', dataType: 'json',
				headers: { 'Idempotency-Key': $f.data('idemKey') }, data: JSON.stringify(body)
			});
		}).done(function (res) {
			if (!res || res.success !== true) {
				msg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad');
				return;
			}
			msg(isClaim ? tr('ui.js.claimSent', 'Claim sent for approval. It goes to the books once an owner or admin approves it.')
				: tr('ui.js.expSaved', 'Expense saved. Posting to the books.'), 'ok');
			$f.removeData('idemKey');
			$f.removeData('receipt');
			$('#expAmount, #expPayee, #expNote, #expReceipt').val('');
			applyPaidFromDefault();                          // EX-2f: the next expense starts from the owner's default
			expenseLoad();
		}).fail(function (xhr) {
			if (xhr === 'cancelled') return;                // the person chose not to save (duplicate receipt)
			if (typeof xhr === 'string') { msg(xhr, 'bad'); return; }
			// The key is KEPT: pressing Save again replays this fill rather than recording it twice.
			msg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
				: tr('ui.js.saveFailed', 'Save failed'), 'bad');
		}).always(function () {
			$b.prop('disabled', false).html(label);
		});
	}

	// ── EX-5 — receipts ────────────────────────────────────────────────────────────────────────────────────────
	// A photo is made smaller HERE before it is sent (long side 1600 px, JPEG), so a phone photo of several MB goes up
	// as a few hundred KB; a PDF goes as it is. The server decides what the file really is from its first bytes.
	var PHOTO_MAX = 1600;
	function compress(file) {
		var d = $.Deferred();
		if (!file) return d.resolve(null).promise();
		if (file.type === 'application/pdf') return d.resolve({ blob: file, name: file.name }).promise();
		if (!/^image\/(jpeg|png|webp)$/.test(file.type)) {
			return d.reject(/heic|heif/i.test(file.type + file.name)
				? tr('ui.js.expReceiptHeic', 'This phone photo format (HEIC) cannot be read here. Set the camera to "Most compatible", or attach a JPEG or PDF.')
				: tr('ui.js.expReceiptType', 'A receipt must be a photo (JPEG, PNG or WEBP) or a PDF.')).promise();
		}
		var url = URL.createObjectURL(file), img = new Image();
		img.onload = function () {
			var k = Math.min(1, PHOTO_MAX / Math.max(img.naturalWidth, img.naturalHeight));
			if (k === 1 && file.size < 400 * 1024) { URL.revokeObjectURL(url); d.resolve({ blob: file, name: file.name }); return; }
			var c = document.createElement('canvas');
			c.width = Math.round(img.naturalWidth * k); c.height = Math.round(img.naturalHeight * k);
			var g = c.getContext('2d');
			g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);   // a transparent PNG becomes white, not black
			g.drawImage(img, 0, 0, c.width, c.height);
			URL.revokeObjectURL(url);
			c.toBlob(function (b) { d.resolve({ blob: b || file, name: (file.name || 'receipt').replace(/\.\w+$/, '') + '.jpg' }); }, 'image/jpeg', 0.82);
		};
		img.onerror = function () { URL.revokeObjectURL(url); d.reject(tr('ui.js.expReceiptType', 'A receipt must be a photo (JPEG, PNG or WEBP) or a PDF.')); };
		img.src = url;
		return d.promise();
	}
	function uploadReceipt(file, voucherId) {
		return compress(file).then(function (c) {
			var fd = new FormData();
			fd.append('file', c.blob, c.name);
			if (voucherId) fd.append('voucherId', voucherId);
			return $.ajax({ url: ctx() + 'expense/receipts', type: 'POST', data: fd, processData: false, contentType: false, dataType: 'json' })
				.then(function (res) {
					if (!res || res.success !== true) return $.Deferred().reject((res && res.message) || tr('ui.js.saveFailed', 'Save failed'));
					return res.data;
				});
		});
	}
	/** The form's receipt, uploaded once per chosen file (a retried save reuses it), after a duplicate warning. */
	function formReceipt() {
		var $f = $('#ExpenseForm'), input = $('#expReceipt')[0], file = input && input.files && input.files[0];
		if (!file) return $.Deferred().resolve([]).promise();
		var sig = file.name + ':' + file.size + ':' + file.lastModified, kept = $f.data('receipt');
		var uploaded = (kept && kept.sig === sig) ? $.Deferred().resolve(kept.r).promise() : uploadReceipt(file, null);
		return uploaded.then(function (r) {
			$f.data('receipt', { sig: sig, r: r });          // a retried save reuses the upload — and warns again
			if (!r.alsoOn || !r.alsoOn.length || typeof global.uiConfirm !== 'function') return [r.id];
			return $.Deferred(function (d) {
				global.uiConfirm({ title: tr('ui.js.expReceiptDupTitle', 'This receipt is already on another expense'),
					message: tr('ui.js.expReceiptDupMsg', 'The same receipt is on {0}. Save this expense anyway?').replace('{0}', r.alsoOn.join(', ')),
					tone: 'warning' }).then(function (ok) { if (ok) d.resolve([r.id]); else d.reject('cancelled'); });
			}).promise();
		});
	}

	function receiptRow(rc, canRemove) {
		return '<tr data-cy="receipt-row" data-rid="' + esc(rc.id) + '">'
			+ '<td><a href="' + esc(ctx() + 'expense/receipts/' + encodeURIComponent(rc.id) + '/content') + '" target="_blank" rel="noopener" data-cy="receipt-view">'
			+ '<span class="glyphicon glyphicon-' + (rc.contentType === 'application/pdf' ? 'file' : 'picture') + '"></span> '
			+ esc(rc.name || tr('ui.js.expReceipt', 'Receipt')) + '</a></td>'
			+ '<td class="text-muted">' + esc(Math.max(1, Math.round((rc.size || 0) / 1024))) + ' KB</td>'
			+ '<td>' + (rc.alsoOn && rc.alsoOn.length ? '<span class="text-warning" data-cy="receipt-also-on">'
				+ esc(tr('ui.js.expReceiptAlsoOn', 'Also on')) + ' ' + esc(rc.alsoOn.join(', ')) + '</span>' : '') + '</td>'
			+ '<td>' + (canRemove ? '<button type="button" class="btn btn-xs btn-default" data-cy="receipt-remove" data-rid="' + esc(rc.id) + '">'
				+ esc(tr('ui.js.expReceiptRemove', 'Remove')) + '</button>' : '') + '</td></tr>';
	}
	function showReceipts(voucherId) {
		var $v = $('#tableExpense tbody tr.expense-row[data-id="' + voucherId + '"]');
		var cols = $('#tableExpense thead th').length, voided = $v.hasClass('row-no-receipts');
		$('#tableExpense tbody tr.exp-rcpt-detail[data-for="' + voucherId + '"]').remove();
		var $d = $('<tr class="exp-rcpt-detail" data-cy="receipts-list">').attr('data-for', voucherId)
			.html('<td colspan="' + cols + '" class="text-muted">' + esc(tr('ui.js.loading', 'Loading…')) + '</td>');
		$v.after($d);
		return $.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(voucherId) + '/receipts', dataType: 'json' })
			.done(function (res) {
				var list = (res && res.data) || [];
				var add = voided ? '' : '<label class="btn btn-xs btn-default" style="margin:0">'
					+ '<span class="glyphicon glyphicon-paperclip"></span> ' + esc(tr('ui.js.expReceiptAdd', 'Add a receipt'))
					+ '<input type="file" data-cy="receipt-add" data-id="' + esc(voucherId) + '" accept="image/jpeg,image/png,image/webp,application/pdf,image/*" style="display:none"></label>';
				$d.html('<td colspan="' + cols + '"><table class="table table-condensed" style="margin:0;background:transparent"><tbody>'
					+ (list.length ? list.map(function (rc) { return receiptRow(rc, canVoid); }).join('') : '<tr><td class="text-muted">' + esc(tr('ui.js.expNoReceipt', 'No receipt yet.')) + '</td></tr>')
					+ '</tbody></table>' + add + ' <span class="exp-rcpt-msg" role="status" aria-live="polite"></span></td>');
			});
	}
	function redrawRow(id) {
		return $.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(id), dataType: 'json' }).done(function (res) {
			if (res && res.data) $('#tableExpense tbody tr.expense-row[data-id="' + id + '"]').replaceWith(row(res.data));
		});
	}

	function expenseVoid(id, no) {
		var ask = typeof global.uiPromptConfirm === 'function' ? global.uiPromptConfirm : null;
		if (!ask) return;
		ask({ title: tr('ui.js.expVoidTitle', 'Void this expense?') + (no ? ' ' + no : ''),
			input: { label: tr('ui.js.expVoidReason', 'Reason (required)') }, tone: 'danger' })
			.then(function (reason) {
				if (reason === null) return;
				if (!String(reason).trim()) { uiAlertSafe(tr('ui.js.expVoidReason', 'Reason (required)')); return; }
				$.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(id) + '/void', type: 'POST',
					contentType: 'application/json', dataType: 'json', data: JSON.stringify({ reason: String(reason).trim() }) })
					.done(function (res) {
						if (!res || res.success !== true) { uiAlertSafe((res && res.message) || tr('ui.js.saveFailed', 'Save failed')); return; }
						msg(tr('ui.js.expVoidDone', 'Expense voided'), 'ok');
						expenseLoad(expPage);
					})
					.fail(function (xhr) {
						uiAlertSafe(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
							: tr('ui.js.saveFailed', 'Save failed'));
					});
			});
	}

	// ── FP-3b: a bill's payments, and reversing one ─────────────────────────────────────────────────────────
	// The list opens under the bill's row. Reverse asks why; the server reverses it in the books first (a closed period
	// refuses it in words) and only then re-opens the bill — so the row is redrawn from the server's answer, never
	// assumed. Only the pressed button shows it is working (§0c).
	function paymentRow(p) {
		var state = p.status === 'REVERSED'
			? '<span class="label label-default">' + esc(tr('ui.js.expReversed', 'Reversed')) + '</span> ' + esc(p.reversalReceiptNo || '')
				+ (p.reversalReason ? ' <span class="text-muted">— ' + esc(p.reversalReason) + '</span>' : '')
			: p.status === 'RECORDED' ? esc(tr('ui.js.expPaid', 'Paid')) : esc(p.status);
		var btn = p.reversible
			? '<button type="button" class="btn btn-xs btn-default" data-cy="reverse-payment" data-bill="' + esc(p.voucherId)
				+ '" data-pid="' + esc(p.id) + '" data-no="' + esc(p.receiptNo || '') + '">' + esc(tr('ui.js.expReverse', 'Reverse')) + '</button>'
			: (p.status === 'RECORDED' && !p.reversible
				? '<span class="text-muted" style="font-size:11.5px">' + esc(tr('ui.js.expViaPaySupplier', 'Paid through Pay Supplier')) + '</span>' : '');
		return '<tr data-cy="bill-payment-row" data-pid="' + esc(p.id) + '">'
			+ '<td>' + esc(p.receiptNo || p.reference || '') + '</td><td>' + showDate(p.paidOn) + '</td>'
			+ '<td style="text-align:right;font-variant-numeric:tabular-nums">' + esc(money(p.amount)) + '</td>'
			+ '<td>' + esc(p.method === 'BANK' ? tr('ui.js.expBank', 'Bank') : tr('ui.js.expCash', 'Cash')) + '</td>'
			+ '<td>' + state + '</td><td>' + btn + '</td></tr>';
	}
	function showBillPayments(billId) {
		var $bill = $('#tableExpense tbody tr.expense-row[data-id="' + billId + '"]');
		var cols = $('#tableExpense thead th').length;
		$('#tableExpense tbody tr.exp-pay-detail[data-for="' + billId + '"]').remove();
		var $d = $('<tr class="exp-pay-detail" data-cy="bill-payments-list">').attr('data-for', billId)
			.html('<td colspan="' + cols + '" class="text-muted">' + esc(tr('ui.js.loading', 'Loading…')) + '</td>');
		$bill.after($d);
		$bill.find('[data-cy=bill-payments]').attr('aria-expanded', 'true');
		return $.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(billId) + '/payments', dataType: 'json' })
			.done(function (res) {
				var list = (res && res.data) || [];
				$d.html('<td colspan="' + cols + '"><table class="table table-condensed" style="margin:0;background:transparent"><tbody>'
					+ (list.length ? list.map(paymentRow).join('') : '<tr><td class="text-muted">—</td></tr>')
					+ '</tbody></table><span class="exp-pay-msg" role="status" aria-live="polite"></span></td>');
			});
	}
	function redrawBill(billId) {
		return $.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(billId), dataType: 'json' }).done(function (res) {
			if (res && res.data) $('#tableExpense tbody tr.expense-row[data-id="' + billId + '"]').replaceWith(row(res.data));
		});
	}
	function reversePayment(btn) {
		var $b = $(btn), billId = $b.attr('data-bill'), pid = $b.attr('data-pid');
		var ask = typeof global.uiPromptConfirm === 'function' ? global.uiPromptConfirm : null;
		if (!ask) return;
		ask({ title: tr('ui.js.expReverseTitle', 'Reverse this payment?') + ' ' + ($b.attr('data-no') || ''),
			message: tr('ui.js.expReverseHint', 'The money goes back to the cash or bank it came from, and the bill owes it again.'),
			input: { label: tr('ui.js.expVoidReason', 'Reason (required)') }, tone: 'danger' })
			.then(function (reason) {
				if (reason === null) return;
				if (!String(reason).trim()) { uiAlertSafe(tr('ui.js.expVoidReason', 'Reason (required)')); return; }
				var label = $b.html();
				$b.prop('disabled', true).text(tr('ui.js.expReversing', 'Reversing…'));
				$.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(billId) + '/payments/' + encodeURIComponent(pid) + '/reverse',
					type: 'POST', contentType: 'application/json', dataType: 'json', data: JSON.stringify({ reason: String(reason).trim() }) })
					.done(function (res) {
						if (!res || res.success !== true) {
							$b.prop('disabled', false).html(label);
							$b.closest('.exp-pay-detail').find('.exp-pay-msg').css('color', '#b3261e').text((res && res.message) || tr('ui.js.saveFailed', 'Save failed'));
							return;
						}
						msg(tr('ui.js.expReversedDone', 'Payment reversed') + (res.data && res.data.reversalReceiptNo ? ' — ' + res.data.reversalReceiptNo : ''), 'ok');
						redrawBill(billId).always(function () { showBillPayments(billId); });
					})
					.fail(function (xhr) {
						$b.prop('disabled', false).html(label);
						$b.closest('.exp-pay-detail').find('.exp-pay-msg').css('color', '#b3261e').text(typeof global.apiFailMessage === 'function'
							? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed')) : tr('ui.js.saveFailed', 'Save failed'));
					});
			});
	}

	// ── FP-3: paying a bill ─────────────────────────────────────────────────────────────────────

	function expensePayOpen(id, no, open, claim, userId) {
		var $p = $('#expPayPanel');
		// EX-7b — a claim may be settled from what its claimant holds as an advance (no money moves)
		var $m = $('#expPayMethod'), held = claim ? Number(advBalances[userId] || 0) : 0;
		$m.find('option[value="ADVANCE"]').remove();
		if (held > 0) $m.append($('<option>').val('ADVANCE').text(tr('ui.js.advFrom', 'From their advance (holds {0})').replace('{0}', money(held))));
		$m.val('CASH');
		$p.data({ id: id, idemKey: newKey() });   // ONE key per opening of the panel
		$('#expPayTitle').text((claim ? tr('ui.js.claimPayBackTitle', 'Pay back claim') : tr('ui.js.expPayTitle', 'Pay bill')) + ' ' + (no || '') + ' — '
			+ (claim ? tr('ui.js.claimOwed', 'Owed to the member') : tr('ui.js.expOwes', 'Owes')) + ' ' + money(open));
		$('#expPayAmount').val(Number(open).toFixed(2));
		$('#expPayMsg').text('');
		$p.show();
		$('#expPayAmount').focus().select();
	}

	function expensePayClose() {
		$('#expPayPanel').hide().removeData('id').removeData('idemKey');
	}

	function expensePay(btn) {
		var $p = $('#expPayPanel'), id = $p.data('id');
		var amount = Number($('#expPayAmount').val());
		if (!id) return;
		if (!(amount > 0)) {
			$('#expPayMsg').css('color', '#b3261e').text(tr('ui.js.expAmountRequired', 'Enter an amount greater than zero.'));
			return;
		}
		var $b = $(btn), label = $b.html();
		$b.prop('disabled', true).html('<span class="glyphicon glyphicon-hourglass"></span> ' + esc(tr('ui.js.expPaying', 'Paying…')));
		$.ajax({
			url: ctx() + 'expense/vouchers/' + encodeURIComponent(id) + '/pay', type: 'POST', contentType: 'application/json',
			dataType: 'json', headers: { 'Idempotency-Key': $p.data('idemKey') },
			data: JSON.stringify({ amount: amount, method: $('#expPayMethod').val() })
		}).done(function (res) {
			if (!res || res.success !== true) {
				$('#expPayMsg').css('color', '#b3261e').text((res && res.message) || tr('ui.js.saveFailed', 'Save failed'));
				return;
			}
			expensePayClose();
			loadAdvances();                               // EX-7b — a settlement from an advance changes what is held
			msg(tr('ui.js.expPayDone', 'Payment recorded') + (res.data && res.data.receiptNo ? ' — ' + res.data.receiptNo : ''), 'ok');
			expenseLoad(expPage);
		}).fail(function (xhr) {
			// The key is KEPT: pressing Pay again is the same payment, never a second one.
			$('#expPayMsg').css('color', '#b3261e').text(typeof global.apiFailMessage === 'function'
				? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed')) : tr('ui.js.saveFailed', 'Save failed'));
		}).always(function () {
			$b.prop('disabled', false).html(label);
		});
	}

	// ── EX-2f / E5 — expense settings ─────────────────────────────────────────────────────────────────────────
	// Read by EVERY member's form (the default Paid from must REACH the form — the "saved default tender never reached
	// New Sale" lesson), changed by owner/admin in the Settings panel. The server validates and answers in words.
	var KEY_BACK = 'expense.voucher.backdateDays', KEY_PAID = 'expense.voucher.defaultPaidFrom', KEY_RCPT = 'expense.receipt.requiredAbove';
	var expSettings = {};
	function loadExpenseSettings() {
		return $.ajax({ url: ctx() + 'expense/settings', dataType: 'json' }).done(function (res) {
			expSettings = {};
			((res && res.success === true && res.data) || []).forEach(function (e) { expSettings[e.key] = e.value; });
			applyPaidFromDefault();
			$('#expSetBackdate').val(expSettings[KEY_BACK] != null ? expSettings[KEY_BACK] : '');
			if (expSettings[KEY_PAID]) $('#expSetPaidFrom').val(expSettings[KEY_PAID]);
			$('#expSetReceiptAbove').val(expSettings[KEY_RCPT] != null ? expSettings[KEY_RCPT] : '');
			// EX-5 — tell the person filling the form when a receipt will be required
			var above = Number(expSettings[KEY_RCPT] || 0);
			$('#expReceiptHint').text(above > 0
				? tr('ui.js.expReceiptRequiredHint', 'Required for an expense above {0}. Photos are made smaller before they are sent.').replace('{0}', money(above))
				: tr('ui.js.expReceiptHint', 'A photo or PDF of the bill. Photos are made smaller before they are sent.'));
		});
	}
	function applyPaidFromDefault() {
		var d = expSettings[KEY_PAID];
		if (d !== 'CASH' && d !== 'BANK') return;
		$('#expPaidFrom').val(d);
		if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect($('#expPaidFrom')[0]);
		toggleBill();
	}
	function setMsg(text, kind) {
		$('#expSetMsg').text(text || '').css('color', kind === 'bad' ? '#b3261e' : (kind === 'ok' ? '#1b7f3b' : ''));
	}
	function expenseSettingsToggle() {
		var $p = $('#expSetPanel'), open = !$p.is(':visible');
		$p.toggle(open);
		$('#expSetOpen').attr('aria-expanded', String(open));
		if (open) { setMsg(''); loadExpenseSettings(); }
	}
	function expenseSettingSave(btn, key, value) {
		var $b = $(btn), label = $b.html();
		$b.prop('disabled', true).text(tr('ui.js.expSaving', 'Saving…'));
		setMsg('');
		return $.ajax({ url: ctx() + 'expense/settings', type: 'POST', dataType: 'json', data: { key: key, value: value } })
			.done(function (res) {
				if (!res || res.success !== true) { setMsg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad'); return; }
				setMsg(tr('ui.js.expSettingSaved', 'Setting saved'), 'ok');
				loadExpenseSettings();
			})
			.fail(function (xhr) {
				setMsg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
					: tr('ui.js.saveFailed', 'Save failed'), 'bad');
			})
			.always(function () { $b.prop('disabled', false).html(label); });
	}

	function uiAlertSafe(text) {
		if (typeof global.uiAlert === 'function') global.uiAlert({ title: tr('ui.js.expenses', 'Expenses'), message: text, tone: 'danger' });
	}

	function showExpenses() {
		$('.formDiv').hide();
		$('#ExpenseDiv').show();
		canVoid = $('#tableExpense thead th').length >= 8;
		var today = new Date();
		if (!$('#expDate').val()) setDate('#expDate', isoOf(today));
		if (!$('#expFrom').val()) setDate('#expFrom', isoOf(new Date(today.getFullYear(), today.getMonth(), 1)));
		if (!$('#expTo').val()) setDate('#expTo', isoOf(today));
		applyClaimOption();
		loadCategories().always(function () { loadTags(); loadSuppliers().always(loadExpenseSettings); expenseLoad(); });
	}

	$(document).on('change', '#expPaidFrom', toggleBill);
	$(document).on('click', '#tableExpense [data-cy="expense-receipts"]', function () {
		var id = $(this).attr('data-id');
		var $open = $('#tableExpense tbody tr.exp-rcpt-detail[data-for="' + id + '"]');
		if ($open.length) { $open.remove(); return; }
		showReceipts(id);
	});
	$(document).on('change', '#tableExpense [data-cy="receipt-add"]', function () {
		var id = $(this).attr('data-id'), file = this.files && this.files[0], $m = $(this).closest('td').find('.exp-rcpt-msg');
		if (!file) return;
		$m.css('color', '').text(tr('ui.js.expSaving', 'Saving…'));
		uploadReceipt(file, id).done(function (r) {
			redrawRow(id).always(function () {
				showReceipts(id).done(function () {
					if (r.alsoOn && r.alsoOn.length) $('#tableExpense tr.exp-rcpt-detail[data-for="' + id + '"] .exp-rcpt-msg').css('color', '#a15c00')
						.text(tr('ui.js.expReceiptAlsoOn', 'Also on') + ' ' + r.alsoOn.join(', '));
				});
			});
		}).fail(function (e) {
			$m.css('color', '#b3261e').text(typeof e === 'string' ? e : (typeof global.apiFailMessage === 'function'
				? global.apiFailMessage(e, tr('ui.js.saveFailed', 'Save failed')) : tr('ui.js.saveFailed', 'Save failed')));
		});
	});
	$(document).on('click', '#tableExpense [data-cy="receipt-remove"]', function () {
		var rid = $(this).attr('data-rid'), id = $(this).closest('tr.exp-rcpt-detail').attr('data-for');
		var go = function () {
			$.ajax({ url: ctx() + 'expense/receipts/' + encodeURIComponent(rid), type: 'DELETE', dataType: 'json' })
				.always(function () { redrawRow(id).always(function () { showReceipts(id); }); });
		};
		if (typeof global.uiConfirm === 'function') global.uiConfirm({ title: tr('ui.js.expReceiptRemoveTitle', 'Remove this receipt?'),
			message: tr('ui.js.expReceiptRemoveMsg', 'It is taken off the expense; the file is kept for the audit trail.'), tone: 'danger' })
			.then(function (ok) { if (ok) go(); });
		else go();
	});
	$(document).on('click', '#tableExpense [data-cy="bill-payments"]', function () {
		var id = $(this).attr('data-id');
		var $open = $('#tableExpense tbody tr.exp-pay-detail[data-for="' + id + '"]');
		if ($open.length) { $open.remove(); $(this).attr('aria-expanded', 'false'); return; }
		showBillPayments(id);
	});
	$(document).on('click', '#tableExpense [data-cy="reverse-payment"]', function () { reversePayment(this); });
	$(document).on('click', '#tableExpense [data-cy="pay-bill"]', function () {
		expensePayOpen($(this).attr('data-id'), $(this).attr('data-no'), $(this).attr('data-open'));
	});
	$(document).on('click', '#tableExpense [data-cy="pay-claim"]', function () {
		expensePayOpen($(this).attr('data-id'), $(this).attr('data-no'), $(this).attr('data-open'), true, $(this).attr('data-user'));
	});
	// EX-1b — only the pressed button shows it is working (§0c); the row is redrawn from the server's answer.
	$(document).on('click', '#tableExpense [data-cy="post-again"]', function () {
		var $b = $(this), id = $b.attr('data-id'), label = $b.html();
		$b.prop('disabled', true).text(tr('ui.js.expSendingAgain', 'Sending…'));
		$.ajax({ url: ctx() + 'expense/vouchers/' + encodeURIComponent(id) + '/post-again', type: 'POST', dataType: 'json' })
			.done(function (res) {
				if (!res || res.success !== true) {
					$b.prop('disabled', false).html(label);
					msg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad');
					return;
				}
				var $tr = $b.closest('tr');
				if (res.data) $tr.replaceWith(row(res.data));
				msg(tr('ui.js.expSentAgain', 'Sent to the books again.'), 'ok');
				watch(id, 15);
			})
			.fail(function (xhr) {
				$b.prop('disabled', false).html(label);
				msg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
					: tr('ui.js.saveFailed', 'Save failed'), 'bad');
			});
	});

	// ── EX-6 — deciding a claim ───────────────────────────────────────────────────────────────────────────────
	// Only the pressed button shows it is working (§0c); the row is redrawn from the server's answer, and an approved
	// claim is then watched like any expense until the books answer.
	function claimAction(btn, action, body) {
		var $b = $(btn), id = $b.attr('data-id'), label = $b.html();
		$b.prop('disabled', true).text(tr('ui.js.expSaving', 'Saving…'));
		return $.ajax({ url: ctx() + 'expense/claims/' + encodeURIComponent(id) + '/' + action, type: 'POST', dataType: 'json',
			contentType: 'application/json', data: body ? JSON.stringify(body) : null })
			.done(function (res) {
				if (!res || res.success !== true) {
					$b.prop('disabled', false).html(label);
					msg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad');
					return;
				}
				msg(res.message || '', 'ok');
				expenseLoad(expPage);                     // an approved claim is POSTED/PENDING: the list watches it
			})
			.fail(function (xhr) {
				$b.prop('disabled', false).html(label);
				msg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
					: tr('ui.js.saveFailed', 'Save failed'), 'bad');
			});
	}
	$(document).on('click', '#tableExpense [data-cy="claim-approve"]', function () { claimAction(this, 'approve'); });
	$(document).on('click', '#tableExpense [data-cy="claim-withdraw"]', function () { claimAction(this, 'withdraw'); });
	$(document).on('click', '#tableExpense [data-cy="claim-reject"]', function () {
		var btn = this, ask = typeof global.uiPromptConfirm === 'function' ? global.uiPromptConfirm : null;
		if (!ask) return;
		ask({ title: tr('ui.js.claimRejectTitle', 'Reject this claim?'),
			input: { label: tr('ui.js.claimRejectReason', 'Why? The person who made the claim will see this.') }, tone: 'danger' })
			.then(function (reason) {
				if (reason === null) return;
				if (!String(reason).trim()) { uiAlertSafe(tr('ui.js.claimRejectReason', 'Why? The person who made the claim will see this.')); return; }
				claimAction(btn, 'reject', { reason: String(reason).trim() });
			});
	});
	$(document).on('capabilities:ready', function () { if ($('#ExpenseDiv').is(':visible')) applyClaimOption(); });

	$(document).on('click', '#tableExpense [data-cy="void-expense"]', function () {
		expenseVoid($(this).attr('data-id'), $(this).attr('data-no'));
	});

	global.showExpenses = showExpenses;
	global.expenseSave = expenseSave;
	global.expenseLoad = expenseLoad;
	global.expensePage = expensePage;
	global.expenseCategoriesToggle = expenseCategoriesToggle;
	global.expenseCategorySave = expenseCategorySave;
	global.expenseCategoryAdd = expenseCategoryAdd;
	global.expenseSettingsToggle = expenseSettingsToggle;
	global.expenseSettingSaveBackdate = function (btn) { expenseSettingSave(btn, KEY_BACK, String($('#expSetBackdate').val() || '').trim()); };
	global.expenseSettingSavePaidFrom = function (btn) { expenseSettingSave(btn, KEY_PAID, $('#expSetPaidFrom').val()); };
	global.expenseSettingSaveReceiptAbove = function (btn) { expenseSettingSave(btn, KEY_RCPT, String($('#expSetReceiptAbove').val() || '0').trim()); };
	global.expensePay = expensePay;
	global.expensePayClose = expensePayClose;
	global.expenseClaimsFilter = expenseClaimsFilter;
	global.expenseAdvancesToggle = expenseAdvancesToggle;
	global.expenseReportToggle = expenseReportToggle;
	global.expenseReportRun = expenseReportRun;
	global.expenseAdvanceGive = expenseAdvanceGive;
})(window);
