/*
 * Receipt printing (G6, slice 38) — a general, vertical-aware thermal receipt for the single commerce dashboard.
 * Replaces the old client-specific printer. Fetches the authoritative invoice (lines + G3 tax + G5 payment) from
 * /getReceipt and prints it via a hidden iframe (no popup blocked, works after an AJAX sale). The header brand /
 * title come from the active vertical profile (window.VERTICAL_PROFILE), so POS / Pharmacy / Store each read right.
 */
(function (global) {
    'use strict';

    function money(v) {
        var n = Number(v);
        return isNaN(n) ? '' : n.toFixed(2);
    }

    function row2(label, value, strong) {
        return '<div class="rc-tot' + (strong ? ' rc-strong' : '') + '"><span>' + label
            + '</span><span>' + value + '</span></div>';
    }

    function buildHtml(inv) {
        var profile = global.VERTICAL_PROFILE || {};
        var brand = profile.brand || 'MyPlus';
        var title = profile.receiptTitle || 'SALES RECEIPT';
        var taxLabel = inv.taxLabel || 'Tax';
        var cust = inv.customer || {};
        var dated = inv.dated ? String(inv.dated).replace('T', ' ').substring(0, 16) : '';

        var lines = (inv.sales || []).map(function (s) {
            var rate = (s.sellRate != null) ? s.sellRate : (s.stock && s.stock.bsellRate != null ? s.stock.bsellRate : '');
            var amt = money((Number(s.totalAmount) || 0) + (Number(s.taxAmount) || 0));
            return '<tr><td class="rc-name">' + escHtml(s.itemName || '') + '</td>'
                + '<td class="rc-r">' + (s.quantity != null ? s.quantity : '') + '</td>'
                + '<td class="rc-r">' + money(rate) + '</td>'
                + '<td class="rc-r">' + amt + '</td></tr>';
        }).join('');

        var grand = inv.grandTotal != null ? inv.grandTotal : inv.subTotal;
        var totals = '';
        if (inv.subTotal != null) totals += row2('Subtotal', money(inv.subTotal));
        if (inv.taxTotal != null && Number(inv.taxTotal) > 0) totals += row2(escHtml(taxLabel), money(inv.taxTotal));
        totals += row2('TOTAL', money(grand), true);

        var pay = '';
        if (inv.paymentMode) pay += row2('Paid by', escHtml(inv.paymentMode));
        if (inv.tenderedAmount != null && Number(inv.tenderedAmount) > 0) pay += row2('Tendered', money(inv.tenderedAmount));
        if (inv.changeAmount != null && Number(inv.changeAmount) > 0) pay += row2('Change', money(inv.changeAmount));
        // Owed on this sale = −dueAmount when negative (dueAmount = paid − bill).
        var owed = (inv.dueAmount != null && Number(inv.dueAmount) < 0) ? (-Number(inv.dueAmount)) : 0;
        if (owed > 0) pay += row2('Due', money(owed));

        var regNo = inv.taxRegNo ? '<div class="rc-c rc-sm">' + escHtml(taxLabel) + ' Reg: ' + escHtml(inv.taxRegNo) + '</div>' : '';

        return '<!doctype html><html><head><meta charset="utf-8"><title>Receipt '
            + escHtml(inv.invoiceNo || '') + '</title><style>'
            + '*{margin:0;padding:0;box-sizing:border-box}'
            + 'body{font-family:"Courier New",monospace;color:#000;width:80mm;padding:6mm 4mm;font-size:12px}'
            + '.rc-c{text-align:center}.rc-r{text-align:right}.rc-sm{font-size:10px}'
            + '.rc-brand{font-size:16px;font-weight:700;text-align:center}'
            + '.rc-title{text-align:center;letter-spacing:2px;margin:2px 0 6px}'
            + '.rc-meta{font-size:11px;margin:2px 0}'
            + 'hr{border:0;border-top:1px dashed #000;margin:6px 0}'
            + 'table{width:100%;border-collapse:collapse}'
            + 'th{font-size:10px;text-align:left;border-bottom:1px solid #000;padding:2px 0}'
            + 'th.rc-r{text-align:right}td{padding:2px 0;vertical-align:top}.rc-name{width:46%}'
            + '.rc-tot{display:flex;justify-content:space-between;font-size:12px;margin:2px 0}'
            + '.rc-strong{font-weight:700;font-size:14px;border-top:1px solid #000;padding-top:3px;margin-top:3px}'
            + '.rc-foot{text-align:center;margin-top:8px;font-size:10px}'
            + '@media print{body{width:auto}}'
            + '</style></head><body>'
            + '<div class="rc-brand" data-brand>' + escHtml(brand) + '</div>'
            + '<div class="rc-title">' + escHtml(title) + '</div>'
            + '<div class="rc-meta">Invoice: <b>' + escHtml(inv.invoiceNo || '') + '</b></div>'
            + (dated ? '<div class="rc-meta">Date: ' + escHtml(dated) + '</div>' : '')
            + (cust.name ? '<div class="rc-meta">Customer: ' + escHtml(cust.name) + '</div>' : '')
            + '<hr>'
            + '<table><thead><tr><th>Item</th><th class="rc-r">Qty</th><th class="rc-r">Rate</th><th class="rc-r">Amt</th></tr></thead>'
            + '<tbody>' + lines + '</tbody></table>'
            + '<hr>' + totals
            + (pay ? '<hr>' + pay : '')
            + regNo
            + '<div class="rc-foot">Thank you for your business</div>'
            + '</body></html>';
    }

    function printInvoiceObject(inv) {
        var frame = document.getElementById('receiptFrame');
        if (!frame) {
            frame = document.createElement('iframe');
            frame.id = 'receiptFrame';
            frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0';
            document.body.appendChild(frame);
        }
        var doc = frame.contentWindow.document;
        doc.open();
        doc.write(buildHtml(inv));
        doc.close();
        setTimeout(function () {
            try { frame.contentWindow.focus(); frame.contentWindow.print(); } catch (e) { /* ignore */ }
        }, 300);
    }

    // Fetch the authoritative receipt by invoice number, then print.
    global.printReceipt = function (invoiceNo) {
        if (!invoiceNo) { if (global.showFormError) showFormError('No invoice to print.'); return; }
        $.get(serverContext + 'getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo), function (resp) {
            if (!resp || resp.status !== 'SUCCESS' || !resp.object) {
                if (global.showFormError) showFormError((resp && resp.message) || 'Could not load the receipt.');
                return;
            }
            printInvoiceObject(resp.object);
        }).fail(function () { if (global.showFormError) showFormError('Could not load the receipt.'); });
    };
})(window);
