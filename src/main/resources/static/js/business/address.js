/*
 * SRA-1 — City and Area from the business's OWN address list, with "+ New" in place, on the customer and supplier
 * forms. Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §4.
 *
 * Markup (businessDashboard.html): a [data-sra-address] group holding a [data-sra-city] select and a [data-sra-area]
 * select, each with a "+ New city" / "+ New area" link ([data-sra-new-for], shown only to members allowed
 * area.create — ui_menu action.area.add). The links open a small form OVER the customer / supplier form.
 *
 * ⭐ BOTH SELECTS ARE SEARCHABLE PICKERS. searchable-selects.js wraps every <select> in bootstrap-select (live search)
 * and hides the original, so every change made here from code ends in refresh() — and the picker ignores options
 * marked hidden, so the Area list is REBUILT for the chosen city rather than filtered in place.
 *
 * ⭐ AN EDIT NEEDS THE STORED AREA TO BE AN OPTION. editRecord() (main.js) fills the form by matching each field's id to
 * a hidden cell of the grid row (#customerAreaId) — and main.js calls resetForm() just before it. That reset is where
 * the Area list is rebuilt with EVERY area (synchronously, see the 'reset' handler), so editRecord can select the
 * stored one; openCrudModal (wrapped below) then sets the city from it and narrows the list to that city.
 *
 * The dialog lives OUTSIDE every form (appended to <body>), so nothing typed in it can ride along with a customer
 * save — populateFormData() posts every element of the form that has an id.
 */
