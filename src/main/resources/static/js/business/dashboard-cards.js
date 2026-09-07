/**
 * SER-5 — the dashboard's two BREAKDOWN cards: products by category, and serialised stock by condition.
 *
 * Asked for by the user: *"on businessdashbaord dashDateLabel we want to add card to show to the user
 * products by categories where user will click and directly navigate to the filtered products also a mobile
 * shop user want to see purchaseCondition (new, used or Refurbished) with single click instead of moving to
 * the products and searching for it"*, then *"Condition card will display all the used in the stock with
 * detail"*.
 *
 * <h3>What a breakdown card is for</h3>
 * The dashboard already had counts and trends. Neither answers the question a shopkeeper acts on: 1,042
 * products — of WHAT? Twelve used handsets — WHICH twelve? Until now the answer meant leaving the dashboard,
 * opening the Product screen and searching for the thing the dashboard had just told you about. Each card
 * therefore states a breakdown AND is the way into it; a number you cannot click is a dead end that looks
 * like a link.
 *
 * <h3>Facet counts come from the SAME query as the rows</h3>
 * Both cards count what their own drill-through will show — active products for the category card, IN_STOCK
 * units for the condition card. A card that counts one population and opens onto another ("120" then 166
 * rows) is worse than no card: the shop stops trusting the dashboard, and there is nothing on screen to
 * explain the discrepancy. This is the discipline every faceted catalogue follows for the same reason.
 *
 * <h3>Failure is stated, never rendered as zero</h3>
 * A failed fetch shows a retry, not "0 categories". An empty state and a broken one look identical to a
 * user, and only one of them is worth telling somebody about.
 */
