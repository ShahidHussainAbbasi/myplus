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
 *     dimensions: subset of ['customer','product','category','channel']  (default: all)
 *     onApply   : function(values) — called when the user applies; run your existing load with `values`
 *     exportUrl : function(values) -> string — the CSV href; omit to hide the Export button
 *   })
 *
 * The values object uses the SAME field names the backend binds (customerId, productId, category,
 * customerType), so a caller passes it straight through to its existing POST with no translation layer.
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

	/** Fill a select from a GenericResponse collection, escaping every label. */
	function fill(sel, url, valueKey, labelKey) {
		if (!sel) return;
		$.get(serverContext + url, function (resp) {
			var rows = (resp && (resp.collection || resp.data)) || [];
			rows.forEach(function (r) {
				if (r[valueKey] == null) return;
				var o = document.createElement('option');
				o.value = r[valueKey];
				o.textContent = String(r[labelKey] == null ? r[valueKey] : r[labelKey]);
				sel.appendChild(o);
			});
		}, 'json');
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

		var customer = add('customer', 'rfCustomer', t('ui.js.allCustomers'));
		var product  = add('product',  'rfProduct',  t('ui.js.allProducts'));
		var category = add('category', 'rfCategory', t('ui.js.allCategories'));
		var channel  = add('channel',  'rfChannel',  t('ui.js.allChannels'));

		if (customer) fill(customer, 'getUserCustomer', 'customerId', 'name');
		if (product)  fill(product,  'getUserProduct',  'id',         'name');
		if (channel) {
			['WALK_IN', 'RETAIL', 'WHOLESALE', 'RETAILER'].forEach(function (v) {
				var o = document.createElement('option');
				o.value = v;
				o.textContent = v.replace('_', ' ');
				channel.appendChild(o);
			});
		}

		function values() {
			return {
				customerId  : customer && customer.value ? customer.value : '',
				productId   : product  && product.value  ? product.value  : '',
				category    : category && category.value ? category.value : '',
				customerType: channel  && channel.value  ? channel.value  : ''
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

		[customer, product, category, channel].forEach(function (s) {
			if (s) s.addEventListener('change', function () {
				refreshExport();                       // the file must always match what is on screen
				if (typeof opts.onApply === 'function') opts.onApply(values());
			});
		});

		return {
			values: values,
			refreshExport: refreshExport,
			/** Populate the category list from the rows a report just loaded. */
			categoriesFrom: function (rows) { fillFromRows(category, rows, 'category'); }
		};
	}

	global.mountReportFilters = mountReportFilters;
})(window);