(function (global, $) {
    'use strict';
    if (!$) return;

    var cities = [], areas = [], loaded = null;

    function tr(key, fallback) {
        return (typeof global.tHas === 'function' && typeof global.t === 'function' && global.tHas(key)) ? global.t(key) : fallback;
    }
    function esc(s) { return $('<div>').text(s == null ? '' : String(s)).html(); }
    function ctx() { return (typeof global.serverContext === 'string') ? global.serverContext : '/'; }
    function ok(r) { return r && (r.status === 'SUCCESS' || r.status === 'FOUND'); }

    /** The lists, fetched once per page (small: a business has tens of areas, not thousands). */
    function load(force) {
        if (loaded && !force) return loaded;
        loaded = $.when(
            $.ajax({ url: ctx() + 'getCities', nonBlocking: true, global: false }),
            $.ajax({ url: ctx() + 'getAreas', nonBlocking: true, global: false })
        ).then(function (c, a) {
            // A LIST arrives in `collection` (GenericResponse puts a List there, a single row in `object`) — reading
            // `object` here left both pickers empty. Probed on the live stack 14 Oct before any screen was opened.
            cities = (c[0] && (c[0].collection || c[0].object)) || [];
            areas = (a[0] && (a[0].collection || a[0].object)) || [];
            renderAll();
        }, function () { loaded = null; });
        return loaded;
    }

    function areaById(id) {
        id = Number(id);
        for (var i = 0; i < areas.length; i++) if (Number(areas[i].id) === id) return areas[i];
        return null;
    }

    /** "Zahir Pir, Khanpur" for a grid row, or '' — the label every screen uses for a filed customer or supplier. */
    function label(areaId) {
        var a = areaId ? areaById(areaId) : null;
        return a ? (a.name + (a.cityName ? ', ' + a.cityName : '')) : '';
    }

    function active(row) { return !row.status || String(row.status).toUpperCase() === 'ACTIVE'; }

    /** The searchable picker (searchable-selects.js) over a select redraws only when told to. */
    function refresh($sel) {
        if ($sel.data('selectpicker')) { try { $sel.selectpicker('refresh'); } catch (e) {} }
    }

    function renderCities($g) {
        var $c = $g.find('[data-sra-city]'), keep = $c.val();
        var html = '<option value="">' + esc(tr('ui.js.sraChooseCity', '— City —')) + '</option>';
        cities.forEach(function (c) { if (active(c) || String(c.id) === keep) html += '<option value="' + c.id + '">' + esc(c.name) + '</option>'; });
        $c.html(html).val(keep || '');
        refresh($c);
    }

    /**
     * Rebuild the Area list: the chosen city's areas, or EVERY area when `all` (the reset before an edit — see the
     * header). A selected area of another city is dropped unless `all`.
     */
    function renderAreas($g, all) {
        var $a = $g.find('[data-sra-area]'), keep = $a.val() || '0';
        var city = $g.find('[data-sra-city]').val();
        // value 0 = "no area": the server clears the stored one. An empty value would be read as "not sent", and an
        // edit would then KEEP the old area while the screen said none.
        var html = '<option value="0">' + esc(tr('ui.js.sraNoArea', '— No area —')) + '</option>';
        areas.forEach(function (a) {
            var inCity = all || (!!city && String(a.cityId) === String(city));
            if (inCity && (active(a) || String(a.id) === keep))
                html += '<option value="' + a.id + '" data-city="' + a.cityId + '">' + esc(a.name) + '</option>';
        });
        $a.html(html).val(keep);
        if ($a.val() == null) $a.val('0');
        refresh($a);
    }

    /** Narrow the Area list to the chosen city. */
    function filterAreas($g) { renderAreas($g, false); }

    /** The printed city follows the picked city; with none picked, a city typed before SRA-1 stays and is shown. */
    function syncCityText($g) {
        var $text = $g.closest('form').find('#customerCity');
        if (!$text.length) return;   // suppliers have no printed city column
        var id = $g.find('[data-sra-city]').val();
        var c = id ? cities.filter(function (x) { return String(x.id) === String(id); })[0] : null;
        if (c) $text.val(c.name);
        var $legacy = $g.find('[data-sra-legacy]');
        var typed = $.trim($text.val() || '');
        if (!c && typed) {
            $legacy.text(tr('ui.js.sraTypedBefore', 'Typed before: {0} — choose its city and area to file it.').replace('{0}', typed)).prop('hidden', false);
        } else {
            $legacy.text('').prop('hidden', true);
        }
    }

    /** After editRecord() has selected the stored area: set the city from it. */
    function syncFromArea($g) {
        var $a = $g.find('[data-sra-area]'), $c = $g.find('[data-sra-city]');
        var a = areaById($a.val());
        $c.val(a ? String(a.cityId) : '');
        refresh($c);
        filterAreas($g);
        if (a) { $a.val(String(a.id)); refresh($a); }
        syncCityText($g);
    }

    function renderAll() {
        $('[data-sra-address]').each(function () {
            var $g = $(this);
            renderCities($g);
            renderAreas($g, true);
            syncFromArea($g);
        });
        relabel();
    }

    /** Grid rows drawn before the lists arrived carry the area id; name them now. */
    function relabel() {
        $('[data-sra-label][data-area-id]').each(function () {
            var id = $(this).attr('data-area-id');
            if (id) $(this).text(label(id));
        });
    }

    // ── "+ New city" / "+ New area": a small form OVER the customer / supplier form ───────────────────────────────
    //
    // The purchase form's "+ New product" pattern (business.js PUR-INLINE), at the size an address needs: the link
    // under the picker opens a crud-overlay ON TOP of the form being filled (overlays stack by document order, so this
    // one is appended to <body> — it is always last, always on top). Save closes it and comes back with the new row
    // SELECTED on the form underneath, a message saying so, and the cursor on the next field. Esc closes only it.
    //
    // "+ New area" can make the city in the same step ("…or a new city"), so filing a shop in a town not yet on the
    // list is one dialog, not two. Both answers from the server select the row: SUCCESS (made now) and FOUND (it
    // already existed — the operator meant that one, and is told so).
    var $modal = null, origin = null;   // origin = the [data-sra-address] group that opened the dialog

    function modal() {
        if ($modal) return $modal;
        $modal = $(
            '<div id="SraAddressModal" class="crud-overlay" data-kbd-custom role="dialog" aria-modal="true" aria-labelledby="SraAddressTitle">'
            + '<div class="crud-box" style="max-width:460px">'
            + '<div class="crud-head"><h4 id="SraAddressTitle"></h4>'
            + '<button type="button" class="crud-x" data-sra-dlg-cancel aria-label="Close">&times;</button></div>'
            + '<div class="crud-body">'
            + '<div class="form-group" data-sra-dlg-city-row><label for="sraDlgCity"></label>'
            + '<select class="form-control" id="sraDlgCity" data-no-search></select>'
            + '<input type="text" class="form-control" id="sraDlgNewCity" maxlength="80" style="margin-top:6px"></div>'
            + '<div class="form-group" data-sra-dlg-name-row><label for="sraDlgName"></label>'
            + '<input type="text" class="form-control" id="sraDlgName" maxlength="80"></div>'
            + '<div class="text-danger" data-sra-dlg-msg style="min-height:18px;font-size:13px"></div>'
            + '<div class="text-right"><button type="button" class="btn btn-default" data-sra-dlg-cancel></button> '
            + '<button type="button" class="btn btn-success" id="sraDlgSave" data-sra-dlg-save></button></div>'
            + '</div></div></div>'
        ).appendTo(document.body);
        $modal.find('[data-sra-dlg-cancel]').last().text(tr('ui.js.cancel', 'Cancel'));
        $modal.find('[data-sra-dlg-save]').text(tr('ui.js.sraSave', 'Save'));
        return $modal;
    }

    function openDialog($g, kind) {
        origin = $g;
        var $m = modal(), isCity = kind === 'city';
        $m.attr('data-kind', kind);
        $m.find('#SraAddressTitle').text(isCity ? tr('ui.js.sraNewCityTitle', 'New city') : tr('ui.js.sraNewAreaTitle', 'New area'));
        $m.find('[data-sra-dlg-msg]').text('');
        // City row: for "New city" it is just the name; for "New area" it is the existing cities (pre-set from the
        // form) with a box under it for a city not on the list yet.
        var $sel = $m.find('#sraDlgCity'), $newCity = $m.find('#sraDlgNewCity');
        $m.find('label[for="sraDlgCity"]').text(tr('ui.js.sraCity', 'City'));
        if (isCity) {
            $sel.hide();
            $newCity.val('').attr('placeholder', tr('ui.js.sraNewCity', 'New city name')).css('margin-top', 0);
            $m.find('[data-sra-dlg-name-row]').hide();
        } else {
            var html = '<option value="">' + esc(tr('ui.js.sraChooseCity', '— City —')) + '</option>';
            cities.forEach(function (c) { if (active(c)) html += '<option value="' + c.id + '">' + esc(c.name) + '</option>'; });
            $sel.html(html).val($g.find('[data-sra-city]').val() || '').show();
            $newCity.val('').attr('placeholder', tr('ui.js.sraOrNewCity', '…or type a new city')).css('margin-top', '6px');
            $m.find('[data-sra-dlg-name-row]').show();
            $m.find('label[for="sraDlgName"]').text(tr('ui.js.sraArea', 'Area'));
            $m.find('#sraDlgName').val('').attr('placeholder', tr('ui.js.sraNewArea', 'New area name'));
        }
        global.openModal('SraAddressModal');   // crud-modal.js: opens at the top
        setTimeout(function () { (isCity || !$sel.val() ? $newCity : $m.find('#sraDlgName')).trigger('focus'); }, 30);
    }

    function closeDialog() {
        if ($modal) global.closeModal('SraAddressModal');
        origin = null;
    }

    function post(path, data) {
        return $.ajax({ type: 'POST', url: ctx() + path, data: data, nonBlocking: true });
    }

    function failText(x) {
        return (x && x.responseJSON && x.responseJSON.message) || tr('ui.js.sraNotSaved', 'Not saved.');
    }

    /** The city: a name typed into "…or a new city" wins over the picker; it is made (or found) first. */
    function cityFor(kind) {
        var $m = modal(), typed = $.trim($m.find('#sraDlgNewCity').val() || '');
        var picked = kind === 'area' ? $m.find('#sraDlgCity').val() : '';
        if (!typed && picked) {
            return $.Deferred().resolve({ row: cities.filter(function (c) { return String(c.id) === String(picked); })[0] }).promise();
        }
        if (!typed) {
            return $.Deferred().reject(kind === 'city' ? tr('ui.js.sraNameRequired', 'Type a name.')
                                                       : tr('ui.js.sraCityFirst', 'Choose the city first.')).promise();
        }
        return post('addCity', { name: typed }).then(function (r) {
            if (!ok(r) || !r.object) return $.Deferred().reject((r && r.message) || tr('ui.js.sraNotSaved', 'Not saved.')).promise();
            cities.push(r.object);
            return { row: r.object, found: r.status === 'FOUND' };
        }, failText);
    }

    function saveDialog() {
        var $m = modal(), kind = $m.attr('data-kind'), $g = origin;
        var $msg = $m.find('[data-sra-dlg-msg]').text('');
        var areaName = $.trim($m.find('#sraDlgName').val() || '');
        if (kind === 'area' && !areaName) { $msg.text(tr('ui.js.sraNameRequired', 'Type a name.')); return; }
        var $btn = $m.find('#sraDlgSave').prop('disabled', true);
        cityFor(kind).then(function (c) {
            if (kind === 'city') return { city: c.row, area: null, found: !!c.found, name: c.row.name };
            return post('addArea', { cityId: c.row.id, name: areaName }).then(function (r) {
                if (!ok(r) || !r.object) return $.Deferred().reject((r && r.message) || tr('ui.js.sraNotSaved', 'Not saved.')).promise();
                areas.push(r.object);
                return { city: c.row, area: r.object, found: r.status === 'FOUND', name: r.object.name };
            }, failText);
        }).then(function (res) {
            dedupe();
            // Every address group on the page sees the new rows (customer AND supplier forms).
            $('[data-sra-address]').each(function () { renderCities($(this)); });
            if ($g && $g.length) {
                $g.find('[data-sra-city]').val(String(res.city.id));
                refresh($g.find('[data-sra-city]'));
                $g.find('[data-sra-area]').val('0');
                filterAreas($g);
                if (res.area) { $g.find('[data-sra-area]').val(String(res.area.id)); refresh($g.find('[data-sra-area]')); }
                syncCityText($g);
            }
            closeDialog();
            var said = res.found ? tr('ui.js.sraExistedSelected', '"{0}" already existed — selected.')
                                 : tr('ui.js.sraAddedSelected', '"{0}" added and selected.');
            if (typeof global.showSaleSuccess === 'function') global.showSaleSuccess(said.replace('{0}', res.name));
            // Land where the operator was heading: a new city → its area; a new area → the next field of the form.
            if ($g && $g.length && global.FocusFlow && global.FocusFlow.mayAutoFocus()) {
                var $next = res.area ? $g.nextAll('.form-group').first().find('input,select').filter(':visible').first()
                                     : $g.find('[data-sra-area]');
                var $picker = $next.closest('.bootstrap-select').find('button').first();
                ($picker.length ? $picker : $next).trigger('focus');
            }
        }, function (reason) {
            $msg.text(typeof reason === 'string' ? reason : tr('ui.js.sraNotSaved', 'Not saved.'));
        }).always(function () { $btn.prop('disabled', false); });
    }

    function dedupe() {
        function uniq(list) {
            var seen = {};
            return list.filter(function (x) { if (!x || seen[x.id]) return false; seen[x.id] = 1; return true; });
        }
        cities = uniq(cities).sort(function (a, b) { return String(a.name).localeCompare(String(b.name)); });
        areas = uniq(areas).sort(function (a, b) { return String(a.name).localeCompare(String(b.name)); });
    }

    $(document)
        .on('change', '[data-sra-city]', function () {
            var $g = $(this).closest('[data-sra-address]');
            filterAreas($g);
            syncCityText($g);
        })
        .on('click', '[data-sra-new-for]', function () {
            var $g = $(this).closest('[data-sra-address]'), kind = $(this).attr('data-sra-new-for');
            load().always(function () { openDialog($g, kind); });
        })
        .on('click', '[data-sra-dlg-save]', saveDialog)
        .on('click', '[data-sra-dlg-cancel]', closeDialog)
        .on('keydown', '#SraAddressModal', function (e) {
            // Its own keys (data-kbd-custom): Enter saves, Esc closes ONLY this dialog — never the form under it.
            if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); saveDialog(); }
            if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeDialog(); }
        })
        // A form reset (New Customer / New Supplier, Save & Add Another): re-sync after it.
        .on('reset', 'form', function () {
            var $f = $(this);
            // ⚠ A HIDDEN input is not cleared by form.reset(): setting its value sets its default too, so the last
            // edited customer's city would ride into "New Customer". Cleared here — the reset event runs BEFORE the
            // fields reset, and before registerAddAnother restores the fields it keeps (customerCity among them).
            $f.find('#customerCity').val('');
            // Every area back in the list NOW, before main.js's editRecord() selects the stored one (see the header).
            $f.find('[data-sra-address]').each(function () { renderAreas($(this), true); });
            setTimeout(function () {
                $f.find('[data-sra-address]').each(function () { syncFromArea($(this)); });
            }, 0);
        });

    // After main.js has filled the form for an EDIT, it opens the overlay: set the city from the stored area then.
    $(function () {
        var open = global.openCrudModal;
        if (typeof open === 'function') {
            global.openCrudModal = function (entity) {
                var r = open.apply(this, arguments);
                var $g = $('#' + entity).find('[data-sra-address]');
                if ($g.length) load().always(function () { syncFromArea($g); });
                return r;
            };
        }
        if ($('[data-sra-address]').length) load();
    });

    // A grid redraw (paging, search, a fresh load) draws rows from the row data; label them whenever one finishes.
    $(document).on('draw.dt', relabel);

    global.SraAddress = { load: load, label: label, areaById: areaById };
})(window, window.jQuery);
