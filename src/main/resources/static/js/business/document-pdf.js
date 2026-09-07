/*
 * OMS O8 slice 4 — sale documents as a DOWNLOADABLE PDF.
 *
 * ── Why this is a separate file, and what it deliberately does not contain ──────────────────────────────────
 *
 * Nothing in the back office downloaded before this. Printing goes through a hidden iframe to window.print(),
 * which hands a shopkeeper paper and leaves a manager with nothing to keep, attach to an email, or search.
 *
 * pdfmake speaks tables, not HTML, so a second emitter is unavoidable. What IS avoidable — and what this file
 * exists to avoid — is a second LAYOUT. Every label, every cell, every totals row here comes from
 * DocumentRenderer.toPrintModel, which walks the same field whitelist and calls the same resolvers the printed
 * document does. So this file decides how a resolved document is DRAWN, and never what it says.
 *
 * The rule to keep: if you find yourself deciding here whether a row should appear, or what a column is called,
 * the logic belongs in receipt.js instead.
 *
 * ── Lazily loaded ──────────────────────────────────────────────────────────────────────────────────────────
 *
 * pdfmake plus its font file is ~900KB gzipped. It is fetched on the FIRST download via
 * LazyExport.ensurePdfMake — the same loader the grid exports use — so the overwhelming majority of users, who
 * never export a document, never pay for it.
 */
