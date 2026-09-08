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
	/**
	 * SER-3b (fix) — the serial this plan finances, read from the CART.
	 *
	 * <h3>Why not #sellSerials, which is what this used to read</h3>
	 * That box belongs to the line-ENTRY row. Add-to-Cart pushes the line and then immediately calls the
	 * generic resetForm() (business.js, right after data.push) — which clicks .resetForm, fires the form's
	 * reset, and empties the box; the same clearing applySerialQuantityLock() exists to notice. So by the
	 * time the sale is submitted the box is blank, and the plan was built with assetRef = null on EVERY
	 * financed sale.
	 *
	 * The mechanism above is the evidence — it is checkable in source and stays true. The database only
	 * agrees weakly and is worth reading honestly: of 351 plans, 148 carry an empty asset_ref and the ten
	 * most recent are all empty, but NO serial was consumed against any of those invoices either, so they
	 * are equally consistent with nobody having typed one. Do not cite them as proof of this bug.
	 *
	 * Silent while `pos.installment.serialRequired` is off — the plan simply recorded no serial, which is
	 * the one thing INST-5a exists to record. Switch the setting on and the same gap becomes a refusal the
	 * cashier cannot satisfy: the number is asked for, typed, and then read from somewhere it no longer is.
	 *
	 * <h3>ONE serial, not a list</h3>
	 * assetRef is unique across live plans (V44's uq_plan_live_asset), so it must be a single value. A
	 * financed sale is a financed ASSET — the handset — and the first serial in the cart is it. A basket
	 * that also carries a tracked charger does not make the plan about the charger.
	 *
	 * The entry box remains the fallback, for the operator who types a serial and completes the sale without
	 * a separate Add-to-Cart step.
	 */
	function cartSerial() {
		var cart = global.data;
		if (cart && cart.length) {
			for (var i = 0; i < cart.length; i++) {
				var raw = cart[i] && cart[i].serials ? String(cart[i].serials) : '';
				// A line may carry several units; the plan names one. Split the way the server does.
				// SER-7: the SAME separators the server splits on — comma or any whitespace. Two different
				// ideas of where one serial ends would pick a different "first" one than the register does.
				var first = raw.split(/[,\s]+/).map(function (x) { return x.trim(); })
					.filter(function (x) { return x.length > 0; })[0];
				if (first) return first;
			}
		}
		return $.trim($('#sellSerials').val() || '') || null;
	}

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
			// Ask how many this shop requires, and draw that many. Zero draws nothing, which is the state
			// 40 of 43 tenants are in.
			loadGuarantorPolicy();
		} else {
			$('#instSchedulePreview').empty();
			$('#sellGuarantorRow').hide();
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
	// ── R4: guarantors ────────────────────────────────────────────────────────────────────────────
	//
	// HOW MANY BLOCKS APPEAR IS A TENANT SETTING, AND ITS DEFAULT IS ZERO.
	// 37 of 43 organisations have never chosen a business type and fall back to a preset that includes
	// installments. Rendering this unconditionally would put a two-block form in front of forty shops that
	// never asked for it — and a pharmacy selling a wheelchair on terms would be told to name guarantors it
	// has never heard of. So the count is fetched, and 0 means the panel does not exist.

	var guarantorsRequired = 0;
	var recentGuarantors = [];

	/** Ask the server how many this shop wants, then draw that many. Once per sale-screen open. */
	function loadGuarantorPolicy() {
		if (!enabled()) return;
		$.get(ctx() + 'guarantorsRequired').done(function (resp) {
			var payload = (resp && (resp.object || resp.data)) || {};
			guarantorsRequired = Number(payload.required || 0);
			renderGuarantorBlocks();
		}).fail(function () {
			// Unreadable means ask for none. A settings hiccup must not start refusing plans for a rule the
			// shop never set — the permissive direction is the one that leaves the counter trading.
			guarantorsRequired = 0;
			renderGuarantorBlocks();
		});
		$.get(ctx() + 'recentGuarantors').done(function (resp) {
			recentGuarantors = (resp && (resp.object || resp.data)) || [];
			renderRecentChips();
		});
	}

	/**
	 * The one-tap chips: people this shop has used before.
	 *
	 * The single biggest saving in the slice. Two guarantors is roughly eighty keystrokes mid-sale with a
	 * queue waiting, and in these shops a small circle of people guarantee most sales — a shopkeeper's
	 * brother-in-law stands behind twenty of them.
	 */
	function renderRecentChips() {
		var $box = $('#guarantorRecent');
		if (!$box.length || !guarantorsRequired || !recentGuarantors.length) { $box.empty(); return; }
		var html = '<span class="help-block" style="margin:0 6px 0 0;display:inline">'
			+ esc(tr('ui.js.guarantorUsedBefore', 'Used before at this shop:')) + '</span>';
		recentGuarantors.forEach(function (g, i) {
			html += '<button type="button" class="btn btn-default btn-xs js-guarantor-recall"'
				+ ' data-idx="' + i + '" style="margin:2px">'
				+ esc(g.name) + (g.cnic ? ' <small>' + esc(g.cnic) + '</small>' : '') + '</button>';
		});
		$box.html(html);
	}

	/** Draw exactly as many blocks as the shop requires. Zero blocks when it requires none. */
	function renderGuarantorBlocks() {
		var $row = $('#sellGuarantorRow');
		var $blocks = $('#guarantorBlocks');
		if (!$row.length) return;

		if (!guarantorsRequired) { $row.hide(); $blocks.empty(); $('#guarantorCount').text(''); return; }

		var html = '';
		for (var i = 0; i < guarantorsRequired; i++) {
			var n = i + 1;
			html += '<div class="panel panel-default js-guarantor-block" data-idx="' + i + '"'
				+ ' style="margin-bottom:8px">'
				+ '<div class="panel-body" style="padding:8px">'
				+ '<div class="row">'
				+ '<div class="col-xs-12"><strong>'
				+ esc(tr('ui.js.guarantorN', 'Guarantor')) + ' ' + n + '</strong></div>'
				// CNIC FIRST, because the card is in the cashier's hand and it is what recalls the person.
				+ '<div class="col-xs-12 col-sm-3"><span class="field-label">'
				+ esc(tr('ui.js.guarantorCnic', 'CNIC')) + '</span>'
				+ '<input type="text" class="form-control js-g-cnic" maxlength="32" autocomplete="off"></div>'
				+ '<div class="col-xs-12 col-sm-3"><span class="field-label">'
				+ esc(tr('ui.js.guarantorName', 'Name')) + '</span>'
				+ '<input type="text" class="form-control js-g-name" maxlength="255" autocomplete="off"></div>'
				+ '<div class="col-xs-12 col-sm-3"><span class="field-label">'
				+ esc(tr('ui.js.guarantorMobile', 'Mobile')) + '</span>'
				+ '<input type="text" class="form-control js-g-contact" maxlength="64" autocomplete="off"></div>'
				+ '<div class="col-xs-12 col-sm-3"><span class="field-label">'
				+ esc(tr('ui.js.guarantorAddress', 'Address')) + '</span>'
				+ '<input type="text" class="form-control js-g-address" maxlength="255" autocomplete="off"></div>'
				+ '<div class="col-xs-12"><span class="help-block js-g-msg" style="margin:2px 0"></span></div>'
				+ '</div></div></div>';
		}
		$blocks.html(html);
		$row.show();
		updateGuarantorCount();
	}

	/**
	 * "1 of 2 recorded" — the count is the feedback loop; a silent form is how a rule gets rediscovered.
	 *
	 * <p>R4b: this is now the ONLY thing that reports a shortfall, and it reports it continuously rather
	 * than as a refusal at submit. A shop that asked to be prompted for two sees that it has one, the whole
	 * time it is typing, and can still choose to sell.
	 */
	function updateGuarantorCount() {
		if (!guarantorsRequired) { $('#guarantorCount').text(''); $('#guarantorNote').text(''); return; }
		var have = collectGuarantors().length;
		$('#guarantorCount').text(
			(tr('ui.js.guarantorCount', '{0} of {1} recorded'))
				.replace('{0}', have).replace('{1}', guarantorsRequired));
		// The two slips that make a recorded guarantor worthless, shown while the form is being filled —
		// where they can still be corrected, rather than as a wall at submit.
		var note = typeof global.guarantorNote === 'function' ? global.guarantorNote() : null;
		$('#guarantorNote').text(note || '');
	}

	/** Digits only, so 35201-1234567-8 and 3520112345678 are one person — the server normalises the same. */
	function cnicDigits(v) { return String(v || '').replace(/[^0-9]/g, ''); }

	/** Read the blocks. A block with no NAME is not an entry — an empty one tabbed through is not a guarantor. */
	function collectGuarantors() {
		var out = [];
		$('#guarantorBlocks .js-guarantor-block').each(function () {
			var $b = $(this);
			var name = $.trim($b.find('.js-g-name').val() || '');
			if (!name) return;
			out.push({
				role: 'GUARANTOR',
				name: name,
				cnic: $.trim($b.find('.js-g-cnic').val() || '') || null,
				contact: $.trim($b.find('.js-g-contact').val() || '') || null,
				address: $.trim($b.find('.js-g-address').val() || '') || null
			});
		});
		return out;
	}

	/**
	 * ⭐ R4b — an ADVISORY about what has been typed. It never stops a sale.
	 *
	 * <p>This was `guarantorProblem`, and `main.js` turned its answer into `return false` — a guarantor
	 * shortfall was the only plan rule that could stop the cashier before anything was recorded. It is now
	 * shown beside the panel while the form is being filled, which is where a shopkeeper can still act on
	 * it, and the sale proceeds regardless.
	 *
	 * <p>The SHORTFALL is deliberately not reported here: the "1 of 2 recorded" counter already says it,
	 * continuously and without sounding like an error. What is reported is the two slips that make a
	 * recorded guarantor worthless — the server drops those rows and says so on the plan message.
	 *
	 * @returns a message, or null when there is nothing to say
	 */
	global.guarantorNote = function () {
		var list = collectGuarantors();
		var seen = {};
		var buyerCnic = cnicDigits($('#sellCustomerCnic').val());
		for (var i = 0; i < list.length; i++) {
			var key = cnicDigits(list[i].cnic) || ('n:' + list[i].name.toLowerCase());
			if (seen[key]) return tr('ui.js.guarantorDuplicate', 'The same guarantor has been entered twice.');
			seen[key] = true;
			// The buyer standing behind his own debt: worth precisely nothing, and the easiest slip here.
			if (buyerCnic && buyerCnic === cnicDigits(list[i].cnic)) {
				return tr('ui.js.guarantorIsBuyer', 'The customer buying cannot also be the guarantor.');
			}
		}
		return null;
	};

	// Recall by a COMPLETE cnic. 13 digits, exact, this shop only — a prefix search would let staff type
	// 352 and walk a list of national identifiers; a full match cannot be walked because you hold the card.
	$(document).on('input', '.js-g-cnic', function () {
		var $b = $(this).closest('.js-guarantor-block');
		var digits = cnicDigits($(this).val());
		var $msg = $b.find('.js-g-msg');
		if (digits.length < 13) {
			$msg.text(digits.length ? (tr('ui.js.guarantorCnicShort', 'A CNIC is usually 13 digits.')) : '');
			return;
		}
		$.get(ctx() + 'guarantorRecall?cnic=' + encodeURIComponent(digits)).done(function (resp) {
			var hit = (resp && (resp.object || resp.data)) || null;
			if (!hit || !hit.name) { $msg.text(''); return; }
			// Fill what is EMPTY; never overwrite what the cashier typed. A guarantor's address is often not
			// the address on file, and a form that overwrites a correction gets worked around.
			if (!$.trim($b.find('.js-g-name').val())) $b.find('.js-g-name').val(hit.name);
			if (!$.trim($b.find('.js-g-contact').val())) $b.find('.js-g-contact').val(hit.contact || '');
			if (!$.trim($b.find('.js-g-address').val())) $b.find('.js-g-address').val(hit.address || '');
			$msg.text(tr('ui.js.guarantorRecalled', 'Recalled from a previous sale.'));
			updateGuarantorCount();
		});
	});

	$(document).on('click', '.js-guarantor-recall', function () {
		var g = recentGuarantors[Number($(this).data('idx'))];
		if (!g) return;
		// Into the first block that has no name yet, so tapping twice fills both.
		var $target = $('#guarantorBlocks .js-guarantor-block').filter(function () {
			return !$.trim($(this).find('.js-g-name').val());
		}).first();
		if (!$target.length) return;
		$target.find('.js-g-name').val(g.name || '');
		$target.find('.js-g-cnic').val(g.cnic || '');
		$target.find('.js-g-contact').val(g.contact || '');
		$target.find('.js-g-address').val(g.address || '');
		$target.find('.js-g-msg').text(tr('ui.js.guarantorRecalled', 'Recalled from a previous sale.'));
		updateGuarantorCount();
	});

	$(document).on('input', '.js-g-name, .js-g-contact, .js-g-address', updateGuarantorCount);

	/** Exposed so the sale screen can draw the panel when it opens. */
	global.loadGuarantorPolicy = loadGuarantorPolicy;
	/** Exposed for the submit path and for resetting between sales. */
	global.guarantorsForSale = collectGuarantors;
	global.resetGuarantors = function () {
		$('#guarantorBlocks input').val('');
		$('#guarantorBlocks .js-g-msg').text('');
		updateGuarantorCount();
	};

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
			/*
			 * SER-3b: taken from the SALE LINE's serial box, not from a second field of our own.
			 *
			 * The panel used to carry its own "IMEI / serial" input beside #sellSerials, so the same number
			 * was asked for twice on one screen and the two could disagree — with nothing saying which one
			 * the serial register would actually read.
			 *
			 * assetRef is the plan's human LABEL ("which handset is this plan against?"); the serial register
			 * validates the same number. One number, entered once, doing both jobs.
			 *
			 * ⚠ Read from the CART, not from #sellSerials directly — Add-to-Cart clears that box, so reading
			 * it here produced assetRef = null on every financed sale. See cartSerial().
			 */
			assetRef: cartSerial(),
			// R4 - an ARRAY, and it must exist on BOTH InstallmentPlanDTO twins or it is dropped in transit:
			// the monolith re-serialises this block on its way to business-service, so a field on one side
			// only vanishes silently and the sale still succeeds.
			guarantors: collectGuarantors()
		};
	};

	/** Clear the panel after a completed sale, so the next customer does not inherit these terms. */
	global.resetInstallmentPanel = function () {
		$('#sellOnInstallment').prop('checked', false);
		$('#sellInstallmentFields').hide();
		$('#instDownPayment, #instFirstDueDate, #instFirstDueDateText').val('');
		$('#instSchedulePreview').empty();
		// The next customer must not inherit the last one's guarantors — the same reason the terms are cleared.
		if (typeof global.resetGuarantors === 'function') global.resetGuarantors();
		$('#sellGuarantorRow').hide();
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
		/*
		 * ⭐ R4c — WHO STANDS BEHIND THIS PLAN.
		 *
		 * The guarantors were recorded from the sale screen and then could not be read back anywhere: the
		 * endpoint existed, was tenant-scoped and was proxied, and NO client code called it. A shop could
		 * take two people's names and CNICs and never see them again — which is most of the point of
		 * taking them, since a guarantor matters precisely when a plan stops being paid.
		 *
		 * R4b made this urgent rather than merely missing: a shortfall is now allowed, and the message a
		 * cashier gets says "add the rest on the plan when you have them". Until this panel existed that
		 * was a promise the product could not keep.
		 *
		 * Here, with the schedule, for the reason INST-5a put the IMEI and the repossess action here — it
		 * is the screen a shopkeeper is already on when a plan goes wrong.
		 */
		foot += '<div id="planGuarantors" style="margin-top:18px"></div>';

		$('#installmentSchedule').html(html + '</tbody></table>' + foot);
		$('#instRepossess').off('click').on('click', function () { repossess(plan); });
		loadPlanGuarantors(plan);
	}

	/** The people standing behind one plan. Read-only list + an add form; money never moves from here. */
	function loadPlanGuarantors(plan) {
		var $box = $('#planGuarantors');
		if (!$box.length || !plan || !plan.id) return;
		$box.html('<span class="text-muted">' + esc(tr('ui.js.loading', 'Loading\u2026')) + '</span>');

		/*
		 * ⚠ `guarantorsRequired` is loaded by loadGuarantorPolicy(), which only runs when the SALE panel is
		 * opened — so on the Installments screen it is still 0 and the shortfall line would never appear,
		 * on exactly the screen a shop uses to notice a shortfall. Fetch it here when it has not been read.
		 *
		 * Fire-and-forget: the list is what matters, and a failed policy read must not hide the guarantors.
		 */
		var policy = guarantorsRequired ? $.Deferred().resolve().promise()
			: $.get(ctx() + 'guarantorsRequired').done(function (resp) {
				var payload = (resp && (resp.object || resp.data)) || {};
				guarantorsRequired = Number(payload.required || 0);
			});

		$.when($.get(ctx() + 'planGuarantors?planId=' + encodeURIComponent(plan.id)), policy)
			.done(function (listArgs) {
				// $.when hands back [data, statusText, jqXHR] per call once there is more than one.
				var resp = $.isArray(listArgs) ? listArgs[0] : listArgs;
				renderPlanGuarantors(plan, (resp && (resp.object || resp.data || resp.collection)) || []);
			})
			.fail(function () {
				// Say so rather than render an empty list: "no guarantors" and "could not load them" are
				// different facts, and a shop chasing a defaulter must not confuse them.
				$box.html('<p class="text-danger">'
					+ esc(tr('ui.js.guarantorLoadFailed', 'Could not load the guarantors for this plan.'))
					+ '</p>');
			});
	}

	function renderPlanGuarantors(plan, rows) {
		var $box = $('#planGuarantors');
		var open = plan.status === 'ACTIVE' || plan.status === 'DEFAULTED';

		var html = '<h4 style="margin:0 0 6px">'
			+ esc(tr('ui.js.guarantors', 'Guarantors')) + ' <span class="text-muted" style="font-weight:400">('
			+ rows.length + ')</span></h4>';

		if (!rows.length) {
			html += '<p class="text-muted">'
				+ esc(tr('ui.js.guarantorNoneOnPlan', 'Nobody is recorded against this plan.')) + '</p>';
		} else {
			html += '<table class="table table-condensed" style="max-width:820px"><thead><tr>'
				+ '<th>' + esc(tr('ui.js.guarantorName', 'Name')) + '</th>'
				+ '<th>' + esc(tr('ui.js.guarantorCnic', 'CNIC')) + '</th>'
				+ '<th>' + esc(tr('ui.js.guarantorMobile', 'Mobile')) + '</th>'
				+ '<th>' + esc(tr('ui.js.guarantorAddress', 'Address')) + '</th>'
				+ '<th>' + esc(tr('ui.js.instAdded', 'Added')) + '</th>'
				+ (open ? '<th></th>' : '') + '</tr></thead><tbody>';
			rows.forEach(function (g) {
				// A WITNESS attests; only a GUARANTOR stands behind the debt. Labelled, because a shop
				// counting who it can call must not count the wrong people.
				var isWitness = String(g.role || '').toUpperCase() === 'WITNESS';
				html += '<tr><td>' + esc(g.name || '')
					+ (isWitness ? ' <span class="label label-default">'
						+ esc(tr('ui.js.guarantorWitness', 'Witness')) + '</span>' : '')
					+ '</td>'
					+ '<td>' + esc(g.cnic || '\u2014') + '</td>'
					// The number is the point of the record when a plan defaults, so make it dialable.
					+ '<td>' + (g.contact
						? '<a href="tel:' + esc(String(g.contact).replace(/[^0-9+]/g, '')) + '">'
							+ esc(g.contact) + '</a>'
						: '\u2014') + '</td>'
					+ '<td>' + esc(g.address || '\u2014') + '</td>'
					+ '<td>' + esc(String(g.createdAt || '').substring(0, 10)) + '</td>'
					+ (open
						? '<td><button type="button" class="btn btn-xs btn-default js-g-remove" data-id="'
							+ esc(String(g.id)) + '" data-name="' + esc(g.name || '') + '">'
							+ esc(tr('ui.js.remove', 'Remove')) + '</button></td>'
						: '')
					+ '</tr>';
			});
			html += '</tbody></table>';
		}

		/*
		 * Adding is offered only while the plan is LIVE. A settled or cancelled plan is a closed record, and
		 * a guarantor added to one would be somebody who never agreed to stand behind anything.
		 */
		if (open) {
			var short = guarantorsRequired && rows.filter(function (g) {
				return String(g.role || 'GUARANTOR').toUpperCase() !== 'WITNESS';
			}).length < guarantorsRequired;
			if (short) {
				html += '<p class="text-warning" style="margin:4px 0">'
					+ esc((tr('ui.js.guarantorCount', '{0} of {1} recorded'))
						.replace('{0}', rows.length).replace('{1}', guarantorsRequired)) + '</p>';
			}
			html += '<div class="form-inline" style="margin-top:6px">'
				+ '<input type="text" id="pgName" class="form-control input-sm" style="margin-right:6px" '
				+ 'placeholder="' + esc(tr('ui.js.guarantorName', 'Name')) + '" maxlength="255">'
				+ '<input type="text" id="pgCnic" class="form-control input-sm" style="margin-right:6px" '
				+ 'placeholder="' + esc(tr('ui.js.guarantorCnic', 'CNIC')) + '" maxlength="32">'
				+ '<input type="text" id="pgContact" class="form-control input-sm" style="margin-right:6px" '
				+ 'placeholder="' + esc(tr('ui.js.guarantorMobile', 'Mobile')) + '" maxlength="64">'
				+ '<input type="text" id="pgAddress" class="form-control input-sm" style="margin-right:6px" '
				+ 'placeholder="' + esc(tr('ui.js.guarantorAddress', 'Address')) + '" maxlength="255">'
				+ '<button type="button" id="pgAdd" class="btn btn-primary btn-sm">'
				+ esc(tr('ui.js.guarantorAdd', 'Add guarantor')) + '</button>'
				+ '</div><span id="pgMsg" class="help-block" style="margin:4px 0"></span>';
		}

		$box.html(html);

		$box.find('#pgAdd').off('click').on('click', function () { addPlanGuarantor(plan); });
		$box.find('.js-g-remove').off('click').on('click', function () {
			removePlanGuarantor(plan, $(this).data('id'), $(this).data('name'));
		});
	}

	function addPlanGuarantor(plan) {
		var name = $.trim($('#pgName').val() || '');
		var $msg = $('#pgMsg').removeClass('text-danger').text('');
		// The NAME is the only required field, exactly as on the sale screen: a shop that has a name and a
		// phone number and no CNIC still has a guarantor.
		if (!name) {
			$msg.addClass('text-danger')
				.text(tr('ui.js.guarantorNameRequired', 'A guarantor needs a name.'));
			$('#pgName').focus();
			return;
		}
		$.post(ctx() + 'savePlanGuarantor', {
			planId: plan.id,
			name: name,
			cnic: $.trim($('#pgCnic').val() || ''),
			contact: $.trim($('#pgContact').val() || ''),
			address: $.trim($('#pgAddress').val() || '')
		}).done(function (resp) {
			// The monolith answers 200 with status FAILED on a refusal — read the ENVELOPE, not the status.
			if (resp && (resp.status === 'FAILED' || resp.status === 'ERROR')) {
				$msg.addClass('text-danger').text(resp.message || tr('ui.js.saveFailed', 'Could not save.'));
				return;
			}
			loadPlanGuarantors(plan);
		}).fail(function () {
			$msg.addClass('text-danger').text(tr('ui.js.saveFailed', 'Could not save.'));
		});
	}

	function removePlanGuarantor(plan, id, name) {
		if (!id) return;

		var go = function () {
			$.post(ctx() + 'deletePlanGuarantor', { id: id })
				.done(function (resp) {
					// Removing is OWNER-gated on the server. A cashier's attempt comes back as a 200 with a
					// refusal in the envelope, so say what happened rather than silently re-rendering an
					// unchanged list — which reads as the button not working.
					if (resp && (resp.status === 'FAILED' || resp.status === 'ERROR')) {
						$('#pgMsg').addClass('text-danger')
							.text(resp.message || tr('ui.js.saveFailed', 'Could not save.'));
						return;
					}
					loadPlanGuarantors(plan);
				})
				.fail(function () {
					$('#pgMsg').addClass('text-danger').text(tr('ui.js.saveFailed', 'Could not save.'));
				});
		};

		/*
		 * ⚠ uiConfirm takes an OPTIONS OBJECT and returns a PROMISE — uiConfirm({title, message, …}).then(ok).
		 *
		 * This called uiConfirm(message, callback), the shape window.confirm and most confirm helpers use.
		 * It does not throw at the call site: uiConfirm does `o.input = null` on whatever it is given, and
		 * assigning a property to a STRING throws in strict mode — "Cannot create property 'input' on string".
		 * So the dialog never opened and the removal never ran.
		 *
		 * (uiAlert accepts a bare string and normalises it; uiConfirm and uiPromptConfirm do not. Worth
		 * knowing, because the inconsistency is what makes the wrong shape look plausible.)
		 */
		var ask = (tr('ui.js.guarantorRemoveConfirm', 'Remove {0} from this plan?')).replace('{0}', name || '');
		if (typeof global.uiConfirm === 'function') {
			global.uiConfirm({
				title: tr('ui.js.guarantorRemoveTitle', 'Remove guarantor'),
				message: ask,
				confirmText: tr('ui.js.remove', 'Remove'),
				// Destructive: a guarantor row is the shop's recourse if the plan defaults.
				tone: 'danger'
			}).then(function (ok) { if (ok) go(); });
		} else {
			go();
		}
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
							message: apiMessage(resp, ''), tone: 'danger' });
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
						message: apiMessage(resp, 'That could not be saved.'), tone: 'danger' });
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
