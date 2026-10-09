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
		if (v.paidFrom !== 'AP' || v.status !== 'POSTED') return '';
		var open = Number(v.openAmount || 0);
		if (open <= 0) return ' <span class="label label-info" data-cy="expense-bill-paid">' + esc(tr('ui.js.expPaid', 'Paid')) + '</span>';
		var out = ' <span class="text-warning" data-cy="expense-bill-owes" style="font-variant-numeric:tabular-nums">'
			+ esc(tr('ui.js.expOwes', 'Owes')) + ' ' + esc(money(open)) + '</span>';
		if (v.postingStatus === 'POSTED_GL') {
			out += ' <button type="button" class="btn btn-xs btn-primary" data-cy="pay-bill" data-id="' + esc(v.id)
				+ '" data-no="' + esc(v.voucherNo || '') + '" data-open="' + esc(open) + '">' + esc(tr('ui.pay', 'Pay')) + '</button>';
		}
		return out;
	}   // set from the table header: the Actions column is rendered only for owner/admin

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
				: v.paidFrom === 'AP' ? tr('ui.js.expBill', 'Bill') : tr('ui.js.expCash', 'Cash')) + '</td>'
			+ '<td>' + esc(v.payeeName || '') + '</td>'
			+ '<td style="text-align:right;font-variant-numeric:tabular-nums">' + esc(money(v.total)) + '</td>'
			+ '<td class="exp-chip">' + chip(v) + billState(v) + refusal(v) + '</td>';
		if (canVoid) {
			// EX-3 — a till pay-out is corrected at the till (the server refuses its void), so no button here.
			// FP-3 — a bill with payments is voided only after its payments are reversed (the server refuses it too).
			var voidable = v.status === 'POSTED' && v.postingStatus !== 'PENDING' && v.source !== 'DRAWER'
				&& !(v.paidFrom === 'AP' && Number(v.paidAmount || 0) > 0);
			tds += '<td>' + (voidable
				? '<button type="button" class="btn btn-xs btn-default" data-cy="void-expense" data-id="' + esc(v.id)
					+ '" data-no="' + esc(v.voucherNo || '') + '">' + esc(tr('ui.js.expVoid', 'Void')) + '</button>'
				: '') + '</td>';
		}
		return '<tr data-id="' + esc(v.id) + '" class="expense-row' + (v.status === 'VOIDED' ? ' row-voided' : '') + '">' + tds + '</tr>';
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

	function toggleBill() { $('#expBillGroup').toggle($('#expPaidFrom').val() === 'AP'); }

	function expenseLoad() {
		var q = [];
		if ($('#expFrom').val()) q.push('from=' + encodeURIComponent($('#expFrom').val()));
		if ($('#expTo').val()) q.push('to=' + encodeURIComponent($('#expTo').val()));
		q.push('size=200');
		var $tb = $('#tableExpense tbody');
		return $.ajax({ url: ctx() + 'expense/vouchers?' + q.join('&'), dataType: 'json' })
			.done(function (res) {
				var rows = (res && res.data && res.data.content) || [];
				var cols = $('#tableExpense thead th').length;
				$tb.html(rows.length ? rows.map(row).join('')
					: '<tr><td colspan="' + cols + '" class="text-muted">' + esc(tr('ui.js.expNone', 'No expenses in this period.')) + '</td></tr>');
				// Anything still posting is re-read until the ledger answers — bounded, never a busy loop.
				rows.filter(function (v) { return v.status === 'POSTED' && v.postingStatus === 'PENDING'; })
					.forEach(function (v) { watch(v.id, 15); });
			})
			.fail(function (xhr) {
				$tb.html('<tr><td colspan="8" class="text-danger">'
					+ esc(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.expLoadFailed', 'Could not load expenses.'))
						: tr('ui.js.expLoadFailed', 'Could not load expenses.')) + '</td></tr>');
			});
	}

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
		var isBill = $('#expPaidFrom').val() === 'AP';
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
		$.ajax({
			url: ctx() + 'expense/vouchers?post=true', type: 'POST', contentType: 'application/json', dataType: 'json',
			headers: { 'Idempotency-Key': $f.data('idemKey') }, data: JSON.stringify(body)
		}).done(function (res) {
			if (!res || res.success !== true) {
				msg((res && res.message) || tr('ui.js.saveFailed', 'Save failed'), 'bad');
				return;
			}
			msg(tr('ui.js.expSaved', 'Expense saved. Posting to the books.'), 'ok');
			$f.removeData('idemKey');
			$('#expAmount, #expPayee, #expNote').val('');
			expenseLoad();
		}).fail(function (xhr) {
			// The key is KEPT: pressing Save again replays this fill rather than recording it twice.
			msg(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
				: tr('ui.js.saveFailed', 'Save failed'), 'bad');
		}).always(function () {
			$b.prop('disabled', false).html(label);
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
						expenseLoad();
					})
					.fail(function (xhr) {
						uiAlertSafe(typeof global.apiFailMessage === 'function' ? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed'))
							: tr('ui.js.saveFailed', 'Save failed'));
					});
			});
	}

	// ── FP-3: paying a bill ─────────────────────────────────────────────────────────────────────

	function expensePayOpen(id, no, open) {
		var $p = $('#expPayPanel');
		$p.data({ id: id, idemKey: newKey() });   // ONE key per opening of the panel
		$('#expPayTitle').text(tr('ui.js.expPayTitle', 'Pay bill') + ' ' + (no || '') + ' — '
			+ tr('ui.js.expOwes', 'Owes') + ' ' + money(open));
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
			msg(tr('ui.js.expPayDone', 'Payment recorded') + (res.data && res.data.receiptNo ? ' — ' + res.data.receiptNo : ''), 'ok');
			expenseLoad();
		}).fail(function (xhr) {
			// The key is KEPT: pressing Pay again is the same payment, never a second one.
			$('#expPayMsg').css('color', '#b3261e').text(typeof global.apiFailMessage === 'function'
				? global.apiFailMessage(xhr, tr('ui.js.saveFailed', 'Save failed')) : tr('ui.js.saveFailed', 'Save failed'));
		}).always(function () {
			$b.prop('disabled', false).html(label);
		});
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
		loadCategories().always(function () { loadTags(); loadSuppliers(); expenseLoad(); });
	}

	$(document).on('change', '#expPaidFrom', toggleBill);
	$(document).on('click', '#tableExpense [data-cy="pay-bill"]', function () {
		expensePayOpen($(this).attr('data-id'), $(this).attr('data-no'), $(this).attr('data-open'));
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

	$(document).on('click', '#tableExpense [data-cy="void-expense"]', function () {
		expenseVoid($(this).attr('data-id'), $(this).attr('data-no'));
	});

	global.showExpenses = showExpenses;
	global.expenseSave = expenseSave;
	global.expenseLoad = expenseLoad;
	global.expensePay = expensePay;
	global.expensePayClose = expensePayClose;
})(window);
