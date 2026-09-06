/**
 * P1 — thermal printing over ESC/POS, for any shop on any hardware.
 *
 * Design: microservices/docs/slices/p1-thermal-printing-escpos-design.md
 *
 * <h3>The constraint that shapes every decision here</h3>
 * ESC/POS TEXT mode is codepage-based. There is no Urdu codepage, and Nastaliq needs contextual glyph
 * shaping and vertical stacking that no printer firmware performs. A product that switched to ESC/POS text
 * would print English faster and turn an Urdu terms block into garbage.
 *
 * So the default ESC/POS mode is RASTER: the document is drawn to a canvas and sent as a bitmap. The
 * browser does the shaping, which means Urdu, Arabic, Hindi, Chinese and a logo all work for free, on a
 * printer whose firmware has never heard of any of them.
 *
 * <h3>Why there is no html2canvas here</h3>
 * `DocumentRenderer.toPrintModel` already resolves the document to neutral data — the same whitelist and the
 * same resolvers the HTML renderer walks. Drawing THAT to a canvas needs no third-party rasteriser, gives
 * exact control of the 576-dot width a receipt roll actually has, and cannot drift from the paper because
 * both come from one model. Screenshotting the HTML would have been the obvious route and the worse one.
 *
 * <h3>Nothing here is reached unless a tenant asks for it</h3>
 * `pos.document.printMode` defaults to `browser`. Every existing shop keeps the iframe-and-window.print path
 * it has always used; this file is inert until somebody switches it on.
 */
