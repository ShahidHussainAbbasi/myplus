/* ============================================================================
 * responsive-tables.js — give every dashboard grid its own horizontal scroller.
 *
 * theme.css hides body overflow-x below 767px, and only .dataTables_wrapper and
 * .table-responsive carried an overflow-x of their own. Only 3 of the ~37 grids
 * across the four dashboards are DataTables and .table-responsive appears in no
 * template, so every other table — Fee Register at 13 columns, Grade at 10, the
 * prescription and controlled-substance registers — was CLIPPED by the body rule
 * instead of scrolling. The columns were not merely hard to reach; they could not
 * be reached at all.
 *
 * The wrap is applied here rather than in markup because that would mean ~37
 * hand-edited <div>s across four templates and every table added afterwards would
 * silently regress. Cost is one querySelectorAll over static markup at DOM-ready;
 * the module scripts only ever replace <tbody> rows, so the wrapper survives every
 * subsequent reload of a grid.
 *
 * Pairs with .table-scroll in /css/responsive.css.
 * ========================================================================== */
(function () {
	'use strict';

	/* A table that already sits in something scrollable must not be wrapped again:
	 * nested scrollers swallow the drag, so the inner table never reaches its last
	 * column. Covers the sell cart (#sellCartScroll), DataTables, and re-runs. */
	function alreadyScrollable(table) {
		var parent = table.parentElement;
		if (!parent) { return true; }

		/* `.table-scroll` and `.table-responsive` are OURS and always carry overflow-x in CSS, so the
		 * class is a safe fast path for them.
		 *
		 * `.dataTables_wrapper` is NOT: DataTables only makes its wrapper scrollable when `scrollX` is
		 * enabled, and this app does not enable it. Trusting the class meant every DataTables grid was
		 * skipped and left CLIPPED on a narrow screen — the education Students grid overflowed by
		 * 1860px with `scrollLeft` pinned at 0, i.e. columns that cannot be reached at all, which is the
		 * exact failure this module was written to prevent. Let it fall through to the computed check
		 * below: a wrapper that really scrolls is still detected, and one that only looks like it should
		 * now gets a `.table-scroll` of its own. */
		if (parent.classList.contains('table-scroll') ||
			parent.classList.contains('table-responsive')) {
			return true;
		}

		/* Checked on the direct parent only — the scroll container is always the
		 * table's own wrapper, and walking the whole ancestor chain would force a
		 * style resolution per level for no additional coverage. */
		var overflowX = window.getComputedStyle(parent).overflowX;
		return overflowX === 'auto' || overflowX === 'scroll';
	}

	function wrapTables() {
		var tables = document.querySelectorAll('#content table');

		for (var i = 0; i < tables.length; i++) {
			var table = tables[i];
			if (alreadyScrollable(table)) { continue; }

			var wrapper = document.createElement('div');
			wrapper.className = 'table-scroll';
			table.parentNode.insertBefore(wrapper, table);
			wrapper.appendChild(table);
		}
	}

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', wrapTables);
	} else {
		wrapTables();
	}

	/* Several screens build their whole table in JS after the page has loaded — the
	 * Finance suite (ledger, aging, trial balance, tax breakdown, audit) and the till
	 * X/Z report render a fresh <table> into a container, and those wide report grids
	 * are exactly the ones that must scroll. Re-running on ajaxComplete catches them.
	 *
	 * Cheap to repeat: a table already wrapped is rejected by a classList test on its
	 * parent before any style is resolved, so a re-run over an untouched page costs one
	 * querySelectorAll and a handful of string comparisons. */
	if (window.jQuery) {
		window.jQuery(document).ajaxComplete(wrapTables);
	}

	/* Exposed for screens that render a grid outside an AJAX callback. */
	window.wrapResponsiveTables = wrapTables;
})();
