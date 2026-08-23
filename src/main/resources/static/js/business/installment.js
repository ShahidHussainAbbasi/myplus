/* ============================================================================
 * installment.js — INST-1. Selling a handset on terms, at the counter.
 *
 * Its own file rather than more of business.js, for the reason order-booking.js
 * is its own file: this is a distinct surface with a distinct audience. A shop
 * that never finances anything loads it and it does nothing.
 *
 * WHAT IT DOES
 *   1. Shows the panel only when the tenant turned on pos.installment.enabled.
 *   2. Previews the schedule BEFORE the sale commits, from the server.
 *   3. Contributes the `installmentPlan` block to the sale payload.
 *
 * WHY THE PREVIEW IS A SERVER CALL AND NOT ARITHMETIC HERE.
 * The schedule has three rules a second implementation always gets wrong: the
 * parts must sum to the financed amount EXACTLY with the residual on the last
 * row; the split rounds DOWN, not HALF_UP (17.70 over 60 under HALF_UP makes the
 * final payment 0.00); and monthly dates are measured from the anchor, never
 * stepped. Recomputing any of that in the browser would give the customer one
 * set of numbers and store another — and the difference would surface months
 * later, on a receipt, in front of them.
 *
 * So the preview calls the SAME generator the commit calls. That is the whole
 * point of the endpoint existing.
 * ========================================================================== */