(function (global, $) {
	'use strict';

	/*
	 * How many categories the card shows before it stops.
	 *
	 * A tenant here has 39. Painting all of them turns a summary into a second navigation menu, and the tail
	 * is a long run of ones nobody scans. Eight covers the shape of a catalogue; the rest are reachable in one
	 * click through "All products", which is where a person browsing 39 categories actually wants to be.
	 */
	var CATEGORY_LIMIT = 8;

	/** Units per page in the condition drill. Matches the Product grid, for one paging feel across the app. */
	var UNITS_PAGE_SIZE = 50;

	/* The grades, in the order a shop thinks about them, with the tone each should read in. */
	var GRADES = [
		{ code: 'NEW',         key: 'ui.js.conditionNew',         tone: 'grade-new' },
		{ code: 'USED',        key: 'ui.js.conditionUsed',        tone: 'grade-used' },
		{ code: 'REFURBISHED', key: 'ui.js.conditionRefurbished', tone: 'grade-refurb' }
	];

	function esc(v) {
		return (typeof escHtml === 'function') ? escHtml(v == null ? '' : String(v))
		                                       : String(v == null ? '' : v);
	}

	function gradeLabel(code) {
		for (var i = 0; i < GRADES.length; i++) {
			if (GRADES[i].code === code) return t(GRADES[i].key);
		}
		return code;   // a grade this build does not know about is shown as-is, never dropped
	}

	/** The shared "this card could not load" state, with the retry wired to whatever refills it. */
	function renderCardError($el, retry) {
		$el.html('<div class="breakdown-error">'
			+ '<span class="glyphicon glyphicon-exclamation-sign"></span> '
			+ '<button type="button" class="btn btn-link btn-sm js-card-retry">' + esc(t('ui.js.couldNotLoadCard'))
			+ '</button></div>');
		$el.find('.js-card-retry').one('click', retry);
	}

	/** Skeleton rows while a card is in flight — the shape of the answer, so the layout does not jump. */
	function renderSkeleton($el, rows) {
		var html = '';
		for (var i = 0; i < rows; i++) html += '<div class="breakdown-skeleton"></div>';
		$el.html(html);
	}

	/* ═════════════════════════════════════════════════════════════════════════════════════════════
	 * Card 1 — products by category
	 * ═══════════════════════════════════════════════════════════════════════════════════════════ */

	/**
	 * A bar per category, widest first, each one a control that opens the Product grid filtered to it.
	 *
	 * <p>Bars rather than a pie: the question is "which categories are big and can I get at them", and a
	 * length is read accurately where a pie slice is not — nor can a pie slice be clicked with any confidence
	 * at 3%. The bar IS the button, so the target grows with the count rather than shrinking.
	 */
	function renderCategories($el, rows) {
		if (!rows.length) {
			$el.html('<div class="breakdown-empty">' + esc(t('ui.js.noDataYet')) + '</div>');
			return;
		}

		// The scale is the LARGEST BAR, not the total: with 39 categories every bar would otherwise be a
		// sliver, and the card would show that a catalogue exists without showing its shape.
		var top = rows[0].count || 1;
		var shown = rows.slice(0, CATEGORY_LIMIT);
		var hidden = rows.length - shown.length;

		var html = '<p class="breakdown-hint">' + esc(t('ui.js.categoryCardHint')) + '</p><ul class="breakdown-list">';
		shown.forEach(function (r) {
			var name = r.uncategorised ? t('ui.js.uncategorised') : (r.categoryName || '');
			var pct = Math.max(2, Math.round((Number(r.count) || 0) / top * 100));   // never a zero-width bar
			html += '<li>'
				+ '<button type="button" class="breakdown-row" '
				+ 'data-category="' + (r.uncategorised ? 'none' : esc(r.categoryId)) + '" '
				+ 'data-label="' + esc(name) + '">'
				+ '<span class="breakdown-name">' + esc(name) + '</span>'
				+ '<span class="breakdown-count">' + esc(r.count) + '</span>'
				+ '<span class="breakdown-bar" style="width:' + pct + '%"></span>'
				+ '</button></li>';
		});
		html += '</ul>';

		// The tail is never hidden without saying so, and the way to it is the unfiltered grid.
		html += '<div class="breakdown-foot">';
		if (hidden > 0) html += '<span class="breakdown-more">' + esc(t('ui.js.moreCategories', hidden)) + '</span>';
		html += '<button type="button" class="btn btn-link btn-sm js-all-products">'
			+ esc(t('ui.js.allCategories')) + ' <span class="glyphicon glyphicon-chevron-right"></span>'
			+ '</button></div>';

		$el.html(html);
	}

	function loadCategoryCard() {
		var $el = $('#dashCategoryCard');
		if (!$el.length) return;
		renderSkeleton($el, 5);
		$.get(serverContext + 'getCategoryCounts')
			.done(function (resp) {
				renderCategories($el, (resp && resp.collection) ? resp.collection : []);
			})
			.fail(function () { renderCardError($el, loadCategoryCard); });
	}

	$(document).on('click', '#dashCategoryCard .breakdown-row', function () {
		var id = $(this).data('category');
		global.openProductsForCategory(id === 'none' ? null : id, $(this).data('label'));
	});

	$(document).on('click', '#dashCategoryCard .js-all-products', function () {
		global.openProductsUnfiltered();
	});

	/* ═════════════════════════════════════════════════════════════════════════════════════════════
	 * Card 2 — serialised stock by condition
	 * ═══════════════════════════════════════════════════════════════════════════════════════════ */

	/**
	 * One tile per grade, each opening the register filtered to it.
	 *
	 * <p>Every grade is drawn even at zero. Refurbished is zero for most shops on day one, and a tile that
	 * appears only once the first refurbished handset exists is a feature nobody discovers — the shop has to
	 * see the grade before it books one in. A zero tile is also an answer: "we hold no refurbished stock" is
	 * what somebody asking the question wanted to know.
	 */
	function renderConditions($el, rows) {
		var byGrade = {};
		rows.forEach(function (r) { byGrade[r.grade] = Number(r.count) || 0; });

		var html = '<p class="breakdown-hint">' + esc(t('ui.js.conditionCardHint')) + '</p><div class="grade-grid">';
		GRADES.forEach(function (g) {
			var n = byGrade[g.code] || 0;
			html += '<button type="button" class="grade-tile ' + g.tone + (n === 0 ? ' is-empty' : '') + '" '
				+ 'data-grade="' + g.code + '">'
				+ '<span class="grade-count">' + n + '</span>'
				+ '<span class="grade-name">' + esc(t(g.key)) + '</span>'
				+ '</button>';
		});
		html += '</div>';

		/*
		 * A grade the server reported that this build does not list — appended rather than dropped.
		 *
		 * The register is the record. A row we cannot classify is precisely the one somebody needs to see,
		 * and a card that silently omits it would under-report the shop's stock with nothing to show why.
		 */
		Object.keys(byGrade).forEach(function (code) {
			if (GRADES.some(function (g) { return g.code === code; })) return;
			html += '<button type="button" class="grade-tile grade-other" data-grade="' + esc(code) + '">'
				+ '<span class="grade-count">' + byGrade[code] + '</span>'
				+ '<span class="grade-name">' + esc(code) + '</span></button>';
		});

		$el.html(html);
	}

	function loadConditionCard() {
		var $el = $('#dashConditionCard');
		if (!$el.length) return;
		renderSkeleton($el, 3);
		$.get(serverContext + 'serialConditionCounts')
			.done(function (resp) {
				renderConditions($el, (resp && resp.collection) ? resp.collection : []);
			})
			.fail(function () { renderCardError($el, loadConditionCard); });
	}

	$(document).on('click', '#dashConditionCard .grade-tile', function () {
		global.showConditionUnits($(this).data('grade'));
	});

	/* ═════════════════════════════════════════════════════════════════════════════════════════════
	 * The condition drill — "all the used in the stock with detail", paged
	 * ═══════════════════════════════════════════════════════════════════════════════════════════ */

	var unitsState = { grade: null, page: 0 };

	/**
	 * Fetch and paint one page of units.
	 *
	 * <p>Paged from the first request, not once the list "gets big". A shop taking trade-ins has hundreds
	 * within a year, and an unbounded read is the defect that has to be designed out rather than discovered
	 * in production by the tenant it finally hurts.
	 */
	function loadUnitsPage() {
		var $body = $('#conditionUnitsBody');
		$body.html('<tr><td colspan="4" class="text-muted">' + esc(t('ui.js.loading')) + '</td></tr>');

		$.get(serverContext + 'serialUnitsByCondition', {
			grade: unitsState.grade,
			status: 'IN_STOCK',
			page: unitsState.page,
			size: UNITS_PAGE_SIZE
		}).done(function (resp) {
			var rows = (resp && resp.collection) ? resp.collection : [];
			var meta = (resp && resp.page) ? resp.page : {};

			$body.html(rows.map(function (u) {
				return '<tr>'
					+ '<td>' + esc(u.serialNo) + '</td>'
					+ '<td>' + esc(u.productName || '') + '</td>'
					+ '<td>' + esc(u.purchaseInvoiceNo || '') + '</td>'
					+ '<td>' + esc(u.dated || '') + '</td>'
					+ '</tr>';
			}).join(''));

			$('#conditionUnitsEmpty').toggle(rows.length === 0);

			var total = Number(meta.totalElements) || 0;
			var from = total === 0 ? 0 : (unitsState.page * UNITS_PAGE_SIZE) + 1;
			var to = from === 0 ? 0 : from + rows.length - 1;
			$('#conditionUnitsRange').text(t('ui.js.showingRange', from, to, total));
			// The pager is hidden when one page holds everything: a Next that cannot advance is a control
			// that lies about there being more.
			$('#conditionUnitsPager').toggle(total > UNITS_PAGE_SIZE);
			$('#conditionUnitsPrev').prop('disabled', !meta.hasPrevious);
			$('#conditionUnitsNext').prop('disabled', !meta.hasNext);
		}).fail(function (jqXHR, textStatus, errorThrown) {
			$body.html('');
			if (typeof handleAjaxFailure === 'function') {
				handleAjaxFailure(jqXHR, errorThrown, 'serialUnitsByCondition');
			}
		});
	}

	/** Open the register filtered to one grade. Entered from the condition card. */
	global.showConditionUnits = function (grade) {
		unitsState.grade = grade;
		unitsState.page = 0;
		$('.formDiv').hide();
		$('#ConditionUnitsDiv').show();
		$('#conditionUnitsTitle').text(gradeLabel(grade));
		$('#conditionUnitsHint').text(t('ui.js.conditionCardHint'));
		loadUnitsPage();
		$('html, body').animate({ scrollTop: 0 }, 200);
	};

	$(document).on('click', '#conditionUnitsNext', function () {
		unitsState.page += 1;
		loadUnitsPage();
	});

	$(document).on('click', '#conditionUnitsPrev', function () {
		if (unitsState.page > 0) { unitsState.page -= 1; loadUnitsPage(); }
	});

	/**
	 * Back to the dashboard.
	 *
	 * <p>Defined here because nothing else defined it: the dashboard was shown on load and every other
	 * section is reached through the sidebar, so no screen had ever needed to return to it. `.formDiv` hide +
	 * show is exactly what every other section switch in this app does.
	 */
	global.showDashboardHome = function () {
		$('.formDiv').hide();
		$('#DashboardDiv').show();
		$('html, body').animate({ scrollTop: 0 }, 200);
	};

	/* ═════════════════════════════════════════════════════════════════════════════════════════════ */

	$(function () {
		// Only on a page that HAS the cards. This file loads with the business dashboard, but the same
		// guard keeps it inert anywhere else it is ever included.
		if (!$('#dashCategoryCard').length && !$('#dashConditionCard').length) return;

		// No capability gate on this one, so there is nothing to wait for.
		loadCategoryCard();

		/*
		 * \u26a0 The condition card waits for `capabilities:ready`, and that wait is not optional.
		 *
		 * capabilities.js fetches the map ASYNCHRONOUSLY and only then adds `.cap-off`. Checking for the
		 * class at DOM-ready therefore always found it absent \u2014 the card would have loaded for every
		 * tenant, including the ones it is gated away from, and then been hidden a moment later. The
		 * request still went out, the data still crossed the wire, and nothing anywhere reported a
		 * problem: the screen looked correct.
		 *
		 * The event fires once the map has SETTLED, including when the fetch failed (capabilities.js
		 * fails open by design), so a capability service that is down leaves the card working rather
		 * than silently missing.
		 */
		if (!$('#dashConditionCard').length) return;
		$(document).one('capabilities:ready', function () {
			if ($('#dashConditionCard').closest('.cap-off').length) return;   // tenant does not have it
			loadConditionCard();
		});
	});

})(window, jQuery);