(function (global, $) {
    'use strict';

    function tr(key, fallback) {
        return (typeof global.t === 'function' && typeof global.tHas === 'function' && global.tHas(key))
            ? global.t(key) : fallback;
    }

    function fail(msgKey, fallback) {
        if (global.showFormError) { global.showFormError(tr(msgKey, fallback)); }
    }

    /*
     * The page geometry, in pdfmake points, stated ONCE.
     *
     * The margins used to live only inside the document definition while columnWidths guessed at the page
     * separately. Column widths have to be measured against the printable area, so the two cannot be allowed
     * to hold different ideas of it.
     */
    var PAGE_MARGINS = [24, 22, 24, 28];          // left, top, right, bottom
    var PAGE_WIDTH = { A4: 595.28, A5: 419.53 };  // ISO 216 at 72dpi
    /** Horizontal padding a table layout adds per column, reserved so the table cannot exceed the page. */
    var CELL_PADDING = 8;

    /** Which sheet this document prints on. The one rule, used by both the widths and the definition. */
    function paperOf(model) {
        return model.paper === 'A5' ? 'A5' : 'A4';
    }

    /**
     * Column widths as ABSOLUTE POINTS, proportional to the profile's percentages.
     *
     * <h3>⚠ The defect this replaces: pdfmake has no weighted star widths</h3>
     * This returned `c.width + '*'` — `'5*'`, `'39*'` — under a comment claiming "pdfmake takes star-weights,
     * and a percentage is exactly a weight". <b>It does not.</b> pdfmake accepts `'auto'`, a bare `'*'`, or a
     * number, and nothing else. Given `'5*'` it left `_calcWidth` as that STRING, then added it to the running
     * x-offset — so a numeric accumulator turned into text and the library threw
     * `unsupported number: 085*1639*1612*1615*1613*0024`, which is those widths concatenated with the cell
     * paddings between them.
     *
     * <p><b>Every PDF whose profile declares column widths failed</b> — which is every built-in preset — and
     * it failed SILENTLY, because both emitters wrap the render in `.catch(fail)`. No file, no error. That is
     * the larger half of the original "Download PDF does nothing" report; the browser's multiple-download
     * rule (see downloadInvoicesPdf) is the other half.
     *
     * <p>A bare `'*'` per column would render, but it divides the page EQUALLY and throws away the designed
     * proportions — an item description would get the same width as a line number. Percentages are turned
     * into points against the printable area instead, which pdfmake supports unambiguously and which keeps
     * the layout the owner designed.
     *
     * <p>Normalised by the actual sum rather than assuming 100, so a profile whose widths total 90 or 110
     * still fills the page in the right proportions.
     */
    function columnWidths(model, paper) {
        var cols = model.columns;
        var declared = cols.filter(function (c) { return c.width; }).length;
        // A profile with no widths gets equal columns, which is what the HTML renderer does too.
        if (declared !== cols.length) {
            return cols.map(function () { return '*'; });
        }
        var total = cols.reduce(function (sum, c) { return sum + (Number(c.width) || 0); }, 0);
        if (!(total > 0)) {
            return cols.map(function () { return '*'; });
        }
        var available = PAGE_WIDTH[paper] - PAGE_MARGINS[0] - PAGE_MARGINS[2] - (CELL_PADDING * cols.length);
        return cols.map(function (c) { return available * (Number(c.width) || 0) / total; });
    }

    function lineTable(model, paper) {
        var head = model.columns.map(function (c) {
            return { text: c.label, style: 'th', alignment: c.align };
        });
        var body = [head];
        model.rows.forEach(function (row) {
            body.push(row.map(function (cell, i) {
                return { text: String(cell == null ? '' : cell), alignment: model.columns[i].align };
            }));
        });
        return {
            table: { headerRows: 1, widths: columnWidths(model, paper), body: body },
            layout: 'lightHorizontalLines',
            fontSize: 8
        };
    }

    /** The letterhead, only as far as the model actually carries it — a blank line is worse than no line. */
    function letterhead(model) {
        var lh = model.letterhead || {};
        var out = [];
        if (lh.storeName) out.push({ text: lh.storeName, style: 'brand' });
        var addr = [lh.address, lh.city].filter(Boolean).join('  ');
        if (addr) out.push({ text: addr, style: 'brandSub' });
        var lic = [];
        if (lh.licenseNo) lic.push(tr('ui.js.docLicenseNo', 'License No') + ': ' + lh.licenseNo);
        if (lh.phone) lic.push(lh.phone);
        if (lic.length) out.push({ text: lic.join('   ·   '), style: 'brandSub' });
        return out;
    }

    function headerBlock(model) {
        if (!model.headerFields.length) return null;
        // Three columns of label/value pairs, mirroring the A4 profile's own three header groups.
        var per = Math.ceil(model.headerFields.length / 3);
        var groups = [[], [], []];
        model.headerFields.forEach(function (f, i) {
            groups[Math.min(Math.floor(i / per), 2)].push(f.label + ': ' + f.value);
        });
        return {
            columns: groups.map(function (g) { return { text: g.join('\n'), fontSize: 8 }; }),
            columnGap: 12,
            margin: [0, 0, 0, 8]
        };
    }

    function totalsBlock(model) {
        if (!model.totals.length) return null;
        var body = model.totals.map(function (r) {
            return [
                { text: r.label, alignment: 'right', bold: r.strong === true },
                { text: String(r.value), alignment: 'right', bold: r.strong === true }
            ];
        });
        return {
            // Right-aligned block, as on the paper document: the reader's eye goes to the bottom-right for the
            // figure that matters.
            columns: [
                { text: '', width: '*' },
                { width: 'auto', table: { widths: ['auto', 'auto'], body: body }, layout: 'noBorders', fontSize: 9 }
            ],
            margin: [0, 8, 0, 0]
        };
    }

    function signatureBlock(model) {
        if (!model.signature.length) return null;
        return {
            margin: [0, 26, 0, 0],
            fontSize: 8,
            columns: model.signature.map(function (label) {
                // An underscore rule rather than a border: it survives a photocopy, which these do.
                return { text: label + '\n____________________' };
            }),
            columnGap: 10
        };
    }

    /**
     * Build and download one document.
     *
     * @param invoiceNo the invoice to render
     * @param profile   a document profile, or null for whatever the renderer would have chosen
     * @param prefix    filename prefix, e.g. 'challan' — the invoice number is appended
     */
    global.downloadDocumentPdf = function (invoiceNo, profile, prefix) {
        var DR = global.DocumentRenderer;
        if (!DR || typeof DR.toPrintModel !== 'function') {
            return fail('ui.js.pdfUnavailable', 'PDF export is not available.');
        }
        if (!global.LazyExport || typeof global.LazyExport.ensurePdfMake !== 'function') {
            return fail('ui.js.pdfUnavailable', 'PDF export is not available.');
        }

        DR.withInvoice(invoiceNo, function (inv) {
            emitPdf(inv, profile, prefix, invoiceNo);
        });
    };

    /**
     * Emit a PDF from an ALREADY-RESOLVED document object (#28).
     *
     * <p>{@code downloadDocumentPdf} above fetches by invoice number, which a sales quote does not have — it
     * carries a QTE- number and is not an invoice at all. Rather than write a second emitter that would drift
     * from this one (the drift the toPrintModel comment warns about, where a customer ends up holding two
     * versions of the same document), the fetch and the emit are separated and both callers share the emit.
     *
     * @param inv       the renderer's invoice-shaped object, already resolved
     * @param profile   the preset to draw with
     * @param prefix    filename prefix, e.g. 'quote'
     * @param nameHint  what to append to the filename — the document's own number
     */
    global.downloadDocumentPdfFromObject = function (inv, profile, prefix, nameHint) {
        var DR = global.DocumentRenderer;
        if (!DR || typeof DR.toPrintModel !== 'function'
            || !global.LazyExport || typeof global.LazyExport.ensurePdfMake !== 'function') {
            return fail('ui.js.pdfUnavailable', 'PDF export is not available.');
        }
        emitPdf(inv, profile, prefix, nameHint);
    };

    /**
     * The blocks for ONE document, in order. Shared by the single and the batch emitters so a change to a
     * document's shape reaches both — the same reason this file draws from `toPrintModel` rather than
     * deciding anything itself.
     */
    function documentBlocks(model, paper) {
        var content = [];
        letterhead(model).forEach(function (b) { content.push(b); });
        content.push({ text: model.title, style: 'title' });
        var head = headerBlock(model); if (head) content.push(head);
        content.push(lineTable(model, paper));
        var tot = totalsBlock(model); if (tot) content.push(tot);
        if (model.footerText) content.push({ text: model.footerText, style: 'foot' });
        var sign = signatureBlock(model); if (sign) content.push(sign);
        return content;
    }

    /** The page setup and styles, identical for one document or fifty. */
    function docDefinition(model, content) {
        return {
            // paperOf and PAGE_MARGINS are the SAME values columnWidths measured against — a table sized for
            // one page on a document printed at another is how the widths silently stopped fitting.
            pageSize: paperOf(model),
            pageMargins: PAGE_MARGINS,
            content: content,
            styles: {
                brand: { fontSize: 15, bold: true, alignment: 'center' },
                brandSub: { fontSize: 8, alignment: 'center' },
                title: { fontSize: 12, bold: true, alignment: 'center', margin: [0, 8, 0, 8],
                    characterSpacing: 1 },
                th: { bold: true, fillColor: '#eeeeee', fontSize: 8 },
                foot: { fontSize: 8, alignment: 'center', margin: [0, 10, 0, 0] }
            }
        };
    }

    /**
     * ⭐ Every selected invoice in ONE file, one document per page.
     *
     * <h3>The defect this replaces, and why "sequential" was not enough</h3>
     * The report used to fire one download per invoice, 400 ms apart, and its own comment said browsers
     * "throttle or silently drop a burst". They do worse than throttle: Chrome and Edge treat the SECOND
     * automatic download from a page as a permission decision, show "allow multiple downloads?", and
     * <b>silently drop every subsequent file</b> if it is dismissed — or if the origin was ever denied. A
     * shopkeeper selecting twenty invoices got one file, or none, with no error anywhere. That is the whole
     * of "Download PDF does nothing".
     *
     * <p>Spacing the calls cannot fix it, because the limit is on the COUNT, not the rate. One file does.
     *
     * <h3>And it is what the user wanted anyway</h3>
     * A shop downloading a day's invoices is going to print or send them as a batch. Twenty files in a
     * downloads folder is a worse answer than one document with twenty pages, even where the browser allows
     * it.
     *
     * @param invoiceNos the numbers to include, in the order the report shows them
     */
    global.downloadInvoicesPdf = function (invoiceNos, profile, prefix) {
        var DR = global.DocumentRenderer;
        if (!DR || typeof DR.toPrintModel !== 'function' || typeof DR.withInvoice !== 'function'
            || !global.LazyExport || typeof global.LazyExport.ensurePdfMake !== 'function') {
            return fail('ui.js.pdfUnavailable', 'PDF export is not available.');
        }
        var nos = (invoiceNos || []).filter(function (n) { return !!n; });
        if (!nos.length) return fail('ui.js.nothingToPrint', 'There is nothing to download.');

        /*
         * Fetched one at a time and in order, so the pages come out in the order the report showed and a
         * slow server cannot interleave them. `withInvoice` reports its own failures, so a document that
         * cannot be read is SKIPPED rather than taking the whole batch down — nineteen invoices are worth
         * more than an all-or-nothing refusal, and the shopkeeper can see which one is missing.
         */
        var models = [];
        var step = function (i) {
            if (i >= nos.length) return emitBatch();
            DR.withInvoice(nos[i], function (inv) {
                try { models.push(DR.toPrintModel(inv, profile || null)); } catch (ignored) { /* skipped */ }
                step(i + 1);
            });
        };

        function emitBatch() {
            if (!models.length) return fail('ui.js.pdfUnavailable', 'PDF export is not available.');
            global.LazyExport.ensurePdfMake().then(function () {
                var content = [];
                /*
                 * ONE page size for the whole file — docDefinition below takes it from models[0], so every
                 * document's columns must be measured against that same sheet. Measuring each against its own
                 * would size a document's table for a page it is not printed on.
                 */
                var paper = paperOf(models[0]);
                models.forEach(function (m, idx) {
                    documentBlocks(m, paper).forEach(function (b, bi) {
                        // A page break BEFORE every document after the first, set on its first block so no
                        // empty trailing page is produced.
                        if (idx > 0 && bi === 0) {
                            b = $.extend({}, b, { pageBreak: 'before' });
                        }
                        content.push(b);
                    });
                });
                var name = (prefix || 'invoices') + '-' + models.length + '.pdf';
                global.pdfMake.createPdf(docDefinition(models[0], content)).download(name);
            }).catch(function () {
                fail('ui.js.pdfUnavailable', 'PDF export is not available.');
            });
        }

        step(0);
    };

    /**
     * The SINGLE-document emitter. downloadDocumentPdf, downloadDocumentPdfFromObject, downloadChallan
     * and downloadInvoicePdf all end here.
     *
     * <p>downloadInvoicesPdf (the batch) does NOT — it emits many documents into one file. The two
     * share documentBlocks() and docDefinition(), which is where a document's shape actually lives, so
     * a change to either reaches both paths.
     */
    function emitPdf(inv, profile, prefix, nameHint) {
        var DR = global.DocumentRenderer;
        {
            var model = DR.toPrintModel(inv, profile || null);
            global.LazyExport.ensurePdfMake().then(function () {
                var content = documentBlocks(model, paperOf(model));

                // The SAME page setup and styles the batch uses — one document or fifty, see docDefinition.
                // This held its own copy of that object until the batch emitter was added; two copies of a
                // document's shape is how a change reaches one path and not the other.
                global.pdfMake.createPdf(docDefinition(model, content))
                    .download((prefix || 'document') + '-' + (model.invoiceNo || nameHint || '') + '.pdf');
            }).catch(function () {
                fail('ui.js.pdfUnavailable', 'PDF export is not available.');
            });
        }
    }

    /** The per-stop slip the shop signs for. Same renderer, same data — only the profile differs. */
    global.downloadChallan = function (invoiceNo) {
        var PRESETS = global.DocumentRenderer && global.DocumentRenderer.PRESETS;
        global.downloadDocumentPdf(invoiceNo, PRESETS ? PRESETS.DELIVERY_CHALLAN_A4 : null, 'challan');
    };

    /** And the invoice itself, which had no download either until now. */
    global.downloadInvoicePdf = function (invoiceNo) {
        global.downloadDocumentPdf(invoiceNo, null, 'invoice');
    };
})(window, jQuery);