(function (global) {
    'use strict';

    var ESC = 0x1B, GS = 0x1D, LF = 0x0A;

    // ── byte helpers ────────────────────────────────────────────────────────────────────────────

    function concat(parts) {
        var total = 0, i;
        for (i = 0; i < parts.length; i++) total += parts[i].length;
        var out = new Uint8Array(total), at = 0;
        for (i = 0; i < parts.length; i++) { out.set(parts[i], at); at += parts[i].length; }
        return out;
    }

    function bytes(arr) { return new Uint8Array(arr); }

    /**
     * Latin text as printer bytes.
     *
     * <p>Anything outside ASCII becomes '?' DELIBERATELY and only in TEXT mode: a printer's codepage cannot
     * render it, and silently emitting a byte it will interpret as some other glyph would put a wrong
     * character on a customer's receipt. A shop that needs those characters uses raster mode, which is why
     * raster is the default.
     */
    function ascii(s) {
        s = String(s == null ? '' : s);
        var out = new Uint8Array(s.length);
        for (var i = 0; i < s.length; i++) {
            var c = s.charCodeAt(i);
            out[i] = (c >= 0x20 && c <= 0x7E) ? c : 0x3F;
        }
        return out;
    }

    // ── control commands ────────────────────────────────────────────────────────────────────────

    /** ESC @ — reset. Always first: a printer holds the last job's font, alignment and line spacing. */
    function init() { return bytes([ESC, 0x40]); }

    /** GS V — cut. Partial by default: a full cut drops the slip on the floor on many models. */
    function cut(partial) {
        return concat([bytes([LF, LF, LF, LF]), bytes([GS, 0x56, partial === false ? 0x00 : 0x01])]);
    }

    /** ESC p — the cash-drawer kick. Pin 0, 25ms on / 250ms off, the values virtually every till expects. */
    function drawerKick() { return bytes([ESC, 0x70, 0x00, 0x19, 0xFA]); }

    // ── raster ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A canvas as packed 1-bit rows, MSB first.
     *
     * <p>Thermal paper is black or white; there is no grey. Luminance below the threshold becomes a fired
     * dot. 8 pixels per byte, and the row is padded to a byte boundary — which is why the canvas width must
     * be a multiple of 8 (see WIDTHS).
     */
    function canvasToRaster(canvas, threshold) {
        var w = canvas.width, h = canvas.height;
        var img = canvas.getContext('2d').getImageData(0, 0, w, h).data;
        var rowBytes = Math.ceil(w / 8);
        var out = new Uint8Array(rowBytes * h);
        var cut = (threshold == null) ? 128 : threshold;

        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var p = (y * w + x) * 4;
                // Rec. 601 luma. A plain average would render a mid-red logo far too light.
                var lum = 0.299 * img[p] + 0.587 * img[p + 1] + 0.114 * img[p + 2];
                var alpha = img[p + 3];
                // Transparent is PAPER, not ink: an untouched canvas region must not print solid black.
                if (alpha > 32 && lum < cut) {
                    out[y * rowBytes + (x >> 3)] |= (0x80 >> (x & 7));
                }
            }
        }
        return { data: out, rowBytes: rowBytes, height: h };
    }

    /**
     * GS v 0 — print a raster bit image.
     *
     * <p>Width is in BYTES and height in DOTS, both little-endian pairs. Getting that asymmetry wrong is the
     * classic ESC/POS bug: the printer emits a diagonal smear rather than an image, because it reads the
     * wrong number of bytes per row and every row after the first is offset.
     */
    function rasterCommand(raster) {
        var xL = raster.rowBytes & 0xFF, xH = (raster.rowBytes >> 8) & 0xFF;
        var yL = raster.height & 0xFF, yH = (raster.height >> 8) & 0xFF;
        return concat([bytes([GS, 0x76, 0x30, 0x00, xL, xH, yL, yH]), raster.data]);
    }

    // ── drawing the document ────────────────────────────────────────────────────────────────────

    /** Does this string START in a right-to-left script? The same question `dir="auto"` asks. */
    function isRtl(s) {
        var str = String(s == null ? '' : s);
        // The FIRST strong character decides, which is exactly the rule `dir="auto"` applies. A string that
        // opens with "Rs 7,430" and ends in Urdu is a left-to-right line with Urdu in it, not an RTL line.
        var first = str.search(/[A-Za-z֐-߿יִ-﷽ﹰ-ﻼ]/);
        return first >= 0 && /[֐-߿יִ-﷽ﹰ-ﻼ]/.test(str.charAt(first));
    }

    /**
     * Draw one string, wrapping to the available width, and return the y after it.
     *
     * <p>`ctx.direction` is set per string rather than per document, for exactly the reason the HTML renderer
     * puts `dir="auto"` on elements and never on `<html>`: a slip with an English table and an Urdu footer is
     * normal, and flipping the whole page would move the money columns.
     */
    function drawWrapped(ctx, text, x, y, maxWidth, lineHeight, align) {
        var raw = String(text == null ? '' : text);
        if (!raw) return y;
        var rtl = isRtl(raw);
        ctx.direction = rtl ? 'rtl' : 'ltr';
        ctx.textAlign = align || (rtl ? 'right' : 'left');

        var anchor = ctx.textAlign === 'center' ? x + maxWidth / 2
                   : ctx.textAlign === 'right' ? x + maxWidth : x;

        raw.split('\n').forEach(function (paragraph) {
            var words = paragraph.split(' ');
            var line = '';
            words.forEach(function (word) {
                var attempt = line ? (line + ' ' + word) : word;
                if (ctx.measureText(attempt).width > maxWidth && line) {
                    ctx.fillText(line, anchor, y);
                    y += lineHeight;
                    line = word;
                } else {
                    line = attempt;
                }
            });
            ctx.fillText(line, anchor, y);
            y += lineHeight;
        });
        ctx.direction = 'ltr';
        return y;
    }

    /**
     * Draw the resolved document onto a canvas `widthDots` wide.
     *
     * <p>Two passes would be tidier; instead this draws onto a generously tall canvas, tracks the y it
     * reached and crops. Measuring text twice costs more than the pixels do, and a receipt has no page
     * breaks to plan for.
     */
    function drawModel(model, opts) {
        var W = opts.widthDots;
        var pad = Math.round(W * 0.03);
        var inner = W - pad * 2;
        var font = opts.fontFamily || 'Calibri, "Segoe UI", Arial, sans-serif';
        var base = Math.round(W / 24);              // ~24 characters of body text across the roll
        var line = Math.round(base * 1.9);          // Nastaliq cascades; Latin merely breathes

        var tall = document.createElement('canvas');
        tall.width = W;
        tall.height = 6000;
        var ctx = tall.getContext('2d');
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, tall.width, tall.height);
        ctx.fillStyle = '#000';
        ctx.textBaseline = 'top';

        var y = pad;
        var lh = model.letterhead || {};

        function rule() {
            y += Math.round(base * 0.4);
            ctx.fillRect(pad, y, inner, 1);
            y += Math.round(base * 0.6);
        }

        // ── letterhead
        ctx.font = '700 ' + Math.round(base * 1.6) + 'px ' + font;
        y = drawWrapped(ctx, lh.businessName || '', pad, y, inner, Math.round(base * 2.1), 'center');
        ctx.font = Math.round(base * 0.9) + 'px ' + font;
        var addr = [lh.addressLine1, lh.addressLine2, lh.phone].filter(Boolean).join('  ');
        if (addr) y = drawWrapped(ctx, addr, pad, y, inner, line, 'center');

        // ── title
        y += Math.round(base * 0.5);
        ctx.font = '700 ' + Math.round(base * 1.15) + 'px ' + font;
        y = drawWrapped(ctx, model.title || '', pad, y, inner, line, 'center');

        // ── header fields (Bill No, Date, Customer, Served by …)
        ctx.font = Math.round(base) + 'px ' + font;
        y += Math.round(base * 0.3);
        (model.headerFields || []).forEach(function (f) {
            var text = (f.label ? f.label + ': ' : '') + f.value;
            y = drawWrapped(ctx, text, pad, y, inner, line, 'left');
        });

        rule();

        // ── the table
        var cols = model.columns || [];
        var totalW = cols.reduce(function (a, c) { return a + (c.width || 10); }, 0) || 100;
        var xs = [], acc = pad;
        cols.forEach(function (c) {
            var cw = Math.round(inner * ((c.width || 10) / totalW));
            xs.push({ x: acc, w: cw, align: c.align });
            acc += cw;
        });

        ctx.font = '700 ' + Math.round(base * 0.92) + 'px ' + font;
        cols.forEach(function (c, i) {
            drawWrapped(ctx, c.label, xs[i].x, y, xs[i].w, line, xs[i].align);
        });
        y += line;
        rule();

        ctx.font = Math.round(base * 0.95) + 'px ' + font;
        (model.rows || []).forEach(function (row) {
            var rowBottomY = y;
            row.forEach(function (cell, i) {
                var end = drawWrapped(ctx, cell, xs[i].x, y, xs[i].w, line, xs[i].align);
                if (end > rowBottomY) rowBottomY = end;
            });
            y = rowBottomY + Math.round(base * 0.15);
        });

        rule();

        // ── totals
        (model.totals || []).forEach(function (tr) {
            ctx.font = (tr.strong ? '700 ' : '') + Math.round(base * (tr.strong ? 1.15 : 0.98)) + 'px ' + font;
            var half = Math.round(inner * 0.55);
            drawWrapped(ctx, tr.label, pad, y, half, line, 'left');
            var end = drawWrapped(ctx, tr.value, pad + half, y, inner - half, line, 'right');
            y = end + Math.round(base * 0.1);
        });

        // ── tax / fiscal / footer / terms
        ctx.font = Math.round(base * 0.85) + 'px ' + font;
        if (model.taxRegNo) {
            y += Math.round(base * 0.4);
            y = drawWrapped(ctx, model.taxRegNo, pad, y, inner, line, 'center');
        }
        if (model.fiscalLine) y = drawWrapped(ctx, model.fiscalLine, pad, y, inner, line, 'center');

        ctx.font = Math.round(base * 0.95) + 'px ' + font;
        if (model.footerText) {
            y += Math.round(base * 0.5);
            y = drawWrapped(ctx, model.footerText, pad, y, inner, line, 'center');
        }
        if (model.termsText) {
            y += Math.round(base * 0.3);
            y = drawWrapped(ctx, model.termsText, pad, y, inner, line, 'center');
        }

        /*
         * The QR, drawn into the SAME bitmap as the rest of the slip.
         *
         * Deliberately not sent as a separate ESC/POS QR command in raster mode: two commands would print
         * two blocks with the printer's own spacing between them, and the code would drift away from the
         * line it belongs under. One bitmap is one document.
         *
         * Drawn synchronously from a data: URI \u2014 already decoded by the time the caller awaits it because
         * the image is inline, but the load is still awaited by the caller (see drawModelAsync) so a slow
         * decode cannot produce a slip with a blank square.
         */
        if (model.qrImage) {
            var qs = Math.round(W * 0.34);
            ctx.drawImage(model.qrImage, Math.round((W - qs) / 2), y, qs, qs);
            y += qs + Math.round(base * 0.5);
        }

        y += pad;

        // Crop to what was actually used. Height must stay a whole number of dots; width is already a
        // multiple of 8 by construction (see WIDTHS) so the row packing never has to pad.
        var out = document.createElement('canvas');
        out.width = W;
        out.height = Math.max(1, Math.min(Math.ceil(y), tall.height));
        var octx = out.getContext('2d');
        octx.fillStyle = '#fff';
        octx.fillRect(0, 0, out.width, out.height);
        octx.drawImage(tall, 0, 0);
        return out;
    }

    // ── text mode ───────────────────────────────────────────────────────────────────────────────

    function padTo(s, width, align) {
        s = String(s == null ? '' : s);
        if (s.length > width) s = s.substring(0, width);
        var gap = width - s.length;
        if (align === 'right') return new Array(gap + 1).join(' ') + s;
        if (align === 'center') {
            var left = Math.floor(gap / 2);
            return new Array(left + 1).join(' ') + s + new Array(gap - left + 1).join(' ');
        }
        return s + new Array(gap + 1).join(' ');
    }

    /**
     * The fast, Latin-only ladder. Kept for a shop that prints English at a busy counter and wants the
     * absolute minimum between the sale and the paper — roughly 1 KB against 40 KB of raster.
     */
    function encodeText(model, opts) {
        var cols = Math.max(24, Math.floor(opts.widthDots / 12));   // Font A is 12 dots wide
        var parts = [init()];

        function centre(s) {
            parts.push(bytes([ESC, 0x61, 0x01]), ascii(s), bytes([LF]), bytes([ESC, 0x61, 0x00]));
        }
        function left(s) { parts.push(ascii(s), bytes([LF])); }

        var lh = model.letterhead || {};
        parts.push(bytes([ESC, 0x21, 0x30]));                       // double height + width
        centre(lh.businessName || '');
        parts.push(bytes([ESC, 0x21, 0x00]));
        var addr = [lh.addressLine1, lh.addressLine2, lh.phone].filter(Boolean).join(' ');
        if (addr) centre(addr);
        centre(model.title || '');
        left(new Array(cols + 1).join('-'));

        (model.headerFields || []).forEach(function (f) {
            left((f.label ? f.label + ': ' : '') + f.value);
        });
        left(new Array(cols + 1).join('-'));

        var cs = model.columns || [];
        var totalW = cs.reduce(function (a, c) { return a + (c.width || 10); }, 0) || 100;
        var widths = cs.map(function (c) { return Math.max(3, Math.floor(cols * ((c.width || 10) / totalW))); });

        left(cs.map(function (c, i) { return padTo(c.label, widths[i], c.align); }).join(''));
        left(new Array(cols + 1).join('-'));
        (model.rows || []).forEach(function (row) {
            left(row.map(function (cell, i) { return padTo(cell, widths[i], cs[i].align); }).join(''));
        });
        left(new Array(cols + 1).join('-'));

        (model.totals || []).forEach(function (tr) {
            left(padTo(tr.label, cols - 14, 'left') + padTo(tr.value, 14, 'right'));
        });

        if (model.taxRegNo) centre(model.taxRegNo);
        if (model.fiscalLine) centre(model.fiscalLine);
        if (model.footerText) centre(model.footerText);

        /*
         * In TEXT mode the PRINTER builds the QR itself (GS ( k), from the payload rather than from an
         * image. That is the right call here for two reasons: the module grid comes out crisper than any
         * bitmap we could rasterise, and it costs a few dozen bytes against tens of kilobytes \u2014 which is
         * the entire reason a shop chose text mode.
         *
         * Only reachable when the payload is ASCII, which every fiscal format specified so far is.
         */
        if (model.qrPayload) {
            var qp = ascii(model.qrPayload);
            var len = qp.length + 3;
            parts.push(
                bytes([GS, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00]),          // model 2
                bytes([GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x06]),                // module size 6
                bytes([GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31]),                // error correction M
                bytes([GS, 0x28, 0x6B, len & 0xFF, (len >> 8) & 0xFF, 0x31, 0x50, 0x30]), qp,  // store
                bytes([ESC, 0x61, 0x01]),                                             // centre
                bytes([GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30]),                // print
                bytes([ESC, 0x61, 0x00]));
        }
        // termsText is deliberately NOT printed here: it is the block a shop most often writes in Urdu, and
        // ascii() would render it as a row of question marks. Raster mode prints it correctly.
        return concat(parts);
    }

    // ── transports ──────────────────────────────────────────────────────────────────────────────

    /**
     * A local agent over HTTP.
     *
     * <p>The most compatible transport and the reason it is the default: no HTTPS requirement, no driver
     * rebinding, and it reaches any printer the machine can already print to. The cost is an install per
     * till, which a shop makes once.
     */
    function sendAgent(url, data) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream' },
            body: data
        }).then(function (r) {
            if (!r.ok) {
                // The agent ANSWERED. It may already have put ink on paper, so the caller must not retry
                // down another route — see printInvoiceObject's fallback rule.
                var e = new Error('Print agent answered ' + r.status);
                e.reachedPrinter = true;
                throw e;
            }
        });
    }

    /**
     * WebUSB.
     *
     * ⚠ Requires HTTPS (or localhost), a user gesture the first time, and on Windows the printer must be
     * bound to WinUSB rather than the vendor's print driver. A shop already printing through the Windows
     * driver will not see the device here without changing that binding — which is why this is not the
     * default and why the failure is reported rather than swallowed.
     */
    function sendUsb(data) {
        if (!global.navigator || !navigator.usb) {
            return Promise.reject(new Error('This browser has no WebUSB. Use the print agent instead.'));
        }
        var dev;
        return navigator.usb.requestDevice({ filters: [{ classCode: 7 }] })   // class 7 = printer
            .then(function (d) { dev = d; return dev.open(); })
            .then(function () { return dev.selectConfiguration(1); })
            .then(function () { return dev.claimInterface(0); })
            .then(function () {
                var ep = 1;
                dev.configuration.interfaces.forEach(function (i) {
                    i.alternates.forEach(function (a) {
                        a.endpoints.forEach(function (e) { if (e.direction === 'out') ep = e.endpointNumber; });
                    });
                });
                return dev.transferOut(ep, data);
            })
            .then(function () { return dev.close(); });
    }

    /** WebSerial — same browser and HTTPS rules as WebUSB, for serial and USB-serial printers. */
    function sendSerial(data, baud) {
        if (!global.navigator || !navigator.serial) {
            return Promise.reject(new Error('This browser has no Web Serial. Use the print agent instead.'));
        }
        var port;
        return navigator.serial.requestPort()
            .then(function (p) { port = p; return port.open({ baudRate: baud || 9600 }); })
            .then(function () {
                var writer = port.writable.getWriter();
                return writer.write(data).then(function () { writer.releaseLock(); });
            })
            .then(function () { return port.close(); });
    }

    // ── top level ───────────────────────────────────────────────────────────────────────────────

    /** 80mm and 58mm rolls at 203dpi. Both multiples of 8, so a raster row never needs padding. */
    var WIDTHS = { '80': 576, '58': 384 };

    /**
     * Build the byte stream for one resolved document.
     *
     * <p>Separated from sending so it can be asserted without a printer — the gate checks the first three
     * bytes are `1D 76 30` and that the length is non-zero, which no device is needed for.
     */
    /**
     * Decode the QR data URI before anything is drawn.
     *
     * <p>An <img> is not usable until it has loaded, even from a data: URI. Drawing first and hoping would
     * put a blank square on some receipts and not others \u2014 the kind of defect that only appears on the
     * busiest till. The whole print path is therefore a Promise; the browser path was already one.
     */
    function loadQr(model) {
        var uri = model && model.qrDataUri;
        if (!uri || !/^data:image\/png;base64,/.test(String(uri))) return Promise.resolve(model);
        return new Promise(function (resolve) {
            var img = new Image();
            img.onload = function () { model.qrImage = img; resolve(model); };
            // A QR that will not decode must not cost the shop a receipt.
            img.onerror = function () { resolve(model); };
            img.src = uri;
        });
    }

    function encode(model, opts) {
        opts = opts || {};
        opts.widthDots = opts.widthDots || WIDTHS['80'];
        var body = (opts.mode === 'escpos-text')
            ? encodeText(model, opts)
            : concat([init(), rasterCommand(canvasToRaster(drawModel(model, opts), opts.threshold))]);

        var tail = [body];
        if (opts.cashDrawer) tail.push(drawerKick());
        if (opts.autoCut !== false) tail.push(cut(true));
        return concat(tail);
    }

    function send(data, opts) {
        switch (opts.transport) {
            case 'usb': return sendUsb(data);
            case 'serial': return sendSerial(data, opts.baudRate);
            default: return sendAgent(opts.agentUrl || 'http://localhost:8083/print', data);
        }
    }

    global.EscPos = {
        encode: encode,
        send: send,
        print: function (model, opts) {
            var o = opts || {};
            // The QR has to be decoded BEFORE the canvas is drawn; text mode has no image to wait for.
            return (o.mode === 'escpos-text' ? Promise.resolve(model) : loadQr(model))
                .then(function (m) { return send(encode(m, o), o); });
        },
        loadQr: loadQr,
        // exposed for the gate; none of these touch a device
        drawModel: drawModel,
        canvasToRaster: canvasToRaster,
        rasterCommand: rasterCommand,
        encodeText: encodeText,
        isRtl: isRtl,
        WIDTHS: WIDTHS
    };
})(window);
