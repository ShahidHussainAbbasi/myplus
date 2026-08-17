/*
 * Document rendering (G6 slice 38 → B2B Phase 3g) — the printable sale document, for every channel.
 *
 * WHAT CHANGED IN 3g: the layout is no longer hardcoded. A **Document Profile** (plain data — paper size,
 * header field groups, the line columns with their labels/widths/alignment, which totals rows print, footer)
 * drives the renderer. That single decision is what makes the whole feature possible:
 *
 *   - the 80mm thermal slip is a profile        (RETAIL_RECEIPT_80MM / DISPENSE_RECEIPT_80MM)
 *   - a full A4 trade invoice is a profile      (TRADE_INVOICE_A4)
 *   - an owner-designed layout is a profile     (3g-3 stores it, 3g-4 edits it)
 *
 * …so there is exactly ONE renderer, and the designer's live preview calls the same `buildHtml` the printer
 * does rather than growing a second implementation that drifts from it.
 *
 * THE SAFETY BOUNDARY (3g §2.3): a profile references fields by KEY, and every key must exist in
 * FIELD_WHITELIST below, where it is bound to a resolver function. An owner controls presence, order, label,
 * width and alignment — never code, never an expression language, never raw markup. That is what keeps an
 * owner-authored invoice XSS-safe (it gets printed and handed to a third party), translatable across all six
 * locales, and upgradeable: a new field is a new whitelist entry, not a broken tenant template.
 *
 * CHANNEL PICKS THE LAYOUT, VERTICAL PICKS THE WORDS. A trade account (Customer.customerType) gets the A4
 * invoice; a walk-in gets the thermal slip — including in a pharmacy, where a patient buying one strip must
 * not be handed an A4 sheet. The vertical profile only supplies the brand and the document's TITLE.
 */
