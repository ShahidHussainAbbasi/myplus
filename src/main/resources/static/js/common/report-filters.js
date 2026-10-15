/**
 * Shared report filter rail + CSV export (slice b2b-P3e-1 = requirement #6).
 *
 * WHY THIS IS SHARED AND NOT PART OF THE SALE REPORT: every report that follows — the returns register from
 * 3c, purchases, stock — needs the same rail and the same Export button. Writing it per screen is the
 * duplication this component exists to prevent, and it is why 3c deliberately shipped WITHOUT a returns
 * screen: that register attaches here instead.
 *
 * CONTRACT
 *   mountReportFilters({
 *     container : element or id to render into
 *     dimensions: subset of ['groupBy','customer','product','category','company','channel']  (default: the four filters)
 *     onApply   : function(values) — called when the user applies; run your existing load with `values`
 *     exportUrl : function(values) -> string — the CSV href; omit to hide the Export button
 *   })
 *
 * The values object uses the SAME field names the backend binds (customerId, productId, category,
 * customerType, groupBy), so a caller passes it straight through to its existing POST with no translation
 * layer.
 */
(function (global) {
	'use strict';

	function el(id) { return typeof id === 'string' ? document.getElementById(id) : id; }

	function label(text) {
		var l = document.createElement('label');
		l.textContent = text;
		return l;
	}

	function field(labelText, control) {
		var d = document.createElement('div');
		d.className = 'sr-field';
		d.appendChild(label(labelText));
		d.appendChild(control);
		return d;
	}

	function select(id, placeholder) {
		var s = document.createElement('select');
		s.id = id;
		s.className = 'form-control';
		var o = document.createElement('option');
		o.value = '';
		o.textContent = placeholder;          // "" means ALL — never "match rows with an empty value"
		s.appendChild(o);
		return s;
	}

	/** Append one <option> per row (textContent — every label is escaped by construction). */
	function fillRows(sel, rows, valueKey, labelKey) {
		(rows || []).forEach(function (r) {
			if (r[valueKey] == null) return;
			var o = document.createElement('option');
			o.value = r[valueKey];
			o.textContent = String(r[labelKey] == null ? r[valueKey] : r[labelKey]);
			sel.appendChild(o);
		});
		/*
		 * Redraw the searchable widget EXPLICITLY. The shared caches read with global:false (and a warm cache reads
		 * nothing at all), so the ajaxComplete hook that used to redraw these pickers never fires: the <select> held
		 * 85 customers while the widget showed one row — an empty filter on screen (dashboard-no-freeze case 5).
		 * refreshSearchableSelect is the one sanctioned way (it carries the busy-guard: never redraw a picker the
		 * operator has open).
		 */
		if (typeof global.refreshSearchableSelect === 'function') global.refreshSearchableSelect(sel);
	}

	/** Fill a select from a GenericResponse collection — the fallback when no shared loader is on the page. */
	function fill(sel, url, valueKey, labelKey) {
		if (!sel) return;
		$.ajax({ url: serverContext + url, dataType: 'json', global: false }).done(function (resp) {
			fillRows(sel, (resp && (resp.collection || resp.data)) || [], valueKey, labelKey);
		});
	}

	/** Distinct, sorted values of one field across already-loaded rows — used for category. */
	function fillFromRows(sel, rows, key) {
		if (!sel || !rows) return;
		var seen = {};
		rows.forEach(function (r) {
			var v = r && r[key];
			if (v && !seen[v]) { seen[v] = 1; }
		});
		Object.keys(seen).sort().forEach(function (v) {
			var o = document.createElement('option');
			o.value = v;
			o.textContent = v;
			sel.appendChild(o);
		});
	}

	/*
	 * SRA-4a — the CODES the sale report sends where a value has no name (design sra-sales-by-rep-and-area.md §7): a sale
	 * source, or a gap. Codes travel, words come from the bundle — so "Not recorded" is translated, and a rep who happens to
	 * be called "Counter" is never mistaken for the counter. ONE place turns a code into words, for the grid, the groups
	 * and the filters alike.
	 */
	var SALE_SOURCES = ['COUNTER', 'REP_LOGIN', 'REP_PAPER', 'OFFICE', 'PORTAL', 'NOT_RECORDED'];
	function saleReportCodeLabel(code) {
		if (code == null || code === '') return '';
		var key = 'ui.js.srCode' + String(code).toLowerCase().replace(/(^|_)([a-z])/g, function (m, a, b) { return b.toUpperCase(); });
		var txt = t(key);
		return txt === key ? String(code) : txt;   // an unknown code still reads as itself, never as a bare key
	}
	global.saleReportCodeLabel = saleReportCodeLabel;

	/** Static options: [value, label] pairs appended after the "all" placeholder. */
	function fillPairs(sel, pairs) {
		if (!sel) return;
		pairs.forEach(function (pv) {
			var o = document.createElement('option');
			o.value = pv[0];
			o.textContent = pv[1];
			sel.appendChild(o);
		});
	}

	function mountReportFilters(opts) {
		var host = el(opts.container);
		if (!host) return null;
		var dims = opts.dimensions || ['customer', 'product', 'category', 'channel'];
		var ids = {};

		function add(dim, id, placeholder) {
			if (dims.indexOf(dim) === -1) return null;
			var s = select(id, placeholder);
			host.appendChild(field(t('ui.js.filter' + dim.charAt(0).toUpperCase() + dim.slice(1)), s));
			ids[dim] = id;
			return s;
		}

		// B2B-P3e-2 (#6): group-by lives in the SHARED rail too — every report that gains grouping inherits
		// the same control and the same value name (groupBy) the backend binds.
		var groupBy = null;
		if (dims.indexOf('groupBy') !== -1) {
			groupBy = select('rfGroupBy', t('ui.js.noGrouping'));
			[['DAY','ui.js.groupDay'],['MONTH','ui.js.groupMonth'],['CUSTOMER','ui.js.groupCustomer'],
			 ['PRODUCT','ui.js.groupProduct'],['CATEGORY','ui.js.groupCategory'],
			 ['COMPANY','ui.js.groupCompany'],['CHANNEL','ui.js.groupChannel'],
			 // SRA-4a — offered only where the page asks for the rep dimensions (the sale report).
			 ].concat(dims.indexOf('rep') === -1 ? [] : [
			 ['REP','ui.js.groupRep'],['SOURCE','ui.js.groupSource'],['KEYED_BY','ui.js.groupKeyedBy'],
			 ['AREA','ui.js.groupArea'],['CITY','ui.js.groupCity'],['SUPPLIER','ui.js.groupSupplier']])
				.forEach(function(pair){
					var o = document.createElement('option');
					o.value = pair[0];
					o.textContent = t(pair[1]);
					groupBy.appendChild(o);
				});
			host.appendChild(field(t('ui.js.groupBy'), groupBy));
			ids.groupBy = 'rfGroupBy';
		}

		var customer = add('customer', 'rfCustomer', t('ui.js.allCustomers'));
		var product  = add('product',  'rfProduct',  t('ui.js.allProducts'));
		var category = add('category', 'rfCategory', t('ui.js.allCategories'));
		// #18: the manufacturer/COMPANY behind the product. Sourced from the returned rows like category,
		// not from a master list — so the filter can only offer companies the report actually contains.
		var company  = add('company',  'rfCompany',  t('ui.js.allCompanies'));
		var channel  = add('channel',  'rfChannel',  t('ui.js.allChannels'));
		// SRA-4a — who sold it, where, from whom, and with free goods or not. Value 0 = the rows with NONE.
		var rep      = add('rep',      'rfRep',      t('ui.js.allReps'));
		var source   = add('source',   'rfSource',   t('ui.js.allSources'));
		var area     = add('area',     'rfArea',     t('ui.js.allAreas'));
		var city     = add('city',     'rfCity',     t('ui.js.allCities'));
		var supplier = add('supplier', 'rfSupplier', t('ui.js.allSuppliers'));
		var bonus    = add('bonus',    'rfBonus',    t('ui.js.bonusEither'));
		fillPairs(rep,      [['0', t('ui.js.srNoRepOption')]]);
		fillPairs(source,   SALE_SOURCES.map(function (c) { return [c, saleReportCodeLabel(c)]; }));
		fillPairs(area,     [['0', saleReportCodeLabel('NO_AREA')]]);
		fillPairs(supplier, [['0', saleReportCodeLabel('NO_SUPPLIER')]]);
		fillPairs(bonus,    [['WITH', t('ui.js.bonusWith')], ['WITHOUT', t('ui.js.bonusWithout')]]);

		/*
		 * PERF (review 2026-09-26) — the lists load LAZILY, from the SHARED caches.
		 *
		 * The rail is mounted on page load (so the report shows its filters before its first run), and it used to
		 * FILL on page load too: every dashboard open fetched the whole catalogue through /getUserProduct —
		 * 1.4 MB of JSON, parsed into thousands of <option>s — and a second /customerOptions, for a report most
		 * sessions never open. Now:
		 *   - the lists are filled the first time they are needed: loadLists() (the report runs) or the operator
		 *     reaching for a Customer/Product filter — whichever comes first, once;
		 *   - products come from ProductPicker (the till's cached, ACTIVE-only id+name projection — the same set
		 *     /getUserProduct returns by default) and customers from CustomerPicker; both are usually warm
		 *     already, so opening the report costs no request at all. The URL read stays as the fallback for a
		 *     page that does not carry the shared pickers.
		 */
		var listsLoaded = false;
		function loadLists() {
			if (listsLoaded) return;
			listsLoaded = true;
			if (customer) {
				if (global.CustomerPicker) global.CustomerPicker.load(function (rows) { fillRows(customer, rows, 'customerId', 'name'); });
				else fill(customer, 'customerOptions', 'customerId', 'name');
			}
			if (product) {
				if (global.ProductPicker) global.ProductPicker.load(function (rows) { fillRows(product, rows, 'id', 'name'); });
				else fill(product, 'getUserProduct', 'id', 'name');
			}
			// SRA-4a — the business's own lists (each small); a rep's own set may read them, the pickers do too.
			// A rep who has left still has sales in past periods, so an INACTIVE rep stays in the filter — marked, and after
			// the active ones, so the list a manager scans first is the people working today.
			if (rep) $.ajax({ url: serverContext + 'getSalesReps', dataType: 'json', global: false }).done(function (resp) {
				var rows = ((resp && resp.collection) || []).slice().sort(function (a, b) {
					var ia = String(a.status || 'ACTIVE').toUpperCase() === 'ACTIVE' ? 0 : 1;
					var ib = String(b.status || 'ACTIVE').toUpperCase() === 'ACTIVE' ? 0 : 1;
					return ia - ib || String(a.name).localeCompare(String(b.name));
				}).map(function (r) {
					var active = String(r.status || 'ACTIVE').toUpperCase() === 'ACTIVE';
					return { id: r.id, name: active ? r.name : r.name + ' (' + t('ui.js.inactive') + ')' };
				});
				fillRows(rep, rows, 'id', 'name');
			});
			if (area)     fill(area,     'getAreas',     'id', 'name');
			if (city)     fill(city,     'getCities',    'id', 'name');
			if (supplier) fill(supplier, 'getUserVender', 'id', 'name');
		}
		// On the RAIL, in the capture phase: these selects are upgraded to bootstrap-select, so the operator's click
		// and focus land on the widget's BUTTON — a listener on the <select> itself would never hear them.
		host.addEventListener('focusin', loadLists, true);
		host.addEventListener('mousedown', loadLists, true);
		// The four values CustomerType actually has — from the ONE list in main.js, never a copy. This filter
		// shipped with a "RETAIL" that is not one of them (a channel matching no row, ever) and with VIP
		// missing, so VIP sales could not be filtered at all. The 3e-1 gate counted these options but never
		// selected one and checked that anything came back, which is how a filter matching nothing went green.
		if (channel) {
			Object.keys(CUSTOMER_TYPE_LABELS).forEach(function (value) {
				var o = document.createElement('option');
				o.value = value;
				o.textContent = customerTypeLabel(value);
				channel.appendChild(o);
			});
		}

		function values() {
			return {
				customerId  : customer && customer.value ? customer.value : '',
				productId   : product  && product.value  ? product.value  : '',
				category    : category && category.value ? category.value : '',
				manufacturer: company  && company.value  ? company.value  : '',
				customerType: channel  && channel.value  ? channel.value  : '',
				groupBy     : groupBy  && groupBy.value  ? groupBy.value  : '',
				// SRA-4a — the names loadSR binds (SellDTO).
				repId       : rep      && rep.value      ? rep.value      : '',
				saleSource  : source   && source.value   ? source.value   : '',
				areaId      : area     && area.value     ? area.value     : '',
				cityId      : city     && city.value     ? city.value     : '',
				supplierId  : supplier && supplier.value ? supplier.value : '',
				bonus       : bonus    && bonus.value    ? bonus.value    : ''
			};
		}

		// Export button — a plain link so the browser handles Content-Disposition and saves the file.
		var exportLink = null;
		if (typeof opts.exportUrl === 'function') {
			exportLink = document.createElement('a');
			exportLink.id = 'rfExport';
			exportLink.className = 'btn btn-default';
			exportLink.textContent = t('ui.js.exportCsv');
			exportLink.setAttribute('href', opts.exportUrl(values()));
			host.appendChild(field(' ', exportLink));
		}

		function refreshExport() {
			if (exportLink && typeof opts.exportUrl === 'function') {
				exportLink.setAttribute('href', opts.exportUrl(values()));
			}
		}

		[customer, product, category, channel, groupBy, rep, source, area, city, supplier, bonus].forEach(function (s) {
			if (s) s.addEventListener('change', function () {
				refreshExport();                       // the file must always match what is on screen
				if (typeof opts.onApply === 'function') opts.onApply(values());
			});
		});

		return {
			values: values,
			refreshExport: refreshExport,
			/** Fill the Customer/Product lists (once). Call it when the report is opened or run. */
			loadLists: loadLists,
			/** Populate the category list from the rows a report just loaded. */
			categoriesFrom: function (rows) { fillFromRows(category, rows, 'category'); },
			// #18: same mechanism, same reason — only companies present in the data are offered.
			companiesFrom : function (rows) { fillFromRows(company,  rows, 'manufacturer'); }
		};
	}

	global.mountReportFilters = mountReportFilters;
})(window);