(function (global) {
	'use strict';

	var $ = global.jQuery;

	function ctx() { return global.serverContext || '/'; }

	function tr(key, fallback) {
		return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
			? global.t(key) : fallback;
	}

	function esc(v) {
		return (typeof global.escHtml === 'function') ? global.escHtml(v == null ? '' : String(v))
			: String(v == null ? '' : v);
	}

	/**
	 * True when the shop has switched installments on.
	 *
	 * Reads {@code window.posInstallmentEnabled}, which business.js sets from the same settings call that
	 * already populates every other pos.* flag. Deliberately NOT a settings fetch of its own: a second read
	 * is a second answer, and the sale screen would then have two opinions about whether this feature exists.
	 */
	function enabled() {
		return global.posInstallmentEnabled === true;
	}

	/**
	 * The cart's total, which is what a plan finances.
	 *
	 * Read the SAME way {@code calculateChange()} reads it — {@code $('#sellTotal')[0].innerHTML} — because
	 * {@code #sellTotal} is a {@code <th>}, the cart grid's TOTAL-column footer, not an input. Calling
	 * {@code .val()} on it returns undefined, which resolved to a price of 0 and made this function return
	 * before it ever asked the server for a schedule: the panel simply did nothing, with no error anywhere.
	 *
	 * <p>Re-summing the cart lines here would be worse still — two places computing one total is how a plan
	 * comes to finance an amount the invoice does not carry.
	 */
	function cartTotal() {
		var el = $('#sellTotal')[0];
		if (!el) return 0;
		return Number(String(el.innerHTML).replace(/[^0-9.\-]/g, '')) || 0;
	}

	/** Show or hide the whole panel — called when the sale section opens. */
	global.applyInstallmentVisibility = function () {
		var on = enabled();
		$('#sellInstallmentWrap').toggle(!!on);
		if (!on) {
			// Leave no half-state behind: a shop that switches the feature off mid-session must not keep a
			// ticked box that would send a plan block on the next sale.
			$('#sellOnInstallment').prop('checked', false);
			$('#sellInstallmentFields').hide();
		}
	};

	global.toggleInstallmentPanel = function () {
		var on = $('#sellOnInstallment').is(':checked');
		$('#sellInstallmentFields').toggle(on);
		if (on) {
			// Seed from the tenant's defaults so the common case needs no typing.
			// Seeded from the same window.pos* globals business.js already sets — one settings read, one answer.
			if (!$('#instCount').val()) $('#instCount').val(global.posInstallmentCount || 6);
			if (!$('#instFrequency').val()) $('#instFrequency').val(global.posInstallmentFrequency || 'monthly');
			if (!$('#instFirstDueDate').val()) {
				// Seed BOTH: the hidden field carries the ISO value the server parses, the visible box shows
				// the shop's dd-MM-yyyy. Writing only one leaves the cashier looking at an empty calendar
				// while a date is silently in flight, or the reverse.
				var iso = defaultFirstDue();
				$('#instFirstDueDate').val(iso);
				$('#instFirstDueDateText').val(iso.substring(8, 10) + '-' + iso.substring(5, 7) + '-' + iso.substring(0, 4));
			}
			previewInstallmentSchedule();
		} else {
			$('#instSchedulePreview').empty();
		}
	};

	/**
	 * One month from today, in yyyy-MM-dd.
	 *
	 * Built from LOCAL date components. `toISOString()` is UTC, and at +05:00 that resolves to YESTERDAY for
	 * any sale rung up before 05:00 — the trap O4's gate hit when the suite first ran at 01:35.
	 */
	function defaultFirstDue() {
		var d = new Date();
		d.setMonth(d.getMonth() + 1);
		return d.getFullYear() + '-'
			+ String(d.getMonth() + 1).padStart(2, '0') + '-'
			+ String(d.getDate()).padStart(2, '0');
	}

	/** Ask the server what the customer would owe, and show it. */
	/**
	 * How much of this sale is NOT payable at the counter today.
	 *
	 * The checkout already has a name for money that is part of the bill but not collected here: the
	 * insured portion. A financed amount is the same kind of thing, so calculateChange() subtracts it the
	 * same way and "due now" becomes the down payment — without anyone inventing a new concept.
	 *
	 * Zero whenever there is no live plan, so an ordinary sale is untouched.
	 */
	global.posFinancedAmount = function () {
		if (!$('#sellOnInstallment').is(':checked')) return 0;
		var price = cartTotal();
		var down = Number($('#instDownPayment').val()) || 0;
		var financed = price - down;
		return financed > 0 ? financed : 0;
	};

	/**
	 * The deposit is MONEY, so it has to reach the till.
	 *
	 * Down payment and Amount Received were two independent fields for one number: the plan read its own,
	 * the invoice read its own, and nothing compared them. Type one and forget the other and the schedule
	 * and the invoice described different debts — 5,000 nobody could collect, or 5,000 billed twice.
	 *
	 * Mirroring is ONE-WAY and only while the down payment is being typed. The cashier can still overwrite
	 * Amount Received afterwards: a customer handing 5,000 for a 4,700 deposit gets 300 back, and a mirror
	 * that fought that edit would break the till to protect an invariant that does not need it.
	 */
	function mirrorDownPaymentToTill() {
		if (!$('#sellOnInstallment').is(':checked')) return;
		var down = Number($('#instDownPayment').val()) || 0;
		$('#sellRec').val(down > 0 ? down : '');
		if (typeof global.calculateChange === 'function') global.calculateChange();
	}

	$(document).on('input change', '#instDownPayment', mirrorDownPaymentToTill);
	// Ticking or clearing the plan changes what is payable today, so the till has to be told either way.
	$(document).on('change', '#sellOnInstallment', function () {
		if ($(this).is(':checked')) mirrorDownPaymentToTill();
		else if (typeof global.calculateChange === 'function') global.calculateChange();
	});

	global.previewInstallmentSchedule = function () {
		if (!$('#sellOnInstallment').is(':checked')) return;

		var price = cartTotal();
		var count = Number($('#instCount').val()) || 0;
		var firstDue = $('#instFirstDueDate').val();
		if (price <= 0 || count < 1 || !firstDue) { $('#instSchedulePreview').empty(); return; }

		$.get(ctx() + 'installmentPreview', {
			cashPrice: price,
			downPayment: Number($('#instDownPayment').val()) || 0,
			installmentCount: count,
			frequency: $('#instFrequency').val() || 'monthly',
			firstDueDate: firstDue
		}).done(function (resp) {
			if (!resp || resp.status !== 'SUCCESS') {
				// The server's refusal is shown verbatim: it names what the cashier has to change
				// ("The down payment cannot be more than the price"), which a generic message would not.
				$('#instSchedulePreview').html('<span style="color:#b91c1c">'
					+ esc((resp && resp.message) || tr('ui.js.instCannotPreview', 'That plan cannot be built.'))
					+ '</span>');
				return;
			}
			renderPreview(resp.collection || [], resp.message);
		}).fail(function () {
			$('#instSchedulePreview').html('<span style="color:#b91c1c">'
				+ esc(tr('ui.js.instCannotPreview', 'That plan cannot be built.')) + '</span>');
		});
	};

	/**
	 * The SALE-SCREEN preview table.
	 *
	 * <p>Named for what it renders, not just "render". This file carries two renderers — the preview and the
	 * worklist — and when both were called {@code render} the later declaration silently replaced the
	 * earlier one: the preview then called the worklist's renderer, wrote nothing into
	 * {@code #instSchedulePreview}, and reported no error at all. Three of five gate cases stayed green
	 * because they never reached it.
	 *
	 * <p>A duplicate function name in one scope is not a syntax error in JavaScript; it is a silent
	 * overwrite. Hoisting means the LAST one wins regardless of call order.
	 */
	function renderPreview(rows, financed) {
		if (!rows.length) { $('#instSchedulePreview').empty(); return; }

		var html = '<strong>' + esc(tr('ui.js.instFinanced', 'To pay over time')) + ': '
			+ esc(financed) + '</strong>'
			+ '<table class="table table-condensed" id="instScheduleTable" style="margin-top:6px;max-width:520px">'
			+ '<thead><tr><th>#</th><th>' + esc(tr('ui.js.instDue', 'Due')) + '</th>'
			+ '<th class="text-right">' + esc(tr('ui.js.instAmount', 'Amount')) + '</th></tr></thead><tbody>';
		rows.forEach(function (r) {
			html += '<tr><td>' + esc(r.seqNo) + '</td><td>' + esc(r.dueDate)
				+ '</td><td class="text-right">' + esc(r.amount) + '</td></tr>';
		});
		$('#instSchedulePreview').html(html + '</tbody></table>');
	}

	/**
	 * The plan block for the sale payload, or null when this is an ordinary sale.
	 *
	 * ⚠ Called by main.js when it assembles `customerHistory`. The block must also exist on BOTH
	 * CustomerHistoryDTOs — the monolith binds the DTO and re-serialises it onward, so a field declared on
	 * one side only is dropped in transit and the plan silently never exists (design note F2).
	 */
	global.installmentPlanForSale = function () {
		if (!enabled() || !$('#sellOnInstallment').is(':checked')) return null;

		var count = Number($('#instCount').val()) || 0;
		var firstDue = $('#instFirstDueDate').val();
		if (count < 1 || !firstDue) return null;

		return {
			cashPrice: cartTotal(),
			downPayment: Number($('#instDownPayment').val()) || 0,
			installmentCount: count,
			frequency: $('#instFrequency').val() || 'monthly',
			firstDueDate: firstDue,
			assetRef: $('#instAssetRef').val() || null
		};
	};

	/** Clear the panel after a completed sale, so the next customer does not inherit these terms. */
	global.resetInstallmentPanel = function () {
		$('#sellOnInstallment').prop('checked', false);
		$('#sellInstallmentFields').hide();
		$('#instDownPayment, #instAssetRef, #instFirstDueDate, #instFirstDueDateText').val('');
		$('#instSchedulePreview').empty();
	};

	// ── the Installments screen (INST-2, requirement R2: "know the dues") ───────────────────────────────

	/**
	 * Open the screen and load the plans.
	 *
	 * <p>Same shape as {@code showQuotes()}: hide every panel, show this one, fetch. Read-only by design —
	 * money moves through the receipt path the counter already uses, so this screen cannot become a second
	 * way to collect, and there is no second place for the two to disagree.
	 */
	global.showInstallments = function () {
		$('.formDiv').hide();
		$('#InstallmentDiv').show();
		$('#installmentSchedule').empty();
		// Always open on the plans tab. Without this the screen reopens on whichever view was last used,
		// which for a screen reached from a menu reads as the menu entry having changed meaning.
		global.showInstallmentTab('plans');
	};

	/** The plans view's load. Extracted so the tab switch can re-run it without duplicating the fetch. */
	function loadPlans() {
		$.get(ctx() + 'installmentPlansOpen').done(function (resp) {
			renderWorklist(resp && resp.collection ? resp.collection : []);
		}).fail(function () {
			$('#installmentBody').empty();
			$('#installmentEmpty').show()
				.text(tr('ui.js.instCouldNotLoad', 'Could not load installment plans.'));
		});
	}

	/**
	 * The INSTALLMENTS SCREEN's list. See renderPreview above on why neither is called `render`.
	 *
	 * The grid is a DataTable so it gets the search box, paging and exports every other grid in this app
	 * already has — the same `lazyExcelButton`/`lazyPdfButton` helpers `loadDataTable()` uses, which pull
	 * pdfmake and JSZip on FIRST CLICK rather than on page load (PERF-4b: they are ~900KB gzipped, most of
	 * what is left in the bundle, and most sessions never export anything).
	 */
	function renderWorklist(plans) {
		// Tear the old instance down BEFORE emptying the tbody. DataTables holds references to the rows it
		// manages; emptying underneath a live instance leaves it describing a table that no longer exists,
		// and the next draw throws from inside the library where the cause is unreadable.
		destroyWorklistTable();

		var $b = $('#installmentBody').empty();
		// DataTables owns the empty state now ("No data available in table"), exactly as it does on every
		// other grid here. #installmentEmpty stays in the DOM but is only ever used for a LOAD FAILURE —
		// two different messages for "nothing to show" and "could not fetch" is the whole point of keeping it.
		$('#installmentEmpty').hide();

		plans.forEach(function (p) {
			var next = nextDue(p);
			// The server already computed overdueCount against the tenant's today. Recomputing it here would
			// be a second opinion about what "late" means, and the screen and the reminder must agree.
			var late = Number(p.overdueCount) || 0;

			var tr$ = $('<tr>').css('cursor', 'pointer')
				.attr('data-plan', p.id)
				.on('click', function () { showSchedule(p); });

			tr$.append($('<td>').text(p.planNo || ''));
			tr$.append($('<td>').text(p.customerName || ''));
			tr$.append($('<td>').text(p.invoiceNo || ''));
			tr$.append($('<td>').text(p.assetRef || ''));
			tr$.append($('<td>').addClass('text-right').text(money(p.financedAmount)));
			tr$.append($('<td>').addClass('text-right').text(money(p.totalPaid)));
			tr$.append($('<td>').addClass('text-right').text(money(p.totalOutstanding)));
			tr$.append($('<td>').text(next ? next.dueDate : ''));
			// Red only when something is actually late — a badge on every row teaches the eye to ignore it.
			tr$.append($('<td>').css('color', late > 0 ? '#b91c1c' : '').text(late > 0 ? late : ''));
			tr$.append($('<td>').text(p.status || ''));
			$b.append(tr$);
		});

		initWorklistTable();
	}

	/** The live DataTable for #tableInstallment, or null. */
	var worklistTable = null;

	function destroyWorklistTable() {
		if (worklistTable) {
			try { worklistTable.destroy(); } catch (e) { /* already gone */ }
			worklistTable = null;
		}
	}

	/**
	 * Search, paging and exports on the plans grid.
	 *
	 * <p>ORDERING IS DELIBERATELY LEFT TO THE SERVER. `/installmentPlansOpen` returns most-overdue-first,
	 * which is what a worklist is for; re-sorting here by plan number would put the newest plan on top and
	 * bury the customer who has owed money longest. `order: []` tells DataTables to leave the order alone
	 * rather than silently applying its default of column 0 ascending.
	 */
	function initWorklistTable() {
		// Initialised even with no rows, so the search box, the length menu and the export buttons are
		// always present. A grid whose controls appear and disappear with its contents is one a shopkeeper
		// cannot learn — and it would force every test that looks for a plan to branch on whether the
		// controls happen to exist.
		worklistTable = $('#tableInstallment').DataTable({
			lengthMenu: [[10, 25, 50, 100, -1], ['10', '25', '50', '100', tr('ui.js.all', 'All')]],
			pageLength: 25,
			order: [],
			autoWidth: true,
			dom: 'Bfrtip',
			buttons: [
				'pageLength',
				lazyExcelButton({ title: tr('ui.js.instScreenTitle', 'Installment plans') }),
				{ extend: 'print', title: tr('ui.js.instScreenTitle', 'Installment plans') },
				lazyPdfButton({
					title: tr('ui.js.instScreenTitle', 'Installment plans'),
					orientation: 'landscape',
					pageSize: 'LEGAL'
				})
			]
		});
	}

	/** The first installment still owing — what the shop chases next. */
	function nextDue(plan) {
		var rows = plan.installments || [];
		for (var i = 0; i < rows.length; i++) {
			if (Number(rows[i].outstanding) > 0) return rows[i];
		}
		return null;
	}

	function money(v) {
		return v == null ? '' : Number(v).toFixed(2);
	}

	/** The chosen plan's schedule, under the list. Text only — no editing: this screen does not move money. */
	function showSchedule(plan) {
		var html = '<h4 style="margin-top:18px">' + esc(plan.planNo || '') + ' — '
			+ esc(plan.customerName || '') + '</h4>'
			+ '<table class="table table-condensed" id="installmentScheduleTable" style="max-width:640px">'
			+ '<thead><tr><th>#</th><th>' + esc(tr('ui.js.instDue', 'Due')) + '</th>'
			+ '<th class="text-right">' + esc(tr('ui.js.instAmount', 'Amount')) + '</th>'
			+ '<th class="text-right">' + esc(tr('ui.js.instPaid', 'Paid')) + '</th>'
			+ '<th class="text-right">' + esc(tr('ui.js.instOutstanding', 'Remaining')) + '</th>'
			+ '<th>' + esc(tr('ui.status', 'Status')) + '</th></tr></thead><tbody>';

		(plan.installments || []).forEach(function (i) {
			// `overdue` is computed by the server from the same predicate the reminder scanner uses, so a
			// row shown as late here and a reminder sent for it can never disagree.
			html += '<tr' + (i.overdue ? ' style="color:#b91c1c"' : '') + '>'
				+ '<td>' + esc(i.seqNo) + '</td>'
				+ '<td>' + esc(i.dueDate) + '</td>'
				+ '<td class="text-right">' + esc(money(i.amount)) + '</td>'
				+ '<td class="text-right">' + esc(money(i.paidAmount)) + '</td>'
				+ '<td class="text-right">' + esc(money(i.outstanding)) + '</td>'
				+ '<td>' + esc(i.status) + (i.overdue ? ' (' + esc(i.daysOverdue) + 'd)' : '') + '</td></tr>';
		});
		// INST-5a — the IMEI and the repossess action live WITH the schedule, because that is the screen a
		// shopkeeper is already on when they decide to take a handset back.
		var foot = '';
		if (plan.assetRef) {
			foot += '<p class="text-muted" style="margin-top:6px">'
				+ esc(tr('ui.js.instAssetRef', 'IMEI / serial')) + ': <b>' + esc(plan.assetRef) + '</b></p>';
		}
		if (plan.status === 'ACTIVE' || plan.status === 'DEFAULTED') {
			// The condition is asked HERE, on the screen, rather than assumed: it decides whether the unit
			// goes back into sellable stock, and a default of "good" would eventually put a smashed handset
			// on the shelf with nothing downstream questioning it.
			foot += '<div class="form-inline" style="margin-top:8px">'
				+ '<select id="instRepossessCondition" class="form-control input-sm" style="margin-right:8px">'
				+ '<option value="GOOD">' + esc(tr('ui.js.instCondGood', 'Resaleable — put back in stock'))
				+ '</option>'
				+ '<option value="DAMAGED">' + esc(tr('ui.js.instCondDamaged', 'Damaged — do not restock'))
				+ '</option></select>'
				+ '<button type="button" id="instRepossess" class="btn btn-danger btn-sm">'
				+ esc(tr('ui.js.instRepossess', 'Repossess')) + '</button></div>';
		}
		$('#installmentSchedule').html(html + '</tbody></table>' + foot);
		$('#instRepossess').off('click').on('click', function () { repossess(plan); });
	}

	/**
	 * INST-5a — take the item back.
	 *
	 * Deliberately a two-part confirmation: the CONDITION decides whether a handset goes back on the shelf,
	 * and it is asked per repossession rather than configured per shop, because it is a fact about this one
	 * item. A tenant-level "always restock" setting would eventually put a smashed phone back into sellable
	 * stock, and nothing downstream would question it.
	 */
	function repossess(plan) {
		// Read at CLICK time, not at render time — the shopkeeper picks the condition after the panel is drawn.
		var condition = $('#instRepossessCondition').val() === 'DAMAGED' ? 'DAMAGED' : 'GOOD';
		global.uiPromptConfirm({
			title: tr('ui.js.instRepossessTitle', 'Repossess this item?'),
			message: tr('ui.js.instRepossessWarn',
				'The unpaid balance is written off and the plan is closed. Money already paid is NOT refunded.')
				+ ' — ' + esc(plan.planNo || ''),
			input: {
				label: tr('ui.js.instRepossessReason', 'Reason'),
				placeholder: tr('ui.js.instRepossessWhy', 'e.g. six payments missed'),
				maxlength: 255
			},
			confirmText: tr('ui.js.instRepossess', 'Repossess'),
			tone: 'danger'
		}).then(function (reason) {
			if (reason === null) return;   // dismissed — this one is destructive, so silence means no
			$.post(ctx() + 'repossessPlan',
				{ planId: plan.id, condition: condition, reason: reason || '' })
				.done(function (resp) {
					if (resp && resp.status === 'SUCCESS') {
						global.uiAlert({ title: tr('ui.js.instRepossessed', 'Repossessed'),
							message: resp.message || '' });
						global.showInstallments();
					} else {
						// The server's OWN words: "goods are protected at 66%" tells the shopkeeper why, which
						// a generic failure never could.
						global.uiAlert({ title: tr('ui.js.instRepossessFailed', 'Not repossessed'),
							message: (resp && resp.message) || '', tone: 'danger' });
					}
				})
				.fail(function () {
					global.uiAlert({ title: tr('ui.js.instRepossessFailed', 'Not repossessed'),
						message: tr('ui.js.instCouldNotLoad', 'Could not complete that.'), tone: 'danger' });
				});
		});
	}

	// The cart total changes as lines are added and the schedule is a function of it, so the preview must
	// follow. Bound to the total FIELD rather than to a bespoke event: calculateNetSell() writes #sellTotal
	// on every recalculation, and hooking the value avoids adding a publish call to a path this slice has no
	// other reason to touch.
	$(document).on('change keyup', '#sellTotal', function () { previewInstallmentSchedule(); });

	// ── the collections worklist (INST-3a, requirement R4: "remind") ────────────────────────────────────
	//
	// NAMING: renderPreview / renderWorklist / renderCollections are three DELIBERATELY different names in
	// one file. Declaring `function render` twice here once silently overwrote the first — JavaScript does
	// not warn, hoisting means the last declaration wins regardless of call order, and the sale-screen
	// preview quietly stopped rendering while the new feature's own gate stayed green. Only re-running the
	// OLD spec found it.

	/**
	 * Switch between the two views of the same plans.
	 *
	 * Going BACK to the plans view re-runs the load rather than just un-hiding the table. #installmentEmpty
	 * is a conditional message, not part of the view: blanket-toggling it visible would announce "no
	 * installment plans yet" over a table full of them.
	 */
	global.showInstallmentTab = function (which) {
		var chasing = which === 'collections';
		// The WRAPPER, not the table. DataTables moves #tableInstallment inside a generated
		// #tableInstallment_wrapper that also holds the search box, the length menu and the export buttons —
		// toggling the table alone would hide the rows and leave its controls floating over the Collections
		// view, still filtering a grid nobody can see. Falls back to the bare table before the first render.
		var $grid = $('#tableInstallment_wrapper');
		if (!$grid.length) $grid = $('#tableInstallment');
		$grid.toggle(!chasing);
		$('#installmentSchedule').toggle(!chasing);
		$('#InstallmentCollections').toggle(chasing);
		$('#instTabPlans').toggleClass('btn-primary', !chasing).toggleClass('btn-default', chasing);
		$('#instTabCollections').toggleClass('btn-primary', chasing).toggleClass('btn-default', !chasing);

		if (chasing) {
			$('#installmentEmpty').hide();
			loadCollections();
		} else {
			loadPlans();
		}
	};

	/**
	 * Load the worklist.
	 *
	 * Scans first, then reads. The scan is idempotent — installment_reminder.dedupe_key is UNIQUE — so
	 * opening the screen five times produces the same list rather than five copies of it, and the shopkeeper
	 * never sees a list that is stale because the timer has not come round yet.
	 */
	function loadCollections() {
		var stage = $('#instChaseStage').val() || '';
		$.post(ctx() + 'scanInstallmentReminders').always(function () {
			$.get(ctx() + 'installmentReminders', { stage: stage }).done(function (resp) {
				renderCollections(resp && resp.collection ? resp.collection : []);
			}).fail(function () {
				$('#instChaseBody').empty();
				$('#instChaseEmpty').show()
					.text(tr('ui.js.instCouldNotLoad', 'Could not load the list.'));
			});
		});
	}

	function renderCollections(rows) {
		var $b = $('#instChaseBody').empty();
		$('#instChaseEmpty').toggle(rows.length === 0)
			.text(tr('ui.js.instNothingToChase', 'Nobody to chase today.'));

		rows.forEach(function (r) {
			var late = r.stage === 'OVERDUE';
			var tr$ = $('<tr>');
			// Grey out a row already dealt with rather than hiding it: the shopkeeper needs to see that the
			// call was made, which is the entire reason this is a record and not a derived query.
			if (r.actioned) tr$.css({ color: '#999', 'font-style': 'italic' });

			tr$.append($('<td>').text(r.customerName || ''));
			tr$.append($('<td>').text(r.contact || ''));
			tr$.append($('<td>').text((r.planNo || '') + (r.seqNo ? '/' + r.seqNo : '')));
			tr$.append($('<td>').css('color', late && !r.actioned ? '#b91c1c' : '').text(r.dueDate || ''));
			tr$.append($('<td>').addClass('text-right').text(money(r.amountDue)));
			tr$.append($('<td>').text(late
				? tr('ui.js.instStageOverdue', 'Late') + ' (' + esc(r.daysOverdue) + 'd)'
				: tr('ui.js.instStageDueSoon', 'Due soon')));
			tr$.append($('<td>').text(r.actedAt ? String(r.actedAt).replace('T', ' ').slice(0, 16) : ''));
			tr$.append($('<td>').text(r.outcome || ''));

			var $act = $('<td>');
			$('<button type="button" class="btn btn-xs btn-default">')
				.text(r.actioned ? tr('ui.js.instChaseAgain', 'Update') : tr('ui.js.instChaseMark', 'Rang'))
				.on('click', function () { chase(r); })
				.appendTo($act);
			tr$.append($act);

			$b.append(tr$);
		});
	}

	/**
	 * Record the outcome of a call.
	 *
	 * uiPromptConfirm, never window.prompt — the platform confirm contract. It takes an options object and
	 * returns a PROMISE (no callback), and CANCEL resolves to null while an empty box resolves to '' — so
	 * the two must be distinguished or dismissing the dialog would record a call that never happened.
	 */
	function chase(r) {
		global.uiPromptConfirm({
			title: tr('ui.js.instChaseTitle', 'Record the call'),
			message: (r.customerName || '') + ' — ' + (r.contact || ''),
			input: {
				label: tr('ui.js.instChaseOutcome', 'Outcome'),
				placeholder: tr('ui.js.instChasePlaceholder', 'e.g. promised Friday'),
				maxlength: 255
			},
			confirmText: tr('ui.js.instChaseSave', 'Save')
		}).then(function (note) {
			if (note === null) return;   // dismissed — recording a call here would be recording a fiction
			$.post(ctx() + 'installmentReminderAction', { id: r.id, outcome: 'CALLED', note: note || '' })
				.done(function (resp) {
					if (resp && resp.status === 'SUCCESS') loadCollections();
					else global.uiAlert({ title: tr('ui.js.instChaseFailed', 'Not saved'),
						message: (resp && resp.message) || 'That could not be saved.', tone: 'danger' });
				})
				.fail(function () {
					global.uiAlert({ title: tr('ui.js.instChaseFailed', 'Not saved'),
						message: 'That could not be saved.', tone: 'danger' });
				});
		});
	}

	$(document).on('change', '#instChaseStage', function () { loadCollections(); });
	$(document).on('click', '#instRefreshChase', function () { loadCollections(); });

})(window);