(function (global) {
    'use strict';

    var B2B_TYPES = ['RETAILER', 'WHOLESALE'];

    // ---------------------------------------------------------------- helpers (defined ONCE, shared by both layouts)

    function num(v) { var n = Number(v); return isNaN(n) ? 0 : n; }

    function money(v) {
        var n = Number(v);
        return isNaN(n) ? '' : n.toFixed(2);
    }

    /** Thousands-separated money, for the A4 summary block where figures are read at a glance. */
    function moneyG(v) {
        var n = Number(v);
        if (isNaN(n)) return '';
        var parts = n.toFixed(2).split('.');
        return parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + parts[1];
    }

    function dateOnly(v) { return v ? String(v).replace('T', ' ').substring(0, 10) : ''; }

    function timeOnly(v) {
        var s = v ? String(v).replace('T', ' ') : '';
        return s.length >= 16 ? s.substring(11, 16) : '';
    }

    function dateTime(v) { return v ? String(v).replace('T', ' ').substring(0, 16) : ''; }

    /** Safe label lookup: a built-in label is an i18n key, an owner-authored one is literal text. */
    function labelOf(spec, override) {
        if (override != null && override !== '') return String(override);
        if (!spec) return '';
        return spec.key ? t(spec.key) : (spec.label || '');
    }

    // ---------------------------------------------------------------- per-line arithmetic
    //
    // Derived from the three fields whose meaning is unambiguous, NOT from Sell.netAmount. netAmount is the
    // right answer on a saga-written row (SagaSellService sets it to the discounted, taxed line gross) but
    // legacy rows persisted the sell form's "Net Amount" box, which actually held PROFIT (gross − cost −
    // discount). Printing that as a line total on an old invoice would be badly wrong, so the total is
    // recomputed from totalAmount / discount / taxAmount, which mean the same thing on every row.
    //
    //   Value  = Sell.totalAmount   = qty × sold rate, BEFORE discount and tax
    //   Total  = Value − Discount + Tax
    //
    // DEFECT FIXED HERE (3g-1): the previous renderer printed `totalAmount + taxAmount` and simply ignored
    // Sell.discount, so every discounted line printed MORE than the customer was charged and the line amounts
    // did not add up to the TOTAL at the foot of the same document.
    function lineMath(s) {
        var qty = num(s.quantity);
        var value = num(s.totalAmount);
        var disc = num(s.discount);
        var tax = num(s.taxAmount);
        var rate = (s.sellRate != null) ? num(s.sellRate)
            : (s.stock && s.stock.bsellRate != null) ? num(s.stock.bsellRate)
                : (qty ? value / qty : 0);
        return {
            qty: qty,
            bonus: num(s.bonusQuantity),
            rate: rate,
            value: value,
            discount: disc,
            discountPct: value ? (disc / value) * 100 : 0,
            netRate: qty ? (value - disc) / qty : 0,
            tax: tax,
            total: value - disc + tax
        };
    }

    function batchText(s, field) {
        return (s.batches || []).map(function (b) {
            return field === 'batchNo' ? (b.batchNo || '') : dateOnly(b.expiryDate);
        }).filter(function (x) { return x; }).join(' / ');
    }

    // ---------------------------------------------------------------- amount in words
    //
    // D-3: English only, in both numbering systems. Every other locale falls back to the numeric total on
    // purpose — an amount in words is a legally meaningful figure on an invoice, and printing a
    // machine-mangled approximation of it in Urdu/Arabic/Hindi is worse than printing digits. Adding a
    // locale means adding a verified word table, not a transliteration.
    var ONES = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten',
        'Eleven', 'Twelve', 'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen'];
    var TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety'];

    function under1000(n) {
        var out = [];
        if (n >= 100) { out.push(ONES[Math.floor(n / 100)], 'Hundred'); n %= 100; }
        if (n >= 20) { out.push(TENS[Math.floor(n / 10)]); n %= 10; }
        if (n > 0) out.push(ONES[n]);
        return out.join(' ');
    }

    /**
     * @param system 'indian' → Thousand / Lakh / Crore (South-Asian grouping, what a Pakistani or Indian
     *               invoice is expected to read); 'international' → Thousand / Million / Billion.
     */
    function wordsEn(n, system) {
        n = Math.floor(Math.abs(num(n)));
        if (n === 0) return 'Zero';
        var parts = [];
        if (system === 'indian') {
            var crore = Math.floor(n / 10000000); n %= 10000000;
            var lakh = Math.floor(n / 100000); n %= 100000;
            var thou = Math.floor(n / 1000); n %= 1000;
            if (crore) parts.push(under1000(crore), 'Crore');
            if (lakh) parts.push(under1000(lakh), 'Lakh');
            if (thou) parts.push(under1000(thou), 'Thousand');
            if (n) parts.push(under1000(n));
        } else {
            var scales = [[1000000000, 'Billion'], [1000000, 'Million'], [1000, 'Thousand']];
            for (var i = 0; i < scales.length; i++) {
                var v = Math.floor(n / scales[i][0]);
                if (v) { parts.push(under1000(v), scales[i][1]); n %= scales[i][0]; }
            }
            if (n) parts.push(under1000(n));
        }
        return parts.join(' ').replace(/\s+/g, ' ').trim();
    }

    function amountInWords(total, inv, profile) {
        var lang = (global.APP_LANG || document.documentElement.lang || 'en').substring(0, 2).toLowerCase();
        var word = inv.currencyWord || 'Rupees';
        if (lang !== 'en') return '';                       // deliberate: digits, never guessed words
        var whole = Math.floor(num(total));
        var paisa = Math.round((num(total) - whole) * 100);
        var s = wordsEn(whole, profile.numberSystem || 'indian') + ' ' + word;
        if (paisa > 0) s += ' and ' + under1000(paisa) + ' ' + (inv.currencyFraction || 'Paisa');
        return s + ' Only';
    }

    // ---------------------------------------------------------------- FIELD WHITELIST
    //
    // The contract between a profile and the renderer. `key` in a profile MUST appear here or it is dropped.
    // Each entry: { key: <i18n key for the default label>, align, resolve(ctx) → already-safe STRING }.
    // Resolvers return plain text; the renderer escapes everything on the way into the document.
    //
    // KEEP IN STEP WITH THE SERVER. The same three key sets are mirrored in
    // business-service DocumentProfileValidator, which REJECTS a stored template referencing anything not on
    // them. Duplicated across the language boundary on purpose: this file needs them to RENDER, the server
    // needs them to VALIDATE, and a server that trusts the browser's list is not validating anything.
    // Adding a field means editing both.

    var HEADER_FIELDS = {
        invoiceNo:      { key: 'ui.js.docInvoiceNo',     resolve: function (c) { return c.inv.invoiceNo || ''; } },
        dated:          { key: 'ui.js.docDate',          resolve: function (c) { return dateOnly(c.inv.dated); } },
        datedTime:      { key: 'ui.js.docDateTime',      resolve: function (c) { return dateTime(c.inv.dated); } },
        time:           { key: 'ui.js.docTime',          resolve: function (c) { return timeOnly(c.inv.dated); } },
        dueDate:        { key: 'ui.js.docDueDate',       resolve: function (c) { return dateOnly(c.inv.dueDate); } },
        paymentMode:    { key: 'ui.js.docPaymentMode',   resolve: function (c) { return c.inv.paymentMode || ''; } },
        storeName:      { key: 'ui.js.docStore',         resolve: function (c) { return (c.inv.letterhead || {}).storeName || ''; } },
        // Seller-side licence — settings-sourced, so a pharmacy can print its drug licence with no schema.
        licenseNo:      { key: 'ui.js.docLicenseNo',     resolve: function (c) { return (c.inv.letterhead || {}).licenseNo || ''; } },
        licenseExpiry:  { key: 'ui.js.docLicenseExpiry', resolve: function (c) { return (c.inv.letterhead || {}).licenseExpiry || ''; } },
        // OVERRIDE WITH FALLBACK. The owner-configured line (pos.document.bookedBy) wins when set;
        // otherwise the name stamped on the sale when it was written. The two differ in kind — one is
        // per-tenant configuration read at print time, the other is per-sale history — so the order
        // matters: an owner who has deliberately set a fixed line means it for every invoice.
        // Blank config is the default, which is why existing tenants see no change at all.
        bookedBy:       { key: 'ui.js.docBookedBy',      resolve: function (c) {
                              var over = (c.inv.letterhead || {}).bookedBy;
                              return (over && String(over).trim()) || c.inv.bookedByName || '';
                          } },
        // Buyer side.
        customerName:   { key: 'ui.js.docCustomer',      resolve: function (c) {
            var code = c.cust.customerId ? '(' + c.cust.customerId + ') ' : '';
            return c.cust.name ? code + c.cust.name : '';
        } },
        customerCode:   { key: 'ui.js.docCustomerCode',  resolve: function (c) { return c.cust.customerId ? String(c.cust.customerId) : ''; } },
        customerAddress:{ key: 'ui.js.docAddress',       resolve: function (c) { return c.cust.address || ''; } },
        customerMobile: { key: 'ui.js.docMobile',        resolve: function (c) { return c.cust.contact || ''; } },
        customerCity:   { key: 'ui.js.docCity',          resolve: function (c) { return c.cust.city || ''; } },
        customerCnic:   { key: 'ui.js.docCnic',          resolve: function (c) { return c.cust.cnic || ''; } },
        customerLicenseNo:     { key: 'ui.js.docLicenseNo',     resolve: function (c) { return c.cust.licenseNo || ''; } },
        customerLicenseExpiry: { key: 'ui.js.docLicenseExpiry', resolve: function (c) { return dateOnly(c.cust.licenseExpiry); } }
    };

    // `sum: true` marks a column the in-table totals row adds up (the sample's "Total: 50.00 … 4100.00" band).
    var LINE_FIELDS = {
        lineNo:        { key: 'ui.js.docLineNo',   align: 'left',  resolve: function (c) { return String(c.i + 1); } },
        itemCode:      { key: 'ui.js.docCode',     align: 'left',  resolve: function (c) { return c.s.itemCode || ''; } },
        itemName:      { key: 'ui.js.docProduct',  align: 'left',  resolve: function (c) { return c.s.itemName || ''; } },
        packing:       { key: 'ui.js.docPacking',  align: 'left',  resolve: function (c) { return c.s.packing || ''; } },
        batchNo:       { key: 'ui.js.docBatchNo',  align: 'left',  resolve: function (c) { return batchText(c.s, 'batchNo'); } },
        expiryDate:    { key: 'ui.js.docExpiry',   align: 'left',  resolve: function (c) { return batchText(c.s, 'expiryDate'); } },
        quantity:      { key: 'ui.js.docQty',      align: 'right', sum: 'qty',      resolve: function (c) { return money(c.m.qty); } },
        bonusQty:      { key: 'ui.js.docBonus',    align: 'right', sum: 'bonus',    resolve: function (c) { return money(c.m.bonus); } },
        tradePrice:    { key: 'ui.js.docTradePrice', align: 'right',                resolve: function (c) { return money(c.m.rate); } },
        lineValue:     { key: 'ui.js.docValue',    align: 'right', sum: 'value',    resolve: function (c) { return money(c.m.value); } },
        discountPct:   { key: 'ui.js.docDiscountPct', align: 'right',               resolve: function (c) { return c.m.discountPct.toFixed(2); } },
        discount:      { key: 'ui.js.docDiscount', align: 'right', sum: 'discount', resolve: function (c) { return money(c.m.discount); } },
        netTradePrice: { key: 'ui.js.docNetTradePrice', align: 'right',             resolve: function (c) { return money(c.m.netRate); } },
        taxRate:       { key: 'ui.js.docTaxRate',  align: 'right',                  resolve: function (c) { return c.s.taxRate != null ? num(c.s.taxRate) + '%' : ''; } },
        taxAmount:     { key: 'ui.js.docTaxAmount', align: 'right', sum: 'tax',     resolve: function (c) { return money(c.m.tax); } },
        lineTotal:     { key: 'ui.js.docTotal',    align: 'right', sum: 'total',    resolve: function (c) { return money(c.m.total); } }
    };

    // Summary rows. `strong` renders the emphasised total rule.
    var TOTAL_ROWS = {
        itemCount:       { key: 'ui.js.docItemCount',      resolve: function (c) { return String(c.lines.length); } },
        qtyTotal:        { key: 'ui.js.docQtyTotal',       resolve: function (c) { return money(c.sums.qty); } },
        bonusTotal:      { key: 'ui.js.docBonusTotal',     resolve: function (c) { return money(c.sums.bonus); } },
        valueTotal:      { key: 'ui.js.docValueTotal',     resolve: function (c) { return money(c.sums.value); } },
        discountTotal:   { key: 'ui.js.docDiscountTotal',  resolve: function (c) { return money(c.sums.discount); } },
        subTotal:        { key: 'ui.js.docSubtotal',       resolve: function (c) { return c.inv.subTotal != null ? money(c.inv.subTotal) : ''; } },
        taxTotal:        { key: null, dynamicLabel: function (c) { return c.taxLabel; },
                           resolve: function (c) { return (c.inv.taxTotal != null && num(c.inv.taxTotal) > 0) ? money(c.inv.taxTotal) : ''; } },
        tradeDiscount:   { key: 'ui.js.docTradeDiscount',  resolve: function (c) { return c.inv.tradeDiscount != null ? money(c.inv.tradeDiscount) : ''; } },
        // V39: delivery charged to the customer. Added after tax and already inside grandTotal, so a document
        // that shows subtotal + tax + delivery adds up; a counter sale has none and the row renders empty.
        shippingFee:     { key: 'ui.js.docShippingFee',    resolve: function (c) { return c.inv.shippingFee != null ? money(c.inv.shippingFee) : ''; } },
        grandTotal:      { key: 'ui.js.docGrandTotal', strong: true,
                           resolve: function (c) { return (c.inv.currencySymbol || '') + moneyG(c.grand); } },
        amountInWords:   { key: 'ui.js.docAmountInWords', wide: true,
                           resolve: function (c) { return c.words; } },
        paidBy:          { key: 'ui.js.docPaidBy',         resolve: function (c) { return c.inv.paymentMode || ''; } },
        tendered:        { key: 'ui.js.docTendered',       resolve: function (c) { return num(c.inv.tenderedAmount) > 0 ? money(c.inv.tenderedAmount) : ''; } },
        change:          { key: 'ui.js.docChange',         resolve: function (c) { return num(c.inv.changeAmount) > 0 ? money(c.inv.changeAmount) : ''; } },
        storeCredit:     { key: 'ui.js.docStoreCredit',    resolve: function (c) { return num(c.inv.storeCreditApplied) > 0 ? money(c.inv.storeCreditApplied) : ''; } },
        storeCreditBalance: { key: 'ui.js.docStoreCreditBalance', resolve: function (c) {
            return (num(c.inv.storeCreditApplied) > 0 && c.cust.creditBalance != null) ? money(c.cust.creditBalance) : ''; } },
        due:             { key: 'ui.js.docDue',            resolve: function (c) { return c.owed > 0 ? money(c.owed) : ''; } },
        // DR/CR is what makes an account figure readable: DR = the customer owes us, CR = they are in credit.
        previousBalance: { key: 'ui.js.previousBalance',   resolve: function (c) {
            return c.inv.balanceAfter == null ? '' : drcr(c.previousBalance, c); } },
        currentBalance:  { key: 'ui.js.newBalance', strong: true, resolve: function (c) {
            return c.inv.balanceAfter == null ? '' : drcr(num(c.inv.balanceAfter), c); } }
    };

    function drcr(v, c) {
        if (!c.showDrCr) return money(v);
        var n = num(v);
        return moneyG(Math.abs(n)) + ' ' + (n < 0 ? t('ui.js.docCr') : t('ui.js.docDr'));
    }

    var FIELD_WHITELIST = { header: HEADER_FIELDS, line: LINE_FIELDS, totals: TOTAL_ROWS };

    // ---------------------------------------------------------------- built-in presets

    function col(key, width, align) { return { key: key, width: width, align: align }; }

    var PRESETS = {
        /*
         * The B2B document. Column set and order follow a pharmaceutical distribution invoice, which is the
         * densest real-world case: identity (code/description/packing), traceability (batch/expiry),
         * quantities (qty/bonus), and the price walk (TP → Value → D% → Discount → Net-TP → Total).
         */
        TRADE_INVOICE_A4: {
            id: 'TRADE_INVOICE_A4',
            name: 'Trade invoice (A4)',
            paper: 'A4',
            channel: 'B2B',
            numberSystem: 'indian',
            showDrCr: true,
            header: {
                titleStyle: 'boxed',
                showLogo: true,
                columns: [
                    ['invoiceNo', 'dated', 'dueDate', 'time'],
                    ['licenseNo', 'licenseExpiry', 'bookedBy', 'customerCity'],
                    ['customerName', 'customerAddress', 'customerMobile', 'customerCnic']
                ]
            },
            lines: [
                col('itemCode', 6, 'left'), col('itemName', 22, 'left'), col('packing', 8, 'left'),
                col('batchNo', 9, 'left'), col('expiryDate', 7, 'left'),
                col('quantity', 6, 'right'), col('bonusQty', 5, 'right'), col('tradePrice', 6, 'right'),
                col('lineValue', 8, 'right'), col('discountPct', 5, 'right'), col('discount', 7, 'right'),
                col('netTradePrice', 6, 'right'), col('lineTotal', 8, 'right')
            ],
            totals: ['itemCount', 'tradeDiscount', 'grandTotal', 'amountInWords',
                'previousBalance', 'currentBalance'],
            footer: { text: '', showSignature: true }
        },

        /* Today's thermal slip, expressed as data. Byte-for-byte the same document it has always been. */
        RETAIL_RECEIPT_80MM: {
            id: 'RETAIL_RECEIPT_80MM',
            name: 'Retail receipt (80mm)',
            paper: '80mm',
            channel: 'B2C',
            numberSystem: 'indian',
            showDrCr: false,
            header: { titleStyle: 'plain', showLogo: false,
                columns: [['invoiceNo', 'datedTime', 'customerName']] },
            lines: [
                col('lineNo', 6, 'left'), col('itemName', 46, 'left'),
                col('quantity', 14, 'right'), col('tradePrice', 16, 'right'), col('lineTotal', 18, 'right')
            ],
            totals: ['subTotal', 'taxTotal', 'grandTotal', 'paidBy', 'tendered', 'change',
                'storeCredit', 'storeCreditBalance', 'due', 'previousBalance', 'currentBalance'],
            footer: { text: '', showSignature: false }
        },

        /*
         * OMS O8 slice 3 — the PER-STOP SLIP the shop signs for.
         *
         * A DELIVERY CHALLAN, and emphatically not a second invoice. One sale may produce only one taxable
         * document; a slip that looked like an invoice would create a second record of the same supply, a
         * duplicate tax entry, and an argument about which copy is real. It carries the invoice number so the
         * shopkeeper can match the two, and says Delivery Challan on its face.
         *
         * It is a PRESET, not a new renderer. Everything below is data the existing renderer already
         * understands -- which is why this document costs a table of field names rather than a second layout
         * engine to keep in step with the first.
         *
         * The columns are the distribution set (code, packing, BATCH, EXPIRY, discount): batch and expiry are a
         * regulatory obligation on pharmaceutical goods, not decoration, and the discount prints beside the
         * list price so the shop can see what it was given rather than a quietly reduced rate.
         *
         * Prices ARE shown. The shop must be able to check what it is being charged before signing, and the
         * same person carrying this slip is the one collecting the cash. (Some distributors omit prices from a
         * driver's copy to keep them from staff; that does not apply when the carrier is the collector.)
         */
        DELIVERY_CHALLAN_A4: {
            id: 'DELIVERY_CHALLAN_A4',
            name: 'Delivery challan (A4)',
            titleKey: 'ui.js.docDeliveryChallan',
            paper: 'A4',
            channel: 'B2B',
            numberSystem: 'indian',
            showDrCr: true,
            header: {
                titleStyle: 'boxed',
                showLogo: true,
                columns: [
                    ['invoiceNo', 'dated', 'time', 'paymentMode'],
                    ['bookedBy', 'customerCity', 'licenseNo', 'licenseExpiry'],
                    ['customerName', 'customerAddress', 'customerMobile', 'customerCnic']
                ]
            },
            lines: [
                col('itemCode', 7, 'left'), col('itemName', 26, 'left'), col('packing', 9, 'left'),
                col('batchNo', 10, 'left'), col('expiryDate', 8, 'left'),
                col('quantity', 7, 'right'), col('bonusQty', 5, 'right'), col('tradePrice', 7, 'right'),
                col('lineValue', 8, 'right'), col('discount', 7, 'right'), col('lineTotal', 9, 'right')
            ],
            totals: ['itemCount', 'qtyTotal', 'discountTotal', 'tradeDiscount', 'shippingFee',
                'grandTotal', 'amountInWords', 'previousBalance', 'currentBalance'],
            footer: {
                text: '',
                showSignature: true,
                // Four boxes, because this slip does two jobs: it proves the goods arrived AND records what was
                // paid for them at the door. The last two are written in by hand at the shop.
                signature: ['ui.js.docDeliveredBy', 'ui.js.docReceivedBySign',
                    'ui.js.docAmountReceived', 'ui.js.docBalanceLeft']
            }
        },

        /* Pharmacy B2C. Identical geometry — the vertical changes the TITLE, not the layout. */
        DISPENSE_RECEIPT_80MM: {
            id: 'DISPENSE_RECEIPT_80MM',
            name: 'Dispense receipt (80mm)',
            paper: '80mm',
            channel: 'B2C',
            numberSystem: 'indian',
            showDrCr: false,
            header: { titleStyle: 'plain', showLogo: false,
                columns: [['invoiceNo', 'datedTime', 'customerName']] },
            lines: [
                col('lineNo', 6, 'left'), col('itemName', 40, 'left'), col('batchNo', 12, 'left'),
                col('quantity', 12, 'right'), col('tradePrice', 14, 'right'), col('lineTotal', 16, 'right')
            ],
            totals: ['subTotal', 'taxTotal', 'grandTotal', 'paidBy', 'tendered', 'change',
                'storeCredit', 'storeCreditBalance', 'due', 'previousBalance', 'currentBalance'],
            footer: { text: '', showSignature: false }
        }
    };

    // ---------------------------------------------------------------- template resolution (Chain of Responsibility)
    //
    //   explicit profile passed in        → designer preview / reprint of a specific layout
    //   org's stored template (3g-3)      → inv.documentProfile, resolved server-side and org-scoped
    //   layoutMode override               → pos.document.layoutMode = thermal | a4
    //   channel preset                    → B2B ⇒ A4 trade invoice, B2C ⇒ thermal
    //   RETAIL_RECEIPT_80MM               → today's behaviour, for an org that changes nothing
    //
    // The last line is what makes 3g additive: an org that never opens the designer sees no change at all.

    function isTradeCustomer(inv) {
        var custType = String((inv.customer && inv.customer.customerType) || '').toUpperCase();
        return B2B_TYPES.indexOf(custType) !== -1;
    }

    function resolveProfile(inv, explicit) {
        if (explicit) return explicit;
        if (inv.documentProfile) return inv.documentProfile;

        var mode = String(inv.layoutMode || 'auto').toLowerCase();
        var trade = isTradeCustomer(inv);
        if (mode === 'a4') return PRESETS.TRADE_INVOICE_A4;
        if (mode === 'thermal') return thermalPresetFor(inv);
        return trade ? PRESETS.TRADE_INVOICE_A4 : thermalPresetFor(inv);
    }

    function thermalPresetFor(inv) {
        var vertical = (global.VERTICAL_PROFILE || {});
        return vertical.receiptTitle === 'DISPENSE RECEIPT'
            ? PRESETS.DISPENSE_RECEIPT_80MM : PRESETS.RETAIL_RECEIPT_80MM;
    }

    /**
     * The document's title. Channel decides receipt-vs-invoice (a trade buyer books an INVOICE); the vertical
     * profile supplies the retail wording, so a pharmacy says DISPENSE RECEIPT and a shop SALES RECEIPT.
     */
    function titleFor(inv, profile) {
        if (profile.title) return profile.title;                       // owner override (3g-4)
        // A PRESET's own title, as a message key. Presets are a static object literal evaluated at load, so a
        // literal string in one would never translate -- and a document title is the last thing that should be
        // stuck in English on a Pakistani distributor's paperwork. The owner override above still wins.
        if (profile.titleKey) return t(profile.titleKey);
        if (isTradeCustomer(inv)) return t('ui.js.docInvoice');
        var vertical = (global.VERTICAL_PROFILE || {});
        return vertical.receiptTitle || t('ui.js.docReceipt');
    }

    // ---------------------------------------------------------------- the renderer

    function normaliseColumns(profile) {
        // Unknown keys are DROPPED, not rendered blank — a stored template that references a field this build
        // no longer has must degrade to a slightly narrower document, never to an empty column.
        return (profile.lines || []).filter(function (c) { return LINE_FIELDS[c.key]; });
    }

    function buildContext(inv, profile) {
        var cust = inv.customer || {};
        var lines = inv.sales || [];
        var sums = { qty: 0, bonus: 0, value: 0, discount: 0, tax: 0, total: 0 };
        var maths = lines.map(function (s) {
            var m = lineMath(s);
            sums.qty += m.qty; sums.bonus += m.bonus; sums.value += m.value;
            sums.discount += m.discount; sums.tax += m.tax; sums.total += m.total;
            return m;
        });
        var grand = inv.grandTotal != null ? num(inv.grandTotal)
            : (inv.subTotal != null ? num(inv.subTotal) : sums.total);
        var owed = (inv.dueAmount != null && num(inv.dueAmount) < 0) ? -num(inv.dueAmount) : 0;
        var after = inv.balanceAfter != null ? num(inv.balanceAfter) : null;
        var previous = after == null ? null : Math.max(after - owed, 0);
        var ctx = {
            inv: inv, cust: cust, lines: lines, maths: maths, sums: sums,
            grand: grand, owed: owed, previousBalance: previous,
            taxLabel: inv.taxLabel || 'Tax',
            showDrCr: profile.showDrCr === true
        };
        ctx.words = (inv.showAmountInWords !== false && profile.totals
            && profile.totals.indexOf('amountInWords') !== -1) ? amountInWords(grand, inv, profile) : '';
        return ctx;
    }

    function renderHeaderFields(profile, ctx) {
        var groups = (profile.header && profile.header.columns) || [];
        var rendered = groups.map(function (group) {
            return (group || []).map(function (key) {
                var spec = HEADER_FIELDS[key];
                if (!spec) return '';                                   // whitelist: unknown key dropped
                var value = spec.resolve(ctx);
                if (!value) return '';                                  // absent value ⇒ no empty label
                var override = (profile.headerLabels || {})[key];
                return '<div class="dc-hf"><span class="dc-hl">' + escHtml(labelOf(spec, override))
                    + ':</span> <span class="dc-hv">' + escHtml(value) + '</span></div>';
            }).join('');
        });
        var nonEmpty = rendered.filter(function (h) { return h; });
        if (!nonEmpty.length) return '';
        return '<div class="dc-head dc-cols-' + Math.min(groups.length, 3) + '">'
            + rendered.map(function (h) { return '<div class="dc-hg">' + h + '</div>'; }).join('')
            + '</div>';
    }

    function renderTable(profile, ctx) {
        var cols = normaliseColumns(profile);
        if (!cols.length) return '';
        var head = cols.map(function (c) {
            var spec = LINE_FIELDS[c.key];
            var align = c.align || spec.align || 'left';
            return '<th class="dc-' + align + '"' + (c.width ? ' style="width:' + num(c.width) + '%"' : '')
                + '>' + escHtml(labelOf(spec, c.label)) + '</th>';
        }).join('');

        var body = ctx.lines.map(function (s, i) {
            var cellCtx = { s: s, m: ctx.maths[i], i: i, inv: ctx.inv, cust: ctx.cust };
            return '<tr>' + cols.map(function (c) {
                var spec = LINE_FIELDS[c.key];
                var align = c.align || spec.align || 'left';
                return '<td class="dc-' + align + '">' + escHtml(spec.resolve(cellCtx) || '') + '</td>';
            }).join('') + '</tr>';
        }).join('');

        // The in-table totals band ("Total: 50.00 … 4100.00"), printed only under columns that sum.
        var summable = cols.some(function (c) { return LINE_FIELDS[c.key].sum; });
        var foot = '';
        if (summable) {
            var first = true;
            foot = '<tfoot><tr>' + cols.map(function (c) {
                var spec = LINE_FIELDS[c.key];
                var align = c.align || spec.align || 'left';
                if (spec.sum) { first = false; return '<td class="dc-' + align + ' dc-sum">' + money(ctx.sums[spec.sum]) + '</td>'; }
                if (first) return '<td class="dc-sum">&nbsp;</td>';
                return '<td class="dc-sum"></td>';
            }).join('') + '</tr></tfoot>';
        }

        return '<table class="dc-t"><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody>'
            + foot + '</table>';
    }

    /** Per-rate tax breakdown, owner-toggleable (pos.receipt.showTaxBreakdown), default ON when absent. */
    function renderTaxBreakdown(ctx) {
        if (ctx.inv.showTaxBreakdown === false) return '';
        var byRate = {};
        (ctx.inv.sales || []).forEach(function (s) {
            var r = num(s.taxRate), amt = num(s.taxAmount);
            if (r > 0 && amt) byRate[r] = (byRate[r] || 0) + amt;
        });
        var keys = Object.keys(byRate);
        if (keys.length < 2) return '';
        return keys.sort(function (a, b) { return a - b; }).map(function (r) {
            return totalRow('&nbsp;&nbsp;' + escHtml(ctx.taxLabel) + ' @' + r + '%', money(byRate[r]), false);
        }).join('');
    }

    function totalRow(labelHtml, valueHtml, strong, wide) {
        return '<div class="dc-tr' + (strong ? ' dc-strong' : '') + (wide ? ' dc-wide' : '') + '">'
            + '<span>' + labelHtml + '</span><span>' + valueHtml + '</span></div>';
    }

    function renderTotals(profile, ctx) {
        var out = '';
        (profile.totals || []).forEach(function (key) {
            var spec = TOTAL_ROWS[key];
            if (!spec) return;                                          // whitelist
            var value = spec.resolve(ctx);
            if (value === '' || value == null) return;                  // absent ⇒ the row does not print
            var label = spec.dynamicLabel ? spec.dynamicLabel(ctx)
                : labelOf(spec, (profile.totalLabels || {})[key]);
            out += totalRow(escHtml(label), escHtml(value), spec.strong === true, spec.wide === true);
            if (key === 'taxTotal') out += renderTaxBreakdown(ctx);
        });
        return out;
    }

    function css(profile) {
        var a4 = profile.paper === 'A4' || profile.paper === 'A5';
        var page = a4
            ? '@page{size:' + (profile.paper === 'A5' ? 'A5' : 'A4') + ';margin:10mm}'
            : '@page{margin:0}';
        var body = a4
            ? 'body{font-family:Arial,Helvetica,sans-serif;color:#000;font-size:11px;padding:0}'
            : 'body{font-family:"Courier New",monospace;color:#000;width:80mm;padding:6mm 4mm;font-size:12px}';
        return page + '*{margin:0;padding:0;box-sizing:border-box}' + body
            + '.dc-c{text-align:center}.dc-right{text-align:right}.dc-left{text-align:left}.dc-sm{font-size:10px}'
            + '.dc-brand{font-size:' + (a4 ? '22px' : '16px') + ';font-weight:700;text-align:center;letter-spacing:.5px}'
            + '.dc-addr{text-align:center;font-size:' + (a4 ? '11px' : '10px') + ';margin-top:2px}'
            + '.dc-title{text-align:center;letter-spacing:2px;margin:' + (a4 ? '10px 0' : '2px 0 6px') + '}'
            + '.dc-title span{display:inline-block;padding:' + (a4 ? '4px 34px' : '0') + ';font-weight:700}'
            + '.dc-boxed span{border:1px solid #000}'
            + '.dc-head{margin:6px 0}'
            + (a4 ? '.dc-cols-2,.dc-cols-3{display:flex;gap:14px}.dc-hg{flex:1}' : '.dc-hg{display:block}')
            + '.dc-hf{margin:1px 0;font-size:' + (a4 ? '11px' : '11px') + '}'
            + '.dc-hl{font-weight:400}.dc-hv{font-weight:700}'
            + 'hr{border:0;border-top:1px ' + (a4 ? 'solid' : 'dashed') + ' #000;margin:6px 0}'
            + '.dc-t{width:100%;border-collapse:collapse;margin-top:4px}'
            + '.dc-t th{font-size:' + (a4 ? '10px' : '10px') + ';border-bottom:1px solid #000;'
            + 'border-top:' + (a4 ? '1px solid #000' : '0') + ';padding:3px 2px;white-space:nowrap}'
            + '.dc-t td{padding:' + (a4 ? '3px 2px' : '2px 0') + ';vertical-align:top;'
            + (a4 ? 'border-bottom:1px dotted #999;' : '') + 'font-size:' + (a4 ? '11px' : '12px') + '}'
            + '.dc-sum{border-top:1px solid #000;font-weight:700;padding-top:3px}'
            + '.dc-totwrap{' + (a4 ? 'width:46%;margin-left:auto;margin-top:8px' : 'margin-top:4px') + '}'
            + '.dc-tr{display:flex;justify-content:space-between;gap:10px;font-size:12px;margin:2px 0}'
            + '.dc-strong{font-weight:700;font-size:' + (a4 ? '15px' : '14px') + ';border-top:1px solid #000;padding-top:3px;margin-top:3px}'
            + '.dc-wide{display:block;text-align:' + (a4 ? 'right' : 'center') + ';font-weight:700;margin-top:4px}'
            + '.dc-wide span:first-child{display:none}'
            + '.dc-foot{text-align:center;margin-top:' + (a4 ? '18px' : '8px') + ';font-size:10px}'
            + '.dc-sign{display:flex;justify-content:space-between;margin-top:30px;font-size:11px}'
            + '.dc-sign div{border-top:1px solid #000;padding-top:3px;width:34%;text-align:center}'
            + '@media print{body{width:auto}}';
    }

    function renderLetterhead(inv, profile) {
        var lh = inv.letterhead || {};
        var vertical = global.VERTICAL_PROFILE || {};
        // Falls back to the vertical brand ONLY when the org has supplied nothing. Printing "MyPlus Pharmacy"
        // on a shop's own invoice was the pre-3g behaviour and is exactly what this replaces.
        var name = lh.businessName || lh.storeName || lh.organizationName || vertical.brand || 'MyPlus';
        var addr = [lh.addressLine1, lh.addressLine2, lh.phone].filter(function (x) { return x; }).join('   ');
        var logo = (profile.header && profile.header.showLogo && lh.logoUrl)
            ? '<div class="dc-c"><img src="' + encodeURI(lh.logoUrl) + '" alt="" style="max-height:60px"></div>' : '';
        return logo
            + '<div class="dc-brand" data-brand>' + escHtml(name) + '</div>'
            + (addr ? '<div class="dc-addr">' + escHtml(addr) + '</div>' : '');
    }

    /**
     * Render one document. `profile` is optional — omitted, it is resolved from the payload (channel, the
     * org's stored template, the layout override). The designer's live preview passes one explicitly, which
     * is what keeps the preview honest: it is this function, not a copy of it.
     */
    function buildHtml(inv, profile) {
        profile = resolveProfile(inv, profile);
        var ctx = buildContext(inv, profile);
        var title = titleFor(inv, profile);
        var boxed = (profile.header && profile.header.titleStyle === 'boxed') ? ' dc-boxed' : '';
        var regNo = inv.taxRegNo
            ? '<div class="dc-c dc-sm">' + escHtml(ctx.taxLabel) + ' Reg: ' + escHtml(inv.taxRegNo) + '</div>' : '';
        var footText = (profile.footer && profile.footer.text) || inv.footerText || t('ui.js.docThankYou');
        // The signature strip, as DATA. `footer.signature` is a list of message keys; absent, it stays exactly
        // the two boxes every invoice has always printed. A delivery challan needs more of them -- what was
        // collected at the door, what is still owed, who took delivery -- and expressing that as a list beats a
        // second footer renderer that would drift from this one.
        var signKeys = (profile.footer && profile.footer.signature)
            || ['ui.js.docPreparedBy', 'ui.js.docReceivedBy'];
        var sign = (profile.footer && profile.footer.showSignature)
            ? '<div class="dc-sign">' + signKeys.map(function (k) {
                  return '<div>' + escHtml(t(k)) + '</div>';
              }).join('') + '</div>'
            : '';

        return '<!doctype html><html><head><meta charset="utf-8"><title>'
            + escHtml(title + ' ' + (inv.invoiceNo || '')) + '</title><style>' + css(profile) + '</style>'
            + '</head><body>'
            + renderLetterhead(inv, profile)
            + '<div class="dc-title' + boxed + '"><span>' + escHtml(title) + '</span></div>'
            + renderHeaderFields(profile, ctx)
            + (profile.paper === '80mm' ? '<hr>' : '')
            + renderTable(profile, ctx)
            + '<div class="dc-totwrap">' + renderTotals(profile, ctx) + '</div>'
            + regNo
            + (footText ? '<div class="dc-foot">' + escHtml(footText) + '</div>' : '')
            + sign
            // B2B-P0 (#13). OFF unless the org turned it on: this prints on a document our customer hands to
            // THEIR customer, so it is opt-in, never a surprise on a paying client's invoices.
            + (inv.showPromo === true
                ? '<div class="dc-foot" style="opacity:.75;margin-top:4px">Powered by MaxTheService'
                  + '<br>maxtheservice.com</div>'
                : '')
            + '</body></html>';
    }

    // ---------------------------------------------------------------- printing

    function writeToFrame(html, frameId) {
        var frame = document.getElementById(frameId);
        if (!frame) {
            frame = document.createElement('iframe');
            frame.id = frameId;
            frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0';
            document.body.appendChild(frame);
        }
        var doc = frame.contentWindow.document;
        doc.open();
        doc.write(html);
        doc.close();
        return frame;
    }

    function printInvoiceObject(inv, profile) {
        var frame = writeToFrame(buildHtml(inv, profile), 'receiptFrame');
        setTimeout(function () {
            try { frame.contentWindow.focus(); frame.contentWindow.print(); } catch (e) { /* ignore */ }
        }, 300);
    }

    // Fetch the authoritative document by invoice number, then print.
    /**
     * The document, RESOLVED, as neutral data — the seam a second output format hangs off.
     *
     * <h3>Why this exists</h3>
     * Nothing in the back office downloaded until now: printing goes through a hidden iframe to
     * {@code window.print()}, which gives a shopkeeper paper and gives a manager nothing to keep. A PDF needs a
     * different emitter (pdfmake speaks tables, not HTML), and the temptation is to write one that lays the
     * document out again — at which point the PDF and the paper start to drift, and the first anyone notices is
     * a customer holding two versions of the same invoice.
     *
     * <p>So the rules stay in one place. This walks the SAME whitelist and calls the SAME resolvers the HTML
     * renderer does, and hands back what they produced: labels, cells, total rows. What differs between paper
     * and PDF is only how that is drawn.
     *
     * <p>A profile is resolved first, so this answers for whatever the renderer would actually have printed —
     * an owner's stored template included, not just the built-in presets.
     */
    function toPrintModel(inv, profile) {
        profile = resolveProfile(inv, profile);
        var ctx = buildContext(inv, profile);
        var cols = normaliseColumns(profile);

        var columns = cols.map(function (c) {
            var spec = LINE_FIELDS[c.key];
            return {
                key: c.key,
                label: labelOf(spec, c.label),
                align: c.align || spec.align || 'left',
                width: c.width || null
            };
        });

        var rows = ctx.lines.map(function (sale, i) {
            var cellCtx = { s: sale, m: ctx.maths[i], i: i, inv: ctx.inv, cust: ctx.cust };
            return cols.map(function (c) { return LINE_FIELDS[c.key].resolve(cellCtx) || ''; });
        });

        // Identical filtering to renderTotals: a row whose resolver answers blank does NOT print. That is how
        // one profile serves an invoice with no tax and one with tax, so the PDF must honour it too or it will
        // show empty rows the paper does not.
        var totals = [];
        (profile.totals || []).forEach(function (key) {
            var spec = TOTAL_ROWS[key];
            if (!spec) return;
            var value = spec.resolve(ctx);
            if (value === '' || value == null) return;
            totals.push({
                key: key,
                label: spec.dynamicLabel ? spec.dynamicLabel(ctx) : labelOf(spec, (profile.totalLabels || {})[key]),
                value: value,
                strong: spec.strong === true,
                wide: spec.wide === true
            });
        });

        var headerFields = [];
        ((profile.header && profile.header.columns) || []).forEach(function (group) {
            (group || []).forEach(function (key) {
                var spec = HEADER_FIELDS[key];
                if (!spec) return;
                var value = spec.resolve(ctx);
                if (value === '' || value == null) return;
                headerFields.push({ key: key, label: labelOf(spec, null), value: value });
            });
        });

        return {
            profile: profile,
            title: titleFor(inv, profile),
            invoiceNo: inv.invoiceNo || '',
            letterhead: inv.letterhead || {},
            paper: profile.paper,
            headerFields: headerFields,
            columns: columns,
            rows: rows,
            totals: totals,
            signature: (profile.footer && profile.footer.showSignature)
                ? ((profile.footer.signature || ['ui.js.docPreparedBy', 'ui.js.docReceivedBy']).map(function (k) {
                    return t(k);
                }))
                : [],
            footerText: (profile.footer && profile.footer.text) || inv.footerText || ''
        };
    }

    /**
     * Fetch one invoice and hand it to a callback, so callers do not each restate the read and its error path.
     *
     * <p>The payload is on {@code object}, not {@code data} — GenericResponse carries a single payload there,
     * and reading the wrong key returns undefined rather than failing, which is how a caller ends up rendering
     * a blank document.
     */
    function withInvoice(invoiceNo, then) {
        if (!invoiceNo) { if (global.showFormError) showFormError(t('ui.js.noInvoiceToPrint')); return; }
        $.get(serverContext + 'getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo), function (resp) {
            if (!resp || resp.status !== 'SUCCESS' || !resp.object) {
                if (global.showFormError) showFormError((resp && resp.message) || t('ui.js.couldNotLoadTheReceipt'));
                return;
            }
            then(resp.object);
        }).fail(function () { if (global.showFormError) showFormError(t('ui.js.couldNotLoadTheReceipt')); });
    }

    /**
     * OMS O8 slice 3 — print the DELIVERY CHALLAN for an invoice: the slip the shop signs for.
     *
     * <p>A one-line wrapper on purpose. It is the same renderer, the same fetch and the same print mechanism as
     * an invoice; only the profile differs. Anything more here would be a second document pipeline.
     */
    global.printChallan = function (invoiceNo) {
        global.printReceipt(invoiceNo, PRESETS.DELIVERY_CHALLAN_A4);
    };

    global.printReceipt = function (invoiceNo, profile) {
        if (!invoiceNo) { if (global.showFormError) showFormError(t('ui.js.noInvoiceToPrint')); return; }
        $.get(serverContext + 'getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo), function (resp) {
            if (!resp || resp.status !== 'SUCCESS' || !resp.object) {
                if (global.showFormError) showFormError((resp && resp.message) || 'Could not load the receipt.');
                return;
            }
            printInvoiceObject(resp.object, profile);
        }).fail(function () { if (global.showFormError) showFormError(t('ui.js.couldNotLoadTheReceipt')); });
    };

    /*
     * The renderer's public surface. 3g-4's designer renders its live preview through THIS buildHtml — the
     * production one — so a preview can never show a layout the printer would not produce. FIELD_WHITELIST is
     * exported so the designer offers exactly the fields the renderer can bind, and no others.
     */
    global.DocumentRenderer = {
        buildHtml: buildHtml,
        // The neutral resolved document, for any output format that is not HTML. See toPrintModel.
        toPrintModel: toPrintModel,
        withInvoice: withInvoice,
        resolveProfile: resolveProfile,
        PRESETS: PRESETS,
        FIELD_WHITELIST: FIELD_WHITELIST,
        isTradeCustomer: isTradeCustomer,
        amountInWords: amountInWords,
        lineMath: lineMath
    };
})(window);
