/* ============================================================================
 * data-import.js — slice I1. The "Template" and "Import" buttons on a data grid.
 *
 * Sibling of lazy-export.js, and the same shape: this file defines a DataTables
 * button factory globally, and the module's loadDataTable() concatenates it into
 * the buttons array it already builds. One insertion point, not one per grid.
 *
 * THE BUTTONS ARE REGISTRY-DRIVEN. importButtons(entity) returns [] unless the
 * server has an ImportSpec for that entity, so a grid the platform cannot import
 * into shows nothing — and it is structurally impossible to ship a button that
 * posts into a void. Sell, Purchase, Orders and Quote get no buttons for a
 * reason that is not "not yet": they are numbered documents whose creation moves
 * stock and posts to the ledger, so a row inserted behind the sale path is a row
 * the books disagree with.
 *
 * Reporting is deliberately ASYMMETRIC (design §11): an ERROR is a call to
 * action — the operator must edit the file and re-upload — so refusals are always
 * listed in full. A SKIP is not: the row already exists, which is the outcome
 * they wanted, so skips collapse to a count. The re-import case is what settles
 * it: fix 20 of 500 rows, re-upload the whole file, and listing all 480 skips
 * would bury the 20 that matter.
 * ========================================================================== */
(function (global) {
	'use strict';

	var $ = global.jQuery;

	/** entity(lowercase) -> {entity, label}. Populated once, on first grid draw. */
	var SPECS = null;
	var loading = null;

	function ctx() {
		return global.serverContext || '/';
	}

	function esc(s) {
		return (typeof global.escHtml === 'function')
			? global.escHtml(s == null ? '' : String(s))
			: String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
				return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
			});
	}

	function tr(key, fallback) {
		if (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key)) {
			return global.t(key);
		}
		return fallback;
	}

	/**
	 * Fetch the importable-entity list once and cache it.
	 *
	 * Failure resolves to an EMPTY map rather than rejecting: the only question this answers is "draw the
	 * buttons?", and when the service cannot be reached the honest answer is no. A grid must never fail to
	 * render because an optional feature could not introduce itself.
	 */
	function loadSpecs() {
		if (SPECS) { return $.Deferred().resolve(SPECS).promise(); }
		if (loading) { return loading; }

		loading = $.get(ctx() + 'import/entities').then(function (resp) {
			var list = (resp && (resp.collection || resp.object)) || [];
			SPECS = {};
			for (var i = 0; i < list.length; i++) {
				if (list[i] && list[i].entity) { SPECS[String(list[i].entity).toLowerCase()] = list[i]; }
			}
			return SPECS;
		}, function () {
			SPECS = {};
			return SPECS;
		});
		return loading;
	}

	/**
	 * Kick the fetch off at DOM ready, then back-fill any grid that was drawn before it landed.
	 *
	 * The back-fill is not belt-and-braces, it is the fix for a real race: importButtons() is called
	 * synchronously while DataTables builds the toolbar, so a grid opened in the same tick as page load
	 * would draw no buttons and keep none until the section was re-opened. Delaying every grid on the
	 * platform behind one optional fetch would be the worse trade, so the buttons arrive late instead.
	 */
	$(function () {
		loadSpecs().then(function () { backfillCurrentTable(); });
	});

	/** Add the buttons to a table that was already built, using the Buttons API rather than a redraw. */
	function backfillCurrentTable() {
		try {
			var dt = global.datatable;
			if (!dt || typeof dt.button !== 'function') { return; }
			if ($('.btn-import-template').length) { return; }          // already drawn

			var buttons = global.importButtons(global.tableV);
			for (var i = 0; i < buttons.length; i++) { dt.button().add(null, buttons[i]); }
		} catch (e) {
			// A toolbar that could not gain two buttons must never break the grid under it.
			if (global.console && console.debug) { console.debug('data-import: backfill skipped', e); }
		}
	}

	// ── the preview panel ───────────────────────────────────────────────────────────────────────────────────

	function closePanel() {
		$('#importPanel').remove();
		$(document).off('keydown.importPanel');
	}

	/**
	 * Render the dry-run report and, when there is something to create, offer the commit.
	 *
	 * Its own panel rather than uiConfirm, which renders its message with textContent and so cannot show a
	 * table of refused rows. The OK button still carries data-ui-confirm="ok", so Cypress drives this exactly
	 * as it drives every other confirm on the platform.
	 */
	function showPreview(entity, label, csv, report, onDone) {
		closePanel();

		var fileError = report && report.fileError;
		var rows = (report && report.rows) || [];
		var toCreate = (report && report.toCreate) || 0;
		var skipped = (report && report.skipped) || 0;
		var refused = (report && report.refused) || 0;

		var errorRows = rows.filter(function (r) { return r.status === 'ERROR'; });
		var skipRows = rows.filter(function (r) { return r.status === 'SKIP'; });

		var html = '' +
			'<div class="uiC-backdrop" id="importPanel">' +
			'  <div class="uiC-card" role="dialog" aria-modal="true" style="max-width:760px">' +
			'    <div class="uiC-head" style="background:linear-gradient(135deg,#2b6cb0,#1f4e8c)">' +
			'      <div class="uiC-badge" aria-hidden="true">' + (refused ? '!' : '✓') + '</div>' +
			'      <h3 class="uiC-title">' + esc(label) + ' — ' + esc(tr('ui.js.importPreview', 'import preview')) + '</h3>' +
			'    </div>' +
			'    <div class="uiC-body" style="max-height:60vh;overflow:auto">';

		if (fileError) {
			// A file-level refusal: nothing was read, so there are no rows to show.
			html += '<p style="color:#b91c1c;font-weight:600">' + esc(fileError) + '</p>';
		} else {
			html += '' +
				'<p style="margin-bottom:10px">' +
				'  <strong>' + toCreate + '</strong> ' + esc(tr('ui.js.importToCreate', 'to create')) +
				'  &nbsp;·&nbsp; <strong>' + skipped + '</strong> ' + esc(tr('ui.js.importAlreadyExist', 'already exist')) +
				'  &nbsp;·&nbsp; <strong style="color:' + (refused ? '#b91c1c' : 'inherit') + '">' + refused + '</strong> ' +
				esc(tr('ui.js.importRefused', 'refused')) +
				'</p>';

			if (refused) {
				// Always expanded, first. This is the list the operator has to act on.
				html += '<h4 style="color:#b91c1c;margin:12px 0 6px">' +
					esc(tr('ui.js.importRefusedRows', 'Rows that cannot be imported')) + '</h4>' +
					'<table class="table table-condensed table-bordered" id="importErrors"><tbody>';
				errorRows.forEach(function (r) {
					html += '<tr><td style="width:70px">' + esc(tr('ui.js.importRow', 'Row')) + ' ' + esc(r.rowNumber) +
						'</td><td>' + esc(r.message) + '</td></tr>';
				});
				html += '</tbody></table>' +
					'<p class="help-block">' +
					esc(tr('ui.js.importNothingWritten', 'Nothing will be imported until every row is valid.')) +
					'</p>';
			}

			if (skipped) {
				// Collapsed: not a call to action. Expandable for the operator who wants to check.
				html += '<details style="margin-top:10px"><summary style="cursor:pointer">' +
					esc(tr('ui.js.importSkippedRows', 'Already in the system')) + ' (' + skipped + ')</summary>' +
					'<table class="table table-condensed" id="importSkips"><tbody>';
				skipRows.forEach(function (r) {
					html += '<tr><td style="width:70px">' + esc(tr('ui.js.importRow', 'Row')) + ' ' + esc(r.rowNumber) +
						'</td><td>' + esc(r.message) + '</td></tr>';
				});
				html += '</tbody></table></details>';
			}
		}

		html += '' +
			'    </div>' +
			'    <div class="uiC-foot">' +
			'      <button type="button" class="uiC-btn uiC-cancel" id="importCancel">' +
			esc(tr('ui.js.importClose', 'Close')) + '</button>';

		if (!fileError && rows.length) {
			html += '      <button type="button" class="uiC-btn" id="importReport" style="background:#6b7280">' +
				esc(tr('ui.js.importDownloadReport', 'Download report')) + '</button>';
		}

		// The confirm is offered ONLY when a commit would actually do something, and its label carries the
		// CREATE count — never the file's row count. A button reading "Import 500" that creates 20 is how an
		// operator concludes the feature is broken.
		var canCommit = !fileError && refused === 0 && toCreate > 0;
		if (canCommit) {
			html += '      <button type="button" class="uiC-btn uiC-ok" id="importConfirm" ' +
				'data-ui-confirm="ok" style="background:#1f7a3f">' +
				esc(tr('ui.js.importConfirm', 'Import')) + ' ' + toCreate + '</button>';
		}

		html += '    </div></div></div>';

		// The panel reuses the shared dialog chrome (.uiC-*), which is injected lazily by
		// confirm-dialog.js. Without this the stylesheet may not exist yet, and .uiC-backdrop starts at
		// opacity:0 — so the preview rendered INVISIBLY and the operator saw nothing happen at all.
		if (typeof global.uiDialogStyles === 'function') { global.uiDialogStyles(); }

		$('body').append(html);
		// .is-open is what fades the backdrop in; without it the panel stays fully transparent.
		global.requestAnimationFrame(function () { $('#importPanel').addClass('is-open'); });

		$('#importCancel').on('click', closePanel);
		$(document).on('keydown.importPanel', function (e) { if (e.key === 'Escape') { closePanel(); } });

		$('#importReport').on('click', function () { postDownload(entity, csv, 'report.csv'); });

		$('#importConfirm').on('click', function () {
			var $btn = $(this).prop('disabled', true);
			$.ajax({
				type: 'POST', url: ctx() + 'import/' + entity + '/commit',
				contentType: 'application/json', dataType: 'json',
				data: JSON.stringify({ csv: csv })
			}).done(function (resp) {
				closePanel();
				if (resp && resp.status === 'SUCCESS') {
					if (typeof global.showSaleSuccess === 'function') { global.showSaleSuccess(resp.message); }
					else if (typeof global.uiAlert === 'function') { global.uiAlert(resp.message); }
					if (typeof onDone === 'function') { onDone(); }
				} else {
					fail((resp && resp.message) || tr('ui.js.importFailed', 'The import could not be completed.'));
				}
			}).fail(function () {
				$btn.prop('disabled', false);
				fail(tr('ui.js.importFailed', 'The import could not be completed.'));
			});
		});
	}

	function fail(message) {
		if (typeof global.uiAlert === 'function') { global.uiAlert({ title: 'Import', message: message }); }
		else if (typeof global.showFormError === 'function') { global.showFormError(message); }
	}

	/**
	 * POST the file and save whatever comes back.
	 *
	 * A form submit rather than $.ajax: the response is a download, and an XHR would hand us the text with no
	 * way to give the browser its filename. The form is removed immediately afterwards.
	 */
	function postDownload(entity, csv, endpoint) {
		var $form = $('<form>', {
			method: 'POST',
			action: ctx() + 'import/' + entity + '/' + endpoint,
			target: '_blank'
		});
		// The proxy takes JSON; a form post cannot send it, so the report route accepts the CSV as a field.
		$form.append($('<textarea>', { name: 'csv' }).val(csv));
		$('body').append($form);
		$form[0].submit();
		$form.remove();
	}

	// ── the file picker ─────────────────────────────────────────────────────────────────────────────────────

	function pickAndValidate(entity, label, onDone) {
		var $input = $('<input type="file" accept=".csv,text/csv" style="display:none">');
		$('body').append($input);

		$input.on('change', function () {
			var file = this.files && this.files[0];
			if (!file) { $input.remove(); return; }

			var reader = new FileReader();
			reader.onload = function (e) {
				var csv = e.target.result;
				$input.remove();

				$.ajax({
					type: 'POST', url: ctx() + 'import/' + entity + '/validate',
					contentType: 'application/json', dataType: 'json',
					data: JSON.stringify({ csv: csv })
				}).done(function (resp) {
					if (resp && resp.status === 'SUCCESS' && resp.object) {
						showPreview(entity, label, csv, resp.object, onDone);
					} else {
						fail((resp && resp.message) || tr('ui.js.importUnreadable',
							'That file could not be read. Check it is the downloaded template.'));
					}
				}).fail(function (xhr) {
					fail(xhr && xhr.status === 403
						? tr('ui.js.importNotAllowed', 'You do not have permission to import.')
						: tr('ui.js.importUnreadable',
							'That file could not be read. Check it is the downloaded template.'));
				});
			};
			// UTF-8: the template is written UTF-8 and Excel may re-save it with a BOM, which the server strips.
			reader.readAsText(file, 'UTF-8');
		});

		$input.trigger('click');
	}

	// ── the public factory ──────────────────────────────────────────────────────────────────────────────────

	/**
	 * DataTables button configs for a grid, or [] when this entity is not importable.
	 *
	 * Called synchronously while the table is being built, so it answers from the cache. On the very first
	 * draw the cache may still be filling — the buttons then appear on the next section open, which is a
	 * better trade than delaying every grid on this platform behind one optional fetch.
	 */
	global.importButtons = function (entity, onDone) {
		if (!entity || !SPECS) { return []; }
		var spec = SPECS[String(entity).toLowerCase()];
		if (!spec) { return []; }

		var refresh = onDone || function () {
			if (typeof global.loadDataTable === 'function') { global.loadDataTable(); }
		};

		return [
			{
				text: tr('ui.js.importTemplate', 'Template'),
				className: 'btn-import-template',
				action: function () {
					// A plain navigation: the server sets Content-Disposition, so the browser saves it.
					global.location.href = ctx() + 'import/' + spec.entity + '/template.csv';
				}
			},
			{
				text: tr('ui.js.importCsv', 'Import CSV'),
				className: 'btn-import-csv',
				action: function () { pickAndValidate(spec.entity, spec.label, refresh); }
			}
		];
	};

	/** Exposed for tests and for a screen that wants to know before drawing. */
	global.importSpecsReady = loadSpecs;

})(window);
