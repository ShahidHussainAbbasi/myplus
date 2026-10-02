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

	var canVoid = false;   // set from the table header: the Actions column is rendered only for owner/admin

	function row(v) {
		// EX-2b — a tagged line reads "Fuel and transport · Bus (LEA-123)": the category, then what it was for.
		var cats = (v.lines || []).map(function (l) {
			return l.categoryName ? (l.categoryName + (l.tagLabel ? ' · ' + l.tagLabel : '')) : null;
		}).filter(Boolean).join(', ');
		var tds = '<td>' + esc(v.voucherNo || '') + '</td>'
			+ '<td>' + showDate(v.voucherDate) + '</td>'
			+ '<td>' + esc(cats) + '</td>'
			+ '<td>' + esc(v.paidFrom === 'BANK' ? tr('ui.js.expBank', 'Bank') : tr('ui.js.expCash', 'Cash')) + '</td>'
			+ '<td>' + esc(v.payeeName || '') + '</td>'
			+ '<td style="text-align:right;font-variant-numeric:tabular-nums">' + esc(money(v.total)) + '</td>'
			+ '<td class="exp-chip">' + chip(v) + '</td>';
		if (canVoid) {
			var voidable = v.status === 'POSTED' && v.postingStatus !== 'PENDING';
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

		// One key per FORM FILL: a retry of the same fill replays the first save on the server.
		if (!$f.data('idemKey')) $f.data('idemKey', newKey());
		var body = {
			voucherDate: $('#expDate').val() || isoOf(new Date()),
			paidFrom: $('#expPaidFrom').val(),
			payeeName: $('#expPayee').val(),
			note: $('#expNote').val(),
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
		loadCategories().always(function () { loadTags(); expenseLoad(); });
	}

	$(document).on('click', '#tableExpense [data-cy="void-expense"]', function () {
		expenseVoid($(this).attr('data-id'), $(this).attr('data-no'));
	});

	global.showExpenses = showExpenses;
	global.expenseSave = expenseSave;
	global.expenseLoad = expenseLoad;
})(window);
