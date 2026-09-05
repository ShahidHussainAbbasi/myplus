var data=[]; // use a global for the submit and return data rendering in the examples
var tablesi;
var removed = false;
var tableSellReport;


$(document).ready(function() {
    // P5b active-store switcher (hides itself for a single-store business).
    loadMyStores();
    $(document).on('change', '#storeSwitcher', switchStore);

    // Hide voided sale/purchase rows by default; a per-table "Show voided" button flips it. Voided rows carry the
    // .row-voided class (added in createdRow). Other list tables never have voided rows, so this filter is a no-op
    // there. Registered once, globally, for all DataTables on the page.
    if (window.hideVoided === undefined) window.hideVoided = true;
    if (!window._voidFilterInstalled && $.fn && $.fn.dataTable) {
        window._voidFilterInstalled = true;
        $.fn.dataTable.ext.search.push(function(settings, data, dataIndex){
            if (window.hideVoided === false) return true;                 // showing everything
            var tr = settings.aoData[dataIndex] && settings.aoData[dataIndex].nTr;
            return !(tr && tr.className && tr.className.indexOf('row-voided') > -1);
        });
    }

    // B2B-P3e-1 (#6): mount the shared filter rail HERE, not inside loadSR — otherwise a user opening the
    // report sees no filters until they have already run it once, which is backwards.
    if (typeof mountSRFilters === 'function') mountSRFilters();

    // Sale Detail Report table. Columns (0-based): 0 Date, 1 Invoice#, 2 Product, 3 Qty, 4 List price,
    // 5 Unit price, 6 Line total, 7 Tax, 8 Net, 9 Customer, 10 Contact, 11 Payment, 12 Invoice due, 13 Margin (SF-10).
    tableSellReport = $('#tableSellReport').DataTable( {
        dom: 'Bfrtip',
        order: [[ 0, 'desc' ]],
        lengthMenu: [
            [ 10, 25, 50, -1 ],
            [ '10 rows', '25 rows', '50 rows', 'Show all' ]
        ],
        columnDefs: [ { targets: [3,4,5,6,7,8,12,13], className: 'num' } ],
        buttons: [
        	'pageLength',
            { extend: 'copyHtml5', footer: true, title: t('ui.js.saleDetailReport') },
            { extend: 'csvHtml5', footer: true, title: t('ui.js.saleDetailReport') },
            // PERF-4b: Excel and PDF now fetch their library on first click (js/common/lazy-export.js).
            // Same options, same behaviour — the ~903KB pdfmake payload just no longer loads on every
            // dashboard for a button most sessions never press. csv/copy/print need no library and are
            // untouched.
            lazyExcelButton({ footer: true, title: t('ui.js.saleDetailReport') }),
            { extend: 'print', footer: true, title: t('ui.js.saleDetailReport') },
        	lazyPdfButton({
              orientation: 'landscape',
              pageSize: 'LEGAL',
              footer: true,
              title: t('ui.js.saleDetailReport')
            })
        ],
	    
	    "footerCallback": function ( row, data, start, end, display ) {
	        var api = this.api();

	        // Strip formatting (commas / currency) so numeric columns can be summed.
	        var intVal = function ( i ) {
	            return typeof i === 'string' ? (i.replace(/[^0-9.\-]/g, '') * 1 || 0) :
	                   typeof i === 'number' ? i : 0;
	        };
	        var sumCol = function ( idx ) {
	            return api.column( idx ).data().reduce( function (a, b) { return intVal(a) + intVal(b); }, 0 );
	        };
	        // 3 Qty (plain), 6 Line total, 7 Tax, 8 Net (money). Invoice due (12) is invoice-level — not summed
	        // per line to avoid double counting; the KPI card shows outstanding due by distinct invoice.
	        $( api.column(3).footer() ).html( srNum( sumCol(3) ) );
	        $( api.column(6).footer() ).html( srMoney( sumCol(6) ) );
	        $( api.column(7).footer() ).html( srMoney( sumCol(7) ) );
	        $( api.column(8).footer() ).html( srMoney( sumCol(8) ) );
	        $( api.column(13).footer() ).html( srMoney( sumCol(13) ) );   // SF-10: total margin
	    }
    } );
 
    $('a.toggle-vis').on( 'click', function (e) {
        e.preventDefault();
        // Get the column API object
        var column = datatable.column( $(this).attr('data-column') );
 
        // Toggle the visibility
/*        if(column.visible()){
            column.visible( ! column.visible() );
        }
*/
        column.visible( ! column.visible() );
        if(column.visible()){
        	$(this).css("color", "#337ab7");
        }else{
        	$(this).css("color", "#727374");
        }
    } );
    
    //invoice table
    tablesi = $('#tablesi').DataTable( {
    	 "searching": false,
    	 "paging": false,
    	 "info":false,
 	    "footerCallback": function ( row, data, start, end, display ) {
 	        var api = this.api(), data;
 	
 	        // Remove the formatting to get integer data for summation
 	        var intVal = function ( i ) {
 	            return typeof i === 'string' ?
 	                i.replace(/[\$,]/g, '')*1 :
 	                typeof i === 'number' ?
 	                    i : 0;
 	        };
 	
 	        // quantity Total over all pages
 	        total = api.column(2).data().reduce( function (a, b) {
 	                	return intVal(a) + intVal(b);
 	            	}, 0 );
 	        // Update footer
 	        $( api.column(2).footer() ).html(total);

 	        // sell Total over all pages
 	       total = api.column(3).data().reduce( function (a, b) {
 	                	return intVal(a) + intVal(b);
 	            	}, 0 );
 	        // Update footer
 	        $( api.column(3).footer() ).html(total);

 	        // discount Total over all pages
  	       total = api.column(4).data().reduce( function (a, b) {
  	                	return intVal(a) + intVal(b);
  	            	}, 0 );
  	        // Update footer
  	        $( api.column(4).footer() ).html(total);

 	        // totals Total over all pages
   	       total = api.column(5).data().reduce( function (a, b) {
   	                	return intVal(a) + intVal(b);
   	            	}, 0 );
   	        // Update footer
   	        $( api.column(5).footer() ).html(total);

 	        
 	    }    
     } );
    
    tablesi.columns( [0] ).visible( false );
    
    $('#tablesi tbody').on( 'click', 'tr', function () {
        if ( $(this).hasClass('selected') ) {
            $(this).removeClass('selected');
        }else {
        	tablesi.$('tr.selected').removeClass('selected');
            $(this).addClass('selected');
            if(removed)
            	tablesi.row(this).remove().draw( false );
            
            removed = false;
        }
    } );
 
    
  //All button get initialized when user switch form
    // Show dashboard on page load
    $('#DashboardDiv').show();
    getDashboardData();
    loadPosFeatureFlags();   // owner-configurable UI toggles (e.g. barcode scanning) — apply on load

    $("#addInviceItem").off().click(function() {
//    	window.open(window.location.hostname + ':' + window.location.port+""+serverContext+"reports/createdocument.docx");
    //	window.print(window.location.hostname + ':' + window.location.port+""+serverContext+"reports/createdocument.docx");
    	//window.print("resources/file/2.docx");
//    	isLoaded();
//    	return false;
        //If all form's required fields are filled
    	validateForm();
        if(formValidated){
        	// U3: a fractional piece count is refused here, mirroring the server's rule rather than
        	// replacing it. The server still refuses it too — this only saves the cashier a round trip.
        	if (window.LooseSell && !LooseSell.validate()) return false;
        	var obj  = formToJSON("Sell");
//        	obj = populateFormData();
        	obj.itemName = $( "#sellItemDD :selected" ).text();
			// M4e.1b (slice 98): the picker value IS the catalog productId now — the cart line keys by productId.
			var pickVal = $("#sellItemDD").val();
			obj.productId = pickVal;            // cart key + productId-native submission
			obj.itemId = pickVal;               // back-compat field (ignored once productId present; removed in M4e.5)
			obj.stock.itemId = obj.itemId;
			// U3: on a LOOSE line this sets soldUnit/soldQuantity and converts `quantity` to packs.
			// A no-op on every ordinary line, which is every line until a shop switches loose selling on.
			if (window.LooseSell) { LooseSell.decorate(obj); LooseSell.stampPackSize(obj); }   // U8b: a PACK line of a divisible product carries its pack size too
			obj.stock.itemName = obj.itemName;
			// The rate this line SOLD at = the cashier's #sellSellRate (bound to stock.bsellRate). Surface it as
			// line.sellRate so the /addSell submission carries it â†’ SagaSellService records the actual sold rate
			// (and snapshots the catalog price separately). Without this the sold rate was dropped (saga fell back
			// to the catalog price).
			obj.sellRate = (obj.stock && obj.stock.bsellRate != null) ? obj.stock.bsellRate : null;
			// B2B-P2-UI: record whether this rate is one the system set or one the cashier typed. Only a
			// system-set rate may be re-priced later when the customer is chosen; an override must survive.
			obj.autoRate = (window._sellAutoRate != null && Number(obj.sellRate) === Number(window._sellAutoRate))
				? Number(window._sellAutoRate) : null;
			// B2B-P3g: free goods on this line ("Bon." on a trade invoice) — 20 billed, 2 free. Blank/zero
			// sends nothing, so a shop that never gives bonus stock is unaffected. Carried through to
			// Sell.bonus_quantity and PRINTED; it takes no part in the line total, tax or margin, which is
			// why it can ship before decision D-2 settles whether bonus should also move inventory.
			var bonusIn = $("#sellBonus").val();
			obj.bonusQuantity = (bonusIn != null && bonusIn !== '' && Number(bonusIn) > 0) ? Number(bonusIn) : null;
			// var item = {"id":$("#sellItemDD").val(), "name":$( "#sellItemDD :selected" ).text()};
			// obj.item = item;

        	// (cart insert handled below: append, or replace-in-place when editing)
			var arr = [
				// SF-9: show the discount WITH its type so "10" is unambiguous — "10%" (percent) vs "10 (Amt)" (fixed).
				// U4: "5 tablets" on a loose line, obj.quantity on every other. Before this, the manual add
				// showed 0.5 while a `5L*CODE` scan of the SAME product showed 5 — two unlabelled numbers
				// for one sale, on the screen a cashier watches while ringing up.
				obj.productId,obj.itemName,looseQtyText(obj),obj.stock.bsellRate,
				(obj.stock && obj.stock.bsellDiscount ? (Number(obj.stock.bsellDiscount) + ((obj.stock.bsellDiscountType==='1'||obj.stock.bsellDiscountType==='%') ? '%' : ' (Amt)')) : (obj.stock ? obj.stock.bsellDiscount : '')),
				($("#sellrm").val()),"<button id='DII' onclick=UIT("+obj.productId+")>Del</button>"
				];
			tablesi.row.add(arr).draw();
			// Edit mode ("Update Item"): if this item is already a line on the invoice, REPLACE it in
				// place (no duplicate). A brand-new item is still appended. New-sale mode always appends.
				var existingIdx = window.editingInvoice
					? data.findIndex(function(d){ return String(d.productId) === String(obj.productId); })
					: -1;
				if (existingIdx >= 0) {
					// The item is locked in edit mode, so carry the original line's stock identity onto the
						// edited line. updateSell keys stock by stockId — the sell form never sets it, so without
						// this the line would save with NULL stock and drop out of the report.
						var prevStock = data[existingIdx].stock || {};
						if (prevStock.stockId != null) obj.stock.stockId = prevStock.stockId;
						if (prevStock.batchNo != null) obj.stock.batchNo = prevStock.batchNo;
						data[existingIdx] = obj;
					// tablesi.rows().every(function(){
					// 	// if (String(this.data()[0]) === String(obj.itemId)) { this.data(arr); }
					// 	this.data(arr);
					// });
					// tablesi.draw(false);
				} else {
					data.push(obj);
					// tablesi.row.add(arr).draw();
				}
			// B1 (pharmacy): same early warning on the manual Add-to-Cart path as on the scan path.
			if (typeof rxNoticeIfNeeded === 'function') rxNoticeIfNeeded(obj.productId, obj.itemName);
			resetForm();
			resetBSDD('sellItemDD');
			// Cart changed (item added / qty updated) â†’ recompute Change & Due from the live cart total
			// (#sellTotal). Standard POS: Due = bill − Received for THIS invoice.
			calculateChange();
        }else{
        	showFormError(t('ui.js.pleaseSelectAnItemAndEnterA'));
        	return false;
        }
    });
} );

function UIT(id){
	// M4e.1b (slice 98): cart lines key by productId now.
	data.forEach(function(d,i){
		if(id==d.productId){
			removed = true;
			data.splice(i,1);
		}
	});
}

// â”€â”€â”€ Barcode-first sell: scan a barcode/SKU â†’ resolve â†’ add a cart line â”€â”€â”€â”€â”€â”€â”€â”€
// A wedge scanner types the code + Enter into #sellScan. We look the product up (barcode or sku), then append a
// cart line at the catalog price (qty 1), or increment the qty if it's already in the cart. The server still
// validates stock at addSell (FEFO reserve), so a scan of an out-of-stock item is rejected at submit.
/**
 * Split a scan-box entry into {qty, code}, supporting the POS quantity-multiplier idiom `12*ABC123`.
 *
 * Selling twelve of something used to mean twelve scans, or abandoning the scan box for the full form —
 * the single biggest cost with people waiting. A count, a star, then the code (or scan) does it once.
 *
 * PURE FUNCTION, no DOM: it is the piece with the interesting edge cases, so it is exported for the gate
 * to exercise directly rather than only through the UI.
 *
 * Refuses rather than guesses. A quantity that is zero, negative, fractional or not a number is a
 * MISTAKE, and the honest response is to say so — silently falling back to 1 would put a line the
 * cashier did not intend on a real invoice.
 *
 * @returns {{qty:number, code:string}} on success, or {error:'...'} describing what was wrong.
 */
function parseScanEntry(raw){
	var s = (raw == null ? '' : String(raw)).trim();
	if(!s) return { error: 'empty' };

	var star = s.indexOf('*');
	if(star < 0) return { qty: 1, code: s, unit: 'PACK' };   // no multiplier => today's behaviour, exactly

	var qtyPart = s.substring(0, star).trim();
	var code    = s.substring(star + 1).trim();

	if(!qtyPart) return { error: 'noQty' };             // "*ABC" — a star with nothing in front
	if(!code)    return { error: 'noCode' };            // "12*"  — a count with nothing to count

	/*
	 * U3 — one optional suffix, not a second grammar.
	 *
	 *     12*CODE   twelve packs   (unchanged)
	 *     5L*CODE   five loose pieces
	 *
	 * The multiplier is still digits-only; `L` is a single reserved marker on the END of it. Extending the
	 * existing parser rather than adding a second one matters because this function is the ONLY place the
	 * scan box is interpreted — a second parser would disagree with this one on some input nobody thought to
	 * test, and the symptom would be a mis-priced line rather than an error.
	 *
	 * ⚠ A scanned MANUFACTURER barcode always means the pack. A GTIN is printed on the pack by whoever made
	 * it and cannot mean "one tablet" — so LOOSE is only ever reached when the operator typed the marker.
	 */
	var unit = 'PACK';
	if(/[Ll]$/.test(qtyPart)){
		unit = 'LOOSE';
		qtyPart = qtyPart.slice(0, -1);
		if(!qtyPart) return { error: 'noQty' };         // "L*ABC" — a marker with no count
	}

	// Digits only: parseInt('12abc') would happily return 12 and sell twelve of the wrong thing.
	if(!/^\d+$/.test(qtyPart)) return { error: 'badQty', qtyText: qtyPart };

	var qty = parseInt(qtyPart, 10);
	if(!(qty > 0)) return { error: 'badQty', qtyText: qtyPart };
	return { qty: qty, code: code, unit: unit };
}
window.parseScanEntry = parseScanEntry;

function sellScanAdd(){
	var $in = $('#sellScan');
	var raw = ($in.val() || '').trim();
	if(!raw){
		// Enter on an EMPTY scan box = "nothing more to add" -> go to the customer/checkout.
		// It lives here, not in a delegated handler: the input's inline onkeydown calls
		// event.stopPropagation() on every Enter, so document-level handlers never see it.
		if (typeof posGoToCheckout === 'function') posGoToCheckout();
		return;
	}

	// The multiplier is part of P2 (pos.keyboard.shortcuts.enabled). With it off, a '*' is just another
	// character in the code — which is what a shop that has never used the idiom expects.
	var qty = 1, code = raw, unit = 'PACK', typedMarker = false;
	if(window.posShortcutsEnabled === true){
		var parsed = parseScanEntry(raw);
		if(parsed.error){
			// Keep what they typed in the box: the fix is usually one character, and clearing it would
			// make them re-scan.
			if(parsed.error === 'badQty')      sellScanMsg(t('ui.js.scanQtyNotANumber', parsed.qtyText), true);
			else if(parsed.error === 'noQty')  sellScanMsg(t('ui.js.scanQtyMissing'), true);
			else if(parsed.error === 'noCode') sellScanMsg(t('ui.js.scanCodeMissing'), true);
			$in.focus();
			return;
		}
		qty = parsed.qty;
		code = parsed.code;
		unit = parsed.unit || 'PACK';
		// U7: the operator stated a quantity/unit explicitly. A sticker must not override or multiply it.
		typedMarker = (raw.indexOf('*') >= 0);
	}

	$in.val('');
	/*
	 * U7 — /scanProduct answers what the CODE means, not merely which product it is:
	 *
	 *     { product: {...}, soldUnit: "LOOSE", quantity: 1, ownSticker: true }
	 *
	 * A manufacturer barcode answers PACK x 1 — the same behaviour the till has always had, now stated by
	 * the server rather than assumed by this function.
	 *
	 * ⚠ A TYPED MARKER OUTRANKS THE STICKER. If the operator wrote `5L*LP-4471`, they said "five loose" and
	 * meant it; the sticker's own quantity must not multiply on top of that, or one deliberate keystroke
	 * silently becomes five. The typed form wins, and the sticker supplies only what was not typed.
	 */
	$.get(serverContext + 'scanProduct', { code: code }, function(resp){
		var res = (typeof resp === 'string') ? (resp ? JSON.parse(resp) : null) : resp;
		if(res && res.error){ sellScanMsg(res.error, true); $in.focus(); return; }
		var ref = res ? (res.product || res) : null;
		if(!ref || ref.id == null){ sellScanMsg('No product for "' + code + '"', true); $in.focus(); return; }
		if(res && res.ownSticker && !typedMarker){
			unit = res.soldUnit || 'PACK';
			qty  = (Number(res.quantity) > 0) ? Number(res.quantity) : 1;
		}
		// A loose scan needs the SCANNED product's pack rules. One extra call, only on a `5L*` scan, which
		// is the rare path — an ordinary scan is untouched and still costs exactly one request.
		if(unit === 'LOOSE'){
			$.get(serverContext + 'looseInfo', { productId: ref.id })
				.done(function(lr){
					var li = (typeof apiOk === 'function' && apiOk(lr)) ? apiData(lr) : null;
					if(!li || !li.allowLoose){
						sellScanMsg(t('ui.js.looseNotAllowed'), true); $in.focus(); return;
					}
					scanAddToCart(ref, qty, 'LOOSE', li);
					sellScanMsg('Added ' + (ref.name || ref.sku || ('#' + ref.id)) + ' ×' + cartQty(ref.id), false);
					$in.focus();
				})
				.fail(function(){ sellScanMsg(t('ui.js.looseUnavailable'), true); $in.focus(); });
			return;
		}
		scanAddToCart(ref, qty, 'PACK', null);
		sellScanMsg('Added ' + (ref.name || ref.sku || ('#' + ref.id)) + ' ×' + cartQty(ref.id), false);
		$in.focus();
	}, 'json').fail(function(){ sellScanMsg('Lookup failed (is catalog-service up?).', true); $in.focus(); });
}
function sellScanMsg(msg, err){ $('#sellScanMsg').text(msg).css('color', err ? '#c0392b' : '#0f6e56'); }
function cartQty(pid){ var d = data.find(function(x){ return String(x.productId) === String(pid); }); return d ? d.quantity : 1; }

/** @param qty units to add (default 1). The `12*CODE` multiplier passes it; every other caller omits it. */
/**
 * @param unit 'LOOSE' for a `5L*CODE` scan, else packs.
 * @param li   the SCANNED product's pack rules ({packSize, looseRate, allowLoose}), or null.
 *
 * ⚠ `li` is passed in rather than read from LooseSell, because LooseSell holds the rules for the product on
 * the LINE — which on a scan is usually a different product entirely. Reading it here would price the scanned
 * item using another product's pack size.
 */
function scanAddToCart(ref, qty, unit, li){
	var pid = ref.id;
	var price = (ref.sellingPrice != null) ? Number(ref.sellingPrice) : 0;
	var name = ref.name || ref.sku || ('#' + pid);
	// Guard the default HERE as well as in the parser: this is also called from the pharmacy dispense
	// path and any future caller, and a missing argument must never become NaN on an invoice line.
	var n = (Number(qty) > 0) ? Math.floor(Number(qty)) : 1;
	var idx = data.findIndex(function(d){ return String(d.productId) === String(pid); });

	// BUGFIX (found while building P2's exact-cash key): the scan path used to push '' into the cart
	// grid's TOTAL column and never set line.totalAmount. #sellTotal is that column's footer sum, so a
	// scanned-only cart totalled ZERO — and calculateChange() derives Change and "Due (this sale)"
	// from #sellTotal, so a cashier scanning items with no customer selected saw Due 0.00 on a sale
	// that was owed. requoteSellCart() fills the column in, but ONLY once a customer is chosen
	// (sellQuoteContext returns null otherwise), which is why the gap survived: the B2B path masked it.
	// The line's money is computed with the SAME sellLineMath() the manual add and the re-quote use, so
	// the three can never drift.
	var lineMath = function(units){ return sellLineMath(price, units, 0, 0, '0'); };

	if(idx >= 0){
		// Already scanned â†’ bump qty on the existing line (cart + grid), and move its money with it.
		data[idx].quantity = (Number(data[idx].quantity) || 0) + n;
		var mUp = lineMath(data[idx].quantity);
		data[idx].totalAmount = mUp.total;
		data[idx].netAmount = mUp.profit;
		tablesi.rows().every(function(){
			var row = this.data();
			if(String(row[0]) === String(pid)){
				row[2] = data[idx].quantity;
				row[5] = mUp.receivable;     // the footer sums THIS column — a blank here reads as zero
				this.data(row);
			}
		});
		tablesi.draw(false);
	} else {
		// New line — mirror the shape a manual "Add to Cart" pushes (data[] is submitted as `sales`).
		var m = lineMath(n);
		var obj = {
			productId: pid, itemId: pid, itemName: name,
			quantity: n, sellRate: price, description: ref.description || '',
			totalAmount: m.total, netAmount: m.profit,
			// B2B-P2-UI: the scan path prefills the CATALOG price too, so mark it re-priceable and quote.
			autoRate: Number(price),
			stock: { itemId: pid, itemName: name, bsellRate: price, bsellDiscount: '', bsellDiscountType: '0' }
		};
		// SER-3: carry the serial box onto a SCANNED line too.
		//
		// The manual Add-to-Cart path gets this free — formToJSON("Sell") reads the form by name — but this
		// path builds its line object by hand, so without this a scanned handset would arrive with no serial
		// and the server would refuse it. The operator would then be told to enter an IMEI they had already
		// typed, with no way to make the scan work at all.
		var scannedSerial = $.trim($('#sellSerials').val() || '');
		if (scannedSerial !== '') obj.serials = scannedSerial;
		// U3: a `5L*CODE` scan is a LOOSE line. The server derives quantity and rate from soldQuantity, so
		// what the browser computes here is DISPLAY only — it cannot mis-sell, only mis-show.
		// U8b: the scanned ProductRef carries packSize, so a PACK line of a divisible product records it too.
		if(ref && ref.packSize > 1 && obj.packSizeSnapshot == null) obj.packSizeSnapshot = ref.packSize;
		if(unit === 'LOOSE' && li && li.allowLoose && li.packSize > 0){
			obj.soldUnit = 'LOOSE';
			obj.soldQuantity = n;
			obj.quantity = Math.round((n / li.packSize) * 10000) / 10000;
			obj.sellRate = Number(li.packRate) || price;
			obj.stock.bsellRate = obj.sellRate;
		}
		data.push(obj);
		// U4: the same formatter as the manual path, so the two can no longer disagree.
		tablesi.row.add([pid, name, looseQtyText(obj), price, '', m.receivable,
			"<button id='DII' onclick=UIT(" + pid + ")>Del</button>"]).draw();
	}
	// A scanned line must be priced for the buyer exactly like a manually added one.
	requoteSellCart();
	// B1 (pharmacy): warn early if this is a prescription-only medicine; the server still refuses it at submit.
	if (typeof rxNoticeIfNeeded === 'function') rxNoticeIfNeeded(pid, name);
	calculateChange();   // recompute Change & Due from the live cart total
}

// â”€â”€â”€ Edit an existing sale (invoice) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Clicking a row in the Sell report loads that row's WHOLE invoice (all line items + customer +
// paid/due) back into the cart (iDiv) and the sell form, in an "editing INV-xxxx" state. Saving
// then routes to updateSell (same invoice #, stock & dues adjusted by the deltas).
function loadSellForEdit(sellId){
	edit = true;
	$.get(serverContext + "getSellInvoice?sellId=" + encodeURIComponent(sellId), function(resp){
		if(!resp || resp.status !== "SUCCESS" || !resp.object){
			showFormError((resp && resp.message) || "Could not load this sale for editing.");
			return;
		}
		var inv = resp.object;
		// 1) clear the current cart
		data.length = 0;
		if(tablesi){ tablesi.clear(); }
		// 2) rebuild the cart from the invoice's line items
		(inv.sales || []).forEach(function(line){
			var stk = line.stock || {};
			stk.itemId = line.itemId;
			stk.itemName = line.itemName;
			var item = {
				sellId: line.sellId,            // original line — lets updateSell revert the right stock
				quantity: line.quantity,
				itemId: line.itemId,
				productId: line.productId,      // M4b: keep the productId-native line on edit
				itemName: line.itemName,
				totalAmount: line.totalAmount,
				netAmount: line.netAmount,
				sellRate: line.sellRate,
				discount: line.discount,
				dt: line.dt,
				srp: line.srp,
				stock: stk
			};
			data.push(item);
			$("#sellRec").val('');
			// tablesi.row.add([
			// 	item.itemId, escHtml(item.itemName || ''), item.quantity,
			// 	stk.bsellRate, stk.bsellDiscount, item.totalAmount,
			// 	"<button id='DII' onclick=UIT(" + item.itemId + ")>Del</button>"
			// ]);
		});
		// if(tablesi){ tablesi.draw(); }
		// 3) LOCK the customer — in edit mode you change quantities/payment, not WHO the customer is.
		//    If the invoice's customer is in the dropdown, show Select mode with it chosen + disabled;
		//    otherwise show Manual mode. Either way the name field is filled (the save reads it) and the
		//    customer inputs are disabled. The customerId is remembered so updateSell updates THAT
		//    customer in place (no duplicate).
		var custId = inv.customer ? (inv.customer.customerId != null ? inv.customer.customerId
		                          : (inv.customer.id != null ? inv.customer.id : null)) : null;
		var inDD = custId != null && $('#sellCustomerDD option[value="' + custId + '"]').length > 0;
		if(typeof onCustomerModeChange === 'function') onCustomerModeChange(inDD ? 'select' : 'manual');
		if(inDD){ $('#sellCustomerDD').val(String(custId)); }
		$("#sellCN").val(inv.customer ? (inv.customer.name || '') : '');     // the save reads name/contact
		$("#sellCC").val(inv.customer ? (inv.customer.contact || '') : '');
		// SF-1/SF-2: show what was already paid on this invoice; "Amount Received" now means ADDITIONAL payment
		// (the server keeps the prior payment and adds the new tender). Received stays empty by default.
		window.editingPaid = Number(inv.paidAmount != null ? inv.paidAmount : 0);
		$("#sellPaidSoFar").val(window.editingPaid.toFixed(2));
		$("#sellPaidSoFarWrap").show();
		$("#sellRecLabel").text('Additional payment');
		$("#sellRec").val('');
		$("#sellCh,#sellDueThis").val('');         // recomputed (incl. prior paid) once items/Received change
		window.selectedCustomerDue = null;          // hide account preview while editing (avoids double-count)
		$("#sellAccountRow").hide();
		// $('#sellCustomerDD').prop('disabled', true);   // customer cannot be changed while editing
		// $('#sellCN').prop('disabled', true);
		// $('#sellCC').prop('disabled', true);
		// 4) enter edit state + show the banner
		window.editingInvoice = { chId: inv.customer_history_id, invoiceNo: inv.invoiceNo, customerId: custId };
		showSellEditBanner(inv.invoiceNo);
		setSellItemBtnMode(true);   // the cart-add button becomes "Update Item" while editing
		// Auto-load the line into the form (item shown but LOCKED, qty editable) so the cashier just
		// adjusts the quantity and clicks "Update Item".
		if(data.length) loadCartLineIntoForm(data[0]);
		// 5) bring the form into view
		try { $('html, body').animate({ scrollTop: $('#sellDiv').offset().top }, 300); } catch(e){}
		updateReadOnly(true);
	}).fail(function(){
		showFormError("Could not load this sale for editing.");
	});
}

function showSellEditBanner(invoiceNo){
	$('#sellEditBanner').remove();
	var banner = $(
		"<div id='sellEditBanner' class='alert alert-info' style='margin:8px 0;display:flex;align-items:center;gap:10px'>"
		+ "<span class='glyphicon glyphicon-pencil'></span> "
		+ "<span><b>Editing invoice " + escHtml(invoiceNo || '') + "</b> — change items / amounts, then click <b>Add Sell</b> to update.</span>"
		+ "<button type='button' id='cancelSellEdit' class='btn btn-xs btn-default' style='margin-left:auto'>Cancel edit</button>"
		+ "</div>");
	$('#iDiv').before(banner);
	$('#cancelSellEdit').off().on('click', cancelSellEdit);
}

// Toggle the cart-add button between "Add to Cart" (new sale) and "Update Item" (editing an invoice).
function setSellItemBtnMode(editing){
	var $b = $('#addInviceItem');
	if(!$b.length) return;
	$b.html(editing
		? "<span class='glyphicon glyphicon-pencil'></span> Update Item"
		: "<span class='glyphicon glyphicon-shopping-cart'></span> Add to Cart");
}

// Load one cart line into the item form for editing. In edit mode the ITEM is FIXED — the dropdown is
// disabled so only the quantity/amounts of that line can change; "Update Item" then replaces this same
// line in place.
function loadCartLineIntoForm(line){
	if(!line) return;
	// M4e.1b (slice 98): (re)load the catalog-product picker (value = productId), then select THIS line by its
	// productId. The first-edit race (picker not yet populated) is handled by reloading here. If the product isn't in
	// the list (e.g. deleted â†’ orphaned sale), inject a one-off option so the sale stays editable.
	// PERF-8: served from the cached picker (js/common/product-picker.js). On an edit this is now
	// free — the catalogue was already loaded when the section opened.
	ProductPicker.load(function(list){
		var html = ProductPicker.optionsHtml(list);
		var $dd = $('#sellItemDD').empty().append(html);
		var pid = (line.productId != null) ? line.productId : line.itemId;   // cart lines key by productId
		var $opt = (pid != null) ? $dd.find('option[value="' + pid + '"]') : $();
		if(!$opt.length && pid != null){
			$dd.append($('<option>').val(pid).attr('data-product', pid)
				.text(line.itemName || ('Product #' + pid)));
			$opt = $dd.find('option[value="' + pid + '"]');
		}
		$dd.val($opt.val());
		if($dd.data('selectpicker')) $dd.selectpicker('refresh');
		var sel = $dd.val();
		if(sel && /^\d+$/.test(sel)) loadStock($dd.find(':selected').text(), sel);   // sel is a productId now
		/*
		 * U4 — THE EDIT PATH IS THE ONE THAT CAN LOSE MONEY.
		 *
		 * `line.quantity` on a loose line is 0.5. Put that in this box and the cashier reads it as PIECES
		 * while the seven numeric readers treat it as PACKS — so editing a sale of five tablets and clicking
		 * Update would re-submit half a pack of packs, silently, at the pack rate.
		 *
		 * So the box is loaded with what was actually sold, and the unit toggle is set to match. U3's
		 * decorate() then re-derives the conversion on submit, and the edit round-trips.
		 */
		var d = (typeof looseDisplay === 'function') ? looseDisplay(line) : null;
		$('#sellItems').val(d && d.isLoose ? d.qty : line.quantity);   // loadStock won't override >0
		if (d && d.isLoose && window.LooseSell) {
			// After loadStock's async /looseInfo settles, or the toggle would be re-drawn as PACK over it.
			setTimeout(function () { LooseSell.setUnit('LOOSE'); }, 600);
		}
		$dd.prop('disabled', true);                           // lock the item while editing
		if($dd.data('selectpicker')) $dd.selectpicker('refresh');
	});
}

// Leave edit mode: drop the editing flag, banner, restore the button label, and UNLOCK the item
// dropdown. Safe to call when not editing (it just normalises the controls). Called on Cancel and
// after a successful save.
function exitSellEditMode(){
	window.editingInvoice = null;
	edit = false;
	// SF-1/SF-2: drop the edit-only "Already paid" display + restore the Received label.
	window.editingPaid = 0;
	$("#sellPaidSoFarWrap").hide();
	$("#sellPaidSoFar").val('');
	$("#sellRecLabel").text('Amount Received');
	$('#sellEditBanner').remove();
	setSellItemBtnMode(false);
	$('#sellItemDD').prop('disabled', false);
	if($('#sellItemDD').data('selectpicker')) $('#sellItemDD').selectpicker('refresh');
	$('#sellCustomerDD').prop('disabled', false);   // unlock the customer controls
	$('#sellCN').prop('disabled', false);
	$('#sellCC').prop('disabled', false);
}

function cancelSellEdit(){
	data.length = 0;
	if(tablesi){ tablesi.clear().draw(); }
	$("#sellCN,#sellCC,#sellRec").val('');
	if(typeof resetForm === 'function') resetForm();
	exitSellEditMode();
	updateReadOnly(false);
}

// â”€â”€â”€ Team / Users (owner-only) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// The company SUPER owner manages team members. Uses a custom show (not the generic .dropdown path)
// so it doesn't trigger loadDataTable for a non-existent "Team" entity.
// showTeam / loadTeamUsers / addTeamUser / teamMsg / the location picker now live in /js/common/team.js —
// one implementation shared with education (which previously had no team screen at all).

// G3 (slice 35): org tax policy. Same direct-show pattern as showTeam (no DataTable entity).
function showTaxSettings(){
	$('.formDiv').hide();
	$('#TaxSettingDiv').show();
	loadTaxSetting();
	loadTaxCodesAdmin();   // multi-rate tax: the tax-code master table
}

function loadTaxSetting(){
	$.get(serverContext + "getTaxSetting", function(resp){
		var s = (resp && resp.object) ? resp.object : {};
		$('#taxEnabled').prop('checked', s.enabled === true);
		$('#taxInputEnabled').prop('checked', s.inputTaxEnabled === true);
		$('#taxMode').val(s.taxMode === 'INCLUSIVE' ? 'INCLUSIVE' : 'EXCLUSIVE');
		$('#taxDefaultRate').val(s.defaultRate != null ? s.defaultRate : '');
		$('#taxLabel').val(s.taxLabel != null ? s.taxLabel : 'Tax');
		$('#taxRegNo').val(s.taxRegNo != null ? s.taxRegNo : '');
	}).fail(function(){
		showFormError(t('ui.js.couldNotLoadTaxSettings'));
	});
}

function saveTaxSetting(){
	$.ajax({
		type: 'POST',
		url: serverContext + "saveTaxSetting",
		dataType: 'json',
		data: {
			'enabled': $('#taxEnabled').is(':checked'),
			'inputTaxEnabled': $('#taxInputEnabled').is(':checked'),
			'taxMode': $('#taxMode').val(),
			'defaultRate': $('#taxDefaultRate').val() || '0',
			'taxLabel': $('#taxLabel').val() || 'Tax',
			'taxRegNo': $('#taxRegNo').val() || ''
		},
		success: function(data){
			if (data && (data.status === 'SUCCESS' || data.message)) {
				showSaleSuccess((data.message) || 'Tax settings saved.');
			} else {
				showFormError((data && data.status ? data.status : 'Save failed') + (data && data.message ? ': ' + data.message : '.'));
			}
		},
		error: function(){ showFormError(t('ui.js.couldNotSaveTaxSettings')); }
	});
}

// â”€â”€â”€ Multi-rate tax: tax-code (tax-class) master CRUD (owner) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
function loadTaxCodesAdmin(){
	$.get(serverContext + 'catalogTaxCodes', function(resp){
		var codes = Array.isArray(resp) ? resp : (typeof resp === 'string' ? (JSON.parse(resp) || []) : []);
		var $tb = $('#taxCodeTable tbody').empty();
		if(!codes.length){ $tb.append('<tr><td colspan="4" class="text-muted">No tax codes yet — add one below.</td></tr>'); return; }
		codes.forEach(function(c){
			$tb.append('<tr>'
				+ '<td>'+escHtml(c.name||'')+(c.active===false?' <span class="label label-default">inactive</span>':'')+'</td>'
				+ '<td class="text-right">'+Number(c.rate||0)+'</td>'
				+ '<td>'+(c.isDefault?'<span class="label label-info">default</span>':'')+'</td>'
				+ '<td><button type="button" class="btn btn-xs btn-default" onclick="editTaxCode('+c.id+')">Edit</button> '
				+ '<button type="button" class="btn btn-xs btn-danger" onclick="deleteTaxCode('+c.id+')">Delete</button></td>'
				+ '</tr>');
		});
		window._taxCodes = codes;   // cache for editTaxCode
	}, 'json').fail(function(){ $('#taxCodeTable tbody').html('<tr><td colspan="4" class="text-danger">Could not load tax codes (is catalog-service up?).</td></tr>'); });
}
function resetTaxCodeForm(){
	$('#tcId').val(''); $('#tcName').val(''); $('#tcRate').val(''); $('#tcDefault').prop('checked', false);
	$('#tcSaveLabel').text('Add code');
}
function editTaxCode(id){
	var c = (window._taxCodes||[]).filter(function(x){ return x.id===id; })[0];
	if(!c) return;
	$('#tcId').val(c.id); $('#tcName').val(c.name||''); $('#tcRate').val(c.rate!=null?c.rate:'');
	$('#tcDefault').prop('checked', c.isDefault===true);
	$('#tcSaveLabel').text('Save code');
}
function saveTaxCode(){
	var name = $('#tcName').val().trim();
	if(!name){ alert(t('ui.js.enterATaxCodeName')); return; }
	var body = { name:name, rate:Number($('#tcRate').val()||0), isDefault:$('#tcDefault').is(':checked') };
	var id = $('#tcId').val(); if(id) body.id = Number(id);
	$.ajax({ type:'POST', url:serverContext+'saveTaxCode', contentType:'application/json', dataType:'json', data:JSON.stringify(body),
		success:function(resp){
			if(resp && resp.success===false){ alert((resp.message)||'Could not save the tax code.'); return; }
			resetTaxCodeForm(); loadTaxCodesAdmin();
		},
		error:function(){ alert(t('ui.js.couldNotSaveTheTaxCodeAdmin')); }
	});
}
function deleteTaxCode(id){
	uiConfirm({
		title: t('ui.js.deleteThisTaxCode'),
		message: t('ui.js.productsUsingItFallBackToTheir'),
		confirmText: t('ui.js.deleteTaxCode'),
		tone: 'danger'
	}).then(function(ok){
		if(!ok) return;
		$.ajax({ type:'POST', url:serverContext+'deleteTaxCode', contentType:'application/json', dataType:'json', data:JSON.stringify({ id:id }),
			success:function(){ loadTaxCodesAdmin(); },
			error:function(){ uiAlert({ title:t('ui.js.deleteFailed'), message:t('ui.js.couldNotDeleteTheTaxCode'), tone:'danger' }); }
		});
	});
}

// â”€â”€â”€ B2B-P2-UI (#10): Price Rules (owner) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// The rule engine and its CRUD API shipped in Phase 2; this screen is what makes them usable without an
// API client. Same show/load/inline-editor shape as the tax-code master above.
//
// THE ONE THING THIS SCREEN MUST GET RIGHT: rules never stack — exactly one wins per line. An owner who
// adds a second, overlapping rule and sees no effect will conclude the system is broken. So the table is
// ordered by the resolver's OWN precedence and says outright which rule is being overridden.
//
// The order is mirrored from PriceResolver.bestRule(): specificity DESC, then priority DESC, then id ASC.
// Mirrored rather than re-invented — if the resolver's order ever changes, this comment is the pointer to
// the one other place that must change with it.

/** specificity() from commerce-pricing PriceRule: CUSTOMER +2, PRODUCT +1. Higher is more specific. */
function priceRuleSpecificity(r){
	return ((r.scope === 'CUSTOMER') ? 2 : 0) + ((r.target === 'PRODUCT') ? 1 : 0);
}

/** Two rules collide only when they target the SAME buyer and the SAME item. */
function priceRuleKey(r){
	var who  = (r.scope === 'CUSTOMER') ? ('C' + r.customerId) : ('T' + (r.customerType || ''));
	var what = (r.target === 'PRODUCT') ? ('P' + r.productId)  : ('G' + r.categoryId);
	return who + '|' + what;
}

/** LIVE / SCHEDULED / EXPIRED / INACTIVE — only a LIVE rule can ever price a line. */
function priceRuleState(r){
	if (r.active === false) return 'INACTIVE';
	var today = new Date(); today.setHours(0,0,0,0);
	if (r.startsOn && new Date(r.startsOn + 'T00:00:00') > today) return 'SCHEDULED';
	if (r.endsOn   && new Date(r.endsOn   + 'T00:00:00') < today) return 'EXPIRED';
	return 'LIVE';
}

/*
 * -- Task #19: the INVOICES behind the Sale Detail Report ---------------------------------------------
 *
 * The report already had Print and PDF buttons, but those are DataTables exports: they dump the TABLE -- a
 * grid of rows. What was missing is the actual invoice DOCUMENT for the sales listed, which is a different
 * artifact from a different pipeline (receipt.js / document-pdf.js).
 *
 * The unit is an INVOICE, not a report row. The report lists sale LINES, so a three-line sale appears three
 * times; printing per row would hand the operator the same invoice three times over. The distinct set is
 * taken from the invoice column of the rows CURRENTLY RENDERED -- what the operator filtered to and can
 * see -- so these buttons cannot produce something they were not shown.
 */
function srVisibleInvoiceNos(){
	var seen = {}, out = [];
	$('#tableSellReport tbody .sr-inv').each(function(){
		var no = $.trim($(this).text());
		if (!no || no === '—' || seen[no]) return;
		seen[no] = true;
		out.push(no);
	});
	return out;
}

$(document).on('click', '#srPrintInvoices', function(){
	var nos = srVisibleInvoiceNos();
	if (!nos.length) { if (window.showFormError) showFormError(t('ui.js.nothingToPrint')); return; }
	uiConfirm({
		title: t('ui.js.printInvoices'),
		message: t('ui.js.printNDocuments', nos.length),
		confirmText: t('ui.js.print')
	}).then(function(ok){
		if (ok) printInvoices(nos);
	});
});

/*
 * Download: ONE PDF per invoice, deliberately. A merged PDF would need pdfmake concatenation, and the
 * per-invoice file is what a manager actually attaches to an email or files against an account.
 *
 * downloadInvoicePdf() already existed in document-pdf.js with NO caller anywhere -- this is the first
 * screen to reach it. Confirmed above a small threshold, because N files is N browser downloads and a
 * manager who meant to print should not receive two hundred of them.
 */
var SR_DOWNLOAD_WARN_AT = 10;

$(document).on('click', '#srDownloadInvoices', function(){
	var nos = srVisibleInvoiceNos();
	if (!nos.length) { if (window.showFormError) showFormError(t('ui.js.nothingToPrint')); return; }
	var go = function(){
		// Sequential, not all at once: browsers throttle or silently drop a burst of simultaneous downloads,
		// which would look like the feature half-working.
		nos.forEach(function(no, i){
			setTimeout(function(){ downloadInvoicePdf(no); }, i * 400);
		});
	};
	if (nos.length < SR_DOWNLOAD_WARN_AT) { go(); return; }
	uiConfirm({
		title: t('ui.js.downloadInvoices'),
		message: t('ui.js.downloadNFiles', nos.length),
		confirmText: t('ui.js.download')
	}).then(function(ok){ if (ok) go(); });
});


/*
 * ── Task #21: the returns register ───────────────────────────────────────────────────────────────────
 *
 * ONE screen for both sides. A credit note and a debit note differ in exactly three things — the endpoint,
 * the party column and the document's name — while the table, the empty state, the reprint action and the
 * scoping are identical. Two screens would be two copies of "render a list of returns", and the second would
 * drift from the first the moment either changed.
 *
 * Why this screen had to exist at all: before it, a note could only be printed in the seconds after the
 * return was taken. Dismiss that prompt and the document was unreachable — `getSaleReturns` had been sitting
 * in business-service and the monolith proxy since SF-11 with nothing in the UI ever calling it.
 *
 * Both endpoints answer with the SAME ReturnDocumentDTO the printed note uses, so this screen reads one set
 * of field names for either mode — documentNo / referenceNo / partyName / totalAmount. That is why the config
 * below carries only what genuinely differs: the source, the title, and what the party column is CALLED.
 */
/*
 * #24 — the party is now CONFIGURED per mode, not branched on.
 *
 * `partyWrap` is the slot to show, `partyDD` the control to read, `partyParam` the query key it becomes.
 * Adding a third register would be a third entry here rather than a third `if`. The user ruled ONE SCREEN
 * (Part 5 §5.7): the two registers differ in configuration, not behaviour, and this is where that difference
 * is allowed to live.
 */
var RETURN_MODES = {
	credit: {
		url: 'getSaleReturns', title: 'ui.creditNotes', party: 'ui.customer',
		partyWrap: '#returnsPartyCustomer', partyDD: '#returnsCustomerDD', partyParam: 'customerId'
	},
	debit: {
		url: 'getPurchaseReturns', title: 'ui.debitNotes', party: 'ui.supplier',
		partyWrap: '#returnsPartyVender', partyDD: '#returnsVenderDD', partyParam: 'venderId'
	}
};

/** Every filter control on the register, so "clear them all" is one list rather than four selectors. */
var RETURN_FILTER_FIELDS = ['#returnsCustomerDD', '#returnsVenderDD', '#returnsFrom', '#returnsTo',
	'#returnsProductDD'];

/**
 * Reset every filter.
 *
 * ⚠ Called on EVERY mode switch. A supplier filter left applied after switching to credit notes would narrow
 * customer returns by a supplier id, match nothing, and read as "this shop has never had a sale return" —
 * a filter leak that looks like missing data, which is the worst way for one to fail.
 */
function clearReturnFilters(){
	RETURN_FILTER_FIELDS.forEach(function(sel){
		var $el = $(sel);
		if (!$el.length) return;
		$el.val('');
		if ($el.is('select')) refreshSearchableSelect($el);
	});
}

function showReturns(mode){
	var cfg = RETURN_MODES[mode] || RETURN_MODES.credit;
	window.returnsMode = (RETURN_MODES[mode] ? mode : 'credit');
	$('.formDiv').hide();
	$('#ReturnsDiv').show();
	$('#returnsTitle').text(t(cfg.title));
	$('#returnsPartyHead').text(t(cfg.party));
	$('#tableReturns tbody').empty();

	/*
	 * #24 — the bar is ALWAYS shown; only the party slot changes.
	 *
	 * Task #16 hid the whole bar on the credit side because the only filter in it was a supplier one, and a
	 * supplier picker over customer returns is a control that can never match anything. That reasoning was
	 * right about the PICKER and wrong about the BAR — it took Print all down with it.
	 */
	$('#returnsFilterBar').show();
	clearReturnFilters();

	var other = RETURN_MODES[window.returnsMode === 'credit' ? 'debit' : 'credit'];
	$(other.partyWrap).hide();
	$(cfg.partyWrap).show();

	if (window.returnsMode === 'debit') loadReturnsVenders();
	else loadReturnsCustomers();
	loadReturnsProducts();

	loadReturns();
}

/*
 * The supplier list for the filter.
 *
 * Reuses the SAME lean read the purchase form's vendor picker uses rather than adding a second vendor
 * endpoint — one definition of "which suppliers does this tenant have". Background, so the register is
 * usable while the list fills.
 */
function loadReturnsVenders(){
	var $dd = $('#returnsVenderDD');
	if ($dd.data('loaded')) return;             // once per session; the list changes rarely
	$dd.empty().append($('<option>').val('').text(t('ui.js.allSuppliers')));
	// `getUserVenders` (plural) answers with <option> MARKUP, not JSON — the same response loadUserVenders
	// appends. bgGet, not bgJson, because forcing a JSON parse on markup yields nothing and fails silently.
	bgGet(serverContext + 'getUserVenders', function(html){
		/*
		 * Drop the endpoint's OWN placeholder before appending.
		 *
		 * getUserVenders emits "<option value=''>Nothing Selected</option>" as its first row — the purchase
		 * form wants that, but this picker already has "All suppliers" above it, so appending the markup raw
		 * gave the filter TWO blank options, the second of them hardcoded English in a six-locale product.
		 *
		 * Filtered on "has a value" rather than on the placeholder's text, so it stays correct if that
		 * wording is ever translated or changed.
		 */
		$dd.append($('<select>').html(html).find('option').filter(function(){ return $(this).val(); }));
		$dd.data('loaded', true);
		// `global:false` skips the ajaxComplete hook that redraws pickers — refresh explicitly. See
		// refreshSearchableSelect for the full trap.
		refreshSearchableSelect($dd);
	});
}

// Re-read on change. Filtering in SQL, not by hiding rows: a distributor's register is not a page.
$(document).on('change', '#returnsVenderDD', function(){ loadReturns(); });

/*
 * Print every note currently listed, as ONE job.
 *
 * Deliberately prints what is ON SCREEN rather than re-querying: the operator filtered to a supplier and can
 * see exactly what they are about to hand over, so the button cannot print something they were not shown.
 */
$(document).on('click', '#returnsPrintAll', function(){
	var notes = $('#tableReturns tbody .rtn-print').map(function(){ return $(this).data('note'); }).get();
	if (!notes.length) { if (window.showFormError) showFormError(t('ui.js.nothingToPrint')); return; }
	uiConfirm({
		title: t('ui.js.printAll'),
		message: t('ui.js.printNDocuments', notes.length),
		confirmText: t('ui.js.print')
	}).then(function(ok){
		if (ok) printReturnDocuments(window.returnsMode || 'credit', notes);
	});
});

/*
 * The customer list for the credit-side filter (#24).
 *
 * `customerOptions`, the LEAN read the till already uses — not `getUserCustomer`, which returns the full
 * record for every customer a distributor has. A filter picker needs an id and a name.
 */
function loadReturnsCustomers(){
	var $dd = $('#returnsCustomerDD');
	if (!$dd.length || $dd.data('loaded')) return;
	bgJson(serverContext + 'customerOptions', function(resp){
		var rows = (resp && resp.collection) || [];
		$dd.empty().append($('<option>').val('').text(t('ui.js.allCustomers')));
		rows.forEach(function(c){
			$dd.append($('<option>').val(c.customerId).text(c.name || ('#' + c.customerId)));
		});
		$dd.data('loaded', true);
		// bgJson sets global:false, which skips the ajaxComplete hook that redraws pickers — refresh
		// explicitly. See refreshSearchableSelect for the full trap.
		refreshSearchableSelect($dd);
	});
}

/** The product list, from the cached lean projection both registers can share (PERF-8). */
function loadReturnsProducts(){
	var $dd = $('#returnsProductDD');
	if (!$dd.length || $dd.data('loaded')) return;
	ProductPicker.load(function(list){
		$dd.empty().append($('<option>').val('').text(t('ui.js.allProducts')))
			.append($('<select>').html(ProductPicker.optionsHtml(list))
				.find('option').filter(function(){ return $(this).val(); }));
		$dd.data('loaded', true);
		refreshSearchableSelect($dd);
	});
}

/** Re-read on any filter change. In SQL, not by hiding rows — a distributor's register is not a page. */
$(document).on('change', '#returnsCustomerDD, #returnsProductDD, #returnsFrom, #returnsTo', function(){
	loadReturns();
});

$(document).on('click', '#returnsClearFilters', function(){
	clearReturnFilters();
	loadReturns();
});

function loadReturns(){
	var mode = window.returnsMode || 'credit';
	var cfg = RETURN_MODES[mode];
	// A register is background population of a screen, not an action the operator is waiting on — same rule
	// tier-1b applied to the pickers, so it must not raise the blocking overlay.
	// Task #16: the supplier filter is applied in SQL, so it travels with the read.
	/*
	 * #24 — the filters travel with the read, applied server-side.
	 *
	 * ⚠ A BLANK filter is OMITTED, never sent as `customerId=`. An empty value fails Spring's Long binding
	 * with a 400, so clearing a filter would break the register rather than widen it. The monolith proxy
	 * drops blanks too (AppUtil.passThroughQuery) — belt and braces, because either side alone leaves the
	 * other free to reintroduce it.
	 *
	 * Only the CURRENT mode's party is read, so a stale value in the other mode's picker cannot travel.
	 */
	var params = [];
	var add = function(key, val){
		if (val !== undefined && val !== null && String(val).trim() !== '')
			params.push(key + '=' + encodeURIComponent(String(val).trim()));
	};
	add(cfg.partyParam, $(cfg.partyDD).val());
	add('productId', $('#returnsProductDD').val());
	add('from', $('#returnsFrom').val());
	add('to', $('#returnsTo').val());

	bgJson(serverContext + cfg.url + (params.length ? '?' + params.join('&') : ''), function(resp){
		renderReturns(mode, (resp && resp.collection) || []);
	}).fail(function(){
		$('#tableReturns tbody').html('<tr><td colspan="8" class="text-danger">'
			+ escHtml(t('ui.js.couldNotLoadReturns')) + '</td></tr>');
	});
}

function renderReturns(mode, rows){
	var $tb = $('#tableReturns tbody').empty();
	if (!rows.length){
		$tb.append('<tr><td colspan="8" class="text-muted">' + escHtml(t('ui.js.noReturnsYet')) + '</td></tr>');
		return;
	}
	rows.forEach(function(r){
		var noteNo = r.documentNo;
		var amt = r.totalAmount;
		var qty = (r.lines && r.lines.length) ? r.lines[0].quantity : null;
		/*
		 * A row with NO note number predates the note series and has no printable document — the same reason
		 * the counter prompt stays silent for one. It still LISTS, because it is a real return and hiding it
		 * would misstate history; it simply offers no action, which is honest where a button that always
		 * fails is not.
		 */
		var action = noteNo
			? "<button type='button' class='btn btn-xs btn-default rtn-print' data-note='" + escHtml(noteNo)
				+ "'><span class='glyphicon glyphicon-print'></span> " + escHtml(t('ui.js.reprint')) + "</button>"
			: '';
		$tb.append('<tr>'
			+ '<td>' + escHtml(noteNo || '—') + '</td>'
			+ '<td>' + escHtml(r.dated ? String(r.dated).substring(0, 10) : '') + '</td>'
			// Resolved server-side in batch — the return row itself holds only ids.
			+ '<td>' + escHtml(r.partyName || '—') + '</td>'
			+ '<td>' + escHtml(r.referenceNo || '') + '</td>'
			+ '<td style="text-align:right">' + escHtml(qty != null ? String(qty) : '') + '</td>'
			+ '<td style="text-align:right">' + escHtml(amt != null ? Number(amt).toFixed(2) : '—') + '</td>'
			+ '<td>' + escHtml(r.reason || '') + '</td>'
			+ '<td>' + action + '</td>'
			+ '</tr>');
	});
}

// Reprint goes through the SAME function the counter prompt calls — one print path, and the reason task #15
// keyed the documents on the note number rather than a row id.
$(document).on('click', '.rtn-print', function(){
	printReturnDocument(window.returnsMode || 'credit', $(this).data('note'));
});

/*
 * ── #17 P1: bonus / free-goods schemes ──────────────────────────────────────────────────────────────
 *
 * The register for buy-and-get offers, in all three directions: what a SUPPLIER gives us, what we give a
 * named CUSTOMER, and what we give a customer TYPE. One engine, three scopes — the same shape price rules
 * already use, and resolved by the same specificity-and-priority idea.
 *
 * Ships WITH P1 on purpose. A scheme master with no screen would be the eighth capability in this codebase
 * that works at the API and cannot be reached by a person. See docs/slices/bonus-schemes.md.
 */
function showBonusSchemes(){
	$('.formDiv').hide();
	$('#BonusSchemeDiv').show();
	resetBonusSchemeForm();
	// Pickers BEFORE the list, for the same reason the price-rule screen loads them first: the table names a
	// supplier and a product by reading the pickers, so an unpopulated picker prints "#12" and only corrects
	// itself on the next save.
	loadBonusSchemeLookups().always(loadBonusSchemes);
}

/** Suppliers, customers and products for the pickers. Returns a promise of all three. */
function loadBonusSchemeLookups(){
	function fillSel($sel, rows, valueKey, labelKey, blank){
		$sel.empty().append($('<option>').val('').text(blank));
		(rows || []).forEach(function(r){
			if (r[valueKey] == null) return;
			$sel.append($('<option>').val(r[valueKey]).text(String(r[labelKey] == null ? r[valueKey] : r[labelKey])));
		});
	}
	return $.when(
		bgGet(serverContext + 'getUserVenders', function(html){
			// getUserVenders answers with <option> MARKUP and carries its own blank placeholder — drop it,
			// this picker supplies its own. Filtered on "has a value" so it survives a wording change.
			$('#bsVendorId').empty().append($('<option>').val('').text(t('ui.js.selectOne')))
				.append($('<select>').html(html).find('option').filter(function(){ return $(this).val(); }));
		}),
		bgJson(serverContext + 'customerOptions', function(resp){
			fillSel($('#bsCustomerId'), (resp && resp.collection) || [], 'customerId', 'name', t('ui.js.selectOne'));
		}),
		bgJson(serverContext + 'getUserProduct', function(resp){
			var rows = (resp && resp.collection) || [];
			fillSel($('#bsTriggerProductId'), rows, 'id', 'name', t('ui.js.selectOne'));
			// Blank on the reward means "the same product as the trigger" — the common case, and it must not
			// require picking the same thing twice.
			fillSel($('#bsRewardProductId'), rows, 'id', 'name', t('ui.js.sameProduct'));
		})
	);
}

/** Show only the party control the chosen scope can actually use. */
function onBonusSchemeScope(){
	var scope = $('#bsScope').val();
	$('#bsVendorId').toggle(scope === 'VENDOR');
	$('#bsCustomerId').toggle(scope === 'CUSTOMER');
	$('#bsCustomerType').toggle(scope === 'CUSTOMER_TYPE');
}

/**
 * Live entitlement preview — "30 paid earns 6 free".
 *
 * Asks the SERVER rather than repeating the arithmetic here. The same sum decides what goes on a delivery,
 * what a return claws back and what a report shows, so a second copy in the browser is a second answer
 * waiting to disagree with the first.
 */
function previewBonusScheme(){
	var paid = Number($('#bsPaidQuantity').val());
	var bonus = Number($('#bsBonusQuantity').val());
	if (!paid || !bonus){ $('#bsPreview').text(''); return; }
	var probe = paid * 3;   // a quantity that makes the ONE_TIME / REPEATING difference visible
	$.ajax({
		url: serverContext + 'bonusScheme/preview', type: 'POST', global: false,
		contentType: 'application/json', dataType: 'json',
		data: JSON.stringify({
			qualificationMode: $('#bsQualificationMode').val(),
			paidQuantity: paid, bonusQuantity: bonus, quantity: probe
		})
	}).done(function(res){
		var d = res && (res.data || res.object);
		if (!d) { $('#bsPreview').text(''); return; }
		$('#bsPreview').text(t('ui.js.previewEarns', probe, d.bonusQuantity));
	});
}

function resetBonusSchemeForm(){
	$('#bsId').val('');
	if ($('#BonusSchemeForm')[0]) $('#BonusSchemeForm')[0].reset();
	$('#bsPriority').val(100);
	$('#bsError').text('');
	$('#bsPreview').text('');
	$('#bsFormTitle').text(t('ui.addOffer'));
	$('#bsSaveLabel').text(t('ui.addOffer'));
	onBonusSchemeScope();
}

function saveBonusScheme(){
	var scope = $('#bsScope').val();
	var body = {
		code: $.trim($('#bsCode').val()),
		scope: scope,
		vendorId:     scope === 'VENDOR'        ? ($('#bsVendorId').val()   || null) : null,
		customerId:   scope === 'CUSTOMER'      ? ($('#bsCustomerId').val() || null) : null,
		customerType: scope === 'CUSTOMER_TYPE' ? ($('#bsCustomerType').val() || null) : null,
		triggerTarget: 'PRODUCT',
		triggerProductId: $('#bsTriggerProductId').val() || null,
		rewardProductId:  $('#bsRewardProductId').val()  || null,
		paidQuantity:  $('#bsPaidQuantity').val()  || null,
		bonusQuantity: $('#bsBonusQuantity').val() || null,
		bonusType: $('#bsBonusType').val(),
		qualificationMode: $('#bsQualificationMode').val(),
		startsOn: $('#bsStartsOn').val() || null,
		endsOn:   $('#bsEndsOn').val()   || null,
		status:   $('#bsStatus').val(),
		priority: Number($('#bsPriority').val()) || 0
	};
	var id = $('#bsId').val();
	$.ajax({
		url: serverContext + 'bonusScheme' + (id ? ('/' + id) : ''),
		type: id ? 'PUT' : 'POST',
		contentType: 'application/json', dataType: 'json', data: JSON.stringify(body)
	}).done(function(res){
		// The SERVER owns what a valid scheme is — a refusal arrives as success:false, NOT as an HTTP error,
		// so the envelope is what decides here. Re-validating in the browser would be a second rulebook.
		if (res && res.success === false){
			$('#bsError').text(res.message || t('ui.js.couldNotSaveOffer'));
			return;
		}
		resetBonusSchemeForm();
		loadBonusSchemes();
		if (typeof showSaleSuccess === 'function') showSaleSuccess(t('ui.js.offerSaved'));
	}).fail(function(){
		$('#bsError').text(t('ui.js.couldNotSaveOffer'));
	});
}

/** Load one scheme back into the form for editing. */
function editBonusScheme(btn){
	var r = $(btn).data('row');
	if (!r) return;
	$('#bsId').val(r.id);
	$('#bsCode').val(r.code);
	$('#bsScope').val(r.scope);
	onBonusSchemeScope();
	$('#bsVendorId').val(r.vendorId || '');
	$('#bsCustomerId').val(r.customerId || '');
	$('#bsCustomerType').val(r.customerType || 'WALK_IN');
	$('#bsTriggerProductId').val(r.triggerProductId || '');
	$('#bsRewardProductId').val(r.rewardProductId || '');
	$('#bsPaidQuantity').val(r.paidQuantity);
	$('#bsBonusQuantity').val(r.bonusQuantity);
	$('#bsBonusType').val(r.bonusType);
	$('#bsQualificationMode').val(r.qualificationMode);
	$('#bsStartsOn').val(r.startsOn || '');
	$('#bsEndsOn').val(r.endsOn || '');
	$('#bsStatus').val(r.status || 'ACTIVE');
	$('#bsPriority').val(r.priority);
	$('#bsFormTitle').text(t('ui.editOffer'));
	$('#bsSaveLabel').text(t('ui.editOffer'));
	previewBonusScheme();
	$('#bsCode').focus();
}

function deleteBonusScheme(btn){
	var r = $(btn).data('row');
	if (!r) return;
	uiConfirm({
		title: t('ui.js.deleteThisOffer'),
		message: r.code,
		confirmText: t('ui.js.delete'),
		tone: 'danger'
	}).then(function(ok){
		if (!ok) return;
		$.ajax({ url: serverContext + 'bonusScheme/' + r.id, type: 'DELETE' })
			.always(function(){ loadBonusSchemes(); });
	});
}

function loadBonusSchemes(){
	// Background: a register is screen population, not an action the operator is waiting on.
	bgJson(serverContext + 'bonusSchemes', function(resp){
		renderBonusSchemes((resp && (resp.data || resp.collection)) || []);
	}).fail(function(){
		$('#tableBonusScheme tbody').html('<tr><td colspan="7" class="text-danger">'
			+ escHtml(t('ui.js.couldNotLoadBonusSchemes')) + '</td></tr>');
	});
}

/** "10 + 1 free" — the offer as an operator states it, from the two quantities the scheme stores. */
function bonusOfferText(r){
	var paid = (r.paidQuantity != null) ? Number(r.paidQuantity) : '';
	var bon  = (r.bonusQuantity != null) ? Number(r.bonusQuantity) : '';
	var lead = ('REPEATING' === r.qualificationMode) ? t('ui.js.everyN') : t('ui.js.buyN');
	return lead + ' ' + paid + ' + ' + bon;
}

function renderBonusSchemes(rows){
	var $tb = $('#tableBonusScheme tbody').empty();
	if(!rows.length){
		$tb.append('<tr><td colspan="7" class="text-muted">' + escHtml(t('ui.js.noBonusSchemesYet')) + '</td></tr>');
		return;
	}
	rows.forEach(function(r){
		// The reward SKU is what a bare bonus QUANTITY could not express, so it is shown when it differs —
		// otherwise "buy a machine get a coffee pack" reads identically to "buy a machine get a machine".
		var reward = (r.rewardProductId != null && r.rewardProductId !== r.triggerProductId)
			? (' → #' + r.rewardProductId) : '';
		var valid = (r.startsOn || r.endsOn)
			? escHtml((r.startsOn || '') + ' – ' + (r.endsOn || ''))
			: escHtml(t('ui.js.always'));
		$tb.append('<tr>'
			+ '<td>' + escHtml(r.code || '') + '</td>'
			+ '<td>' + escHtml(r.scope || '') + '</td>'
			+ '<td>' + escHtml(r.triggerProductId != null ? ('#' + r.triggerProductId) : (r.triggerCategoryId != null ? ('cat #' + r.triggerCategoryId) : '')) + escHtml(reward) + '</td>'
			+ '<td>' + escHtml(bonusOfferText(r)) + '</td>'
			+ '<td>' + escHtml(r.bonusType || '') + '</td>'
			+ '<td>' + valid + '</td>'
			+ '<td>' + escHtml(r.status || '') + '</td>'
			+ '</tr>');
	});
}

function showPriceRules(){
	$('.formDiv').hide();
	$('#PriceRuleDiv').show();
	resetPriceRuleForm();
	// The table names a customer by reading the picker it was loaded into, so the pickers must be populated
	// BEFORE the rules render — otherwise the first paint shows "#12" instead of "Ali Traders" and only
	// corrects itself on the next save. Load, then render.
	loadPriceRuleLookups().always(loadPriceRules);
}

/** Customers, products and categories for the three pickers. Returns a promise of all three. */
function loadPriceRuleLookups(){
	function fill($sel, rows, valueKey, labelKey){
		$sel.empty().append($('<option>').val('').text(t('ui.js.selectOne')));
		(rows || []).forEach(function(r){
			if (r[valueKey] == null) return;
			$sel.append($('<option>').val(r[valueKey]).text(String(r[labelKey] == null ? r[valueKey] : r[labelKey])));
		});
	}
	return $.when(
		// Report filters need id + name only — the lean read more than covers them.
		$.get(serverContext + 'customerOptions', function(resp){
			fill($('#prCustomerId'), (resp && resp.collection) || [], 'customerId', 'name');
		}, 'json'),
		$.get(serverContext + 'getUserProduct', function(resp){
			fill($('#prProductId'), (resp && resp.collection) || [], 'id', 'name');
		}, 'json'),
		$.get(serverContext + 'getUserCategories', function(resp){
			fill($('#prCategoryId'), (resp && resp.categories) || [], 'id', 'name');
		}, 'json')
	);
}

function loadPriceRules(){
	$.get(serverContext + 'priceRules', function(resp){
		var rules = Array.isArray(resp) ? resp : (typeof resp === 'string' ? (JSON.parse(resp) || []) : []);
		window._priceRules = rules;                       // cache for editPriceRule
		renderPriceRules(rules);
	}, 'json').fail(function(){
		$('#tablePriceRule tbody').html('<tr><td colspan="8" class="text-danger">'
			+ escHtml(t('ui.js.couldNotLoadPriceRules')) + '</td></tr>');
	});
}

function renderPriceRules(rules){
	var $tb = $('#tablePriceRule tbody').empty();
	if (!rules.length){
		$tb.append('<tr><td colspan="8" class="text-muted">' + escHtml(t('ui.js.noPriceRulesYet')) + '</td></tr>');
		return;
	}

	// Resolver order: specificity DESC, priority DESC, id ASC.
	var sorted = rules.slice().sort(function(a, b){
		return (priceRuleSpecificity(b) - priceRuleSpecificity(a))
			|| ((b.priority || 0) - (a.priority || 0))
			|| ((a.id || 0) - (b.id || 0));
	});

	// Which rule actually wins each collision. Only LIVE rules compete — an inactive or expired rule is not
	// "overridden", it simply cannot apply. This flags EXACT collisions only: whether a customer×product rule
	// shadows a type×category one depends on the customer and product on the line, so it cannot be decided
	// for the table as a whole without inventing an answer.
	var winner = {};
	sorted.forEach(function(r){
		if (priceRuleState(r) !== 'LIVE') return;
		var k = priceRuleKey(r);
		if (winner[k] === undefined) winner[k] = r.id;     // first in resolver order = the one that applies
	});

	var SPEC_LABEL = {
		3: t('ui.js.customerProduct'),
		2: t('ui.js.customerCategory'),
		1: t('ui.js.typeProduct'),
		0: t('ui.js.typeCategory')
	};
	var STATE_LABEL = {
		LIVE:      '<span class="label label-success">' + escHtml(t('ui.js.live')) + '</span>',
		SCHEDULED: '<span class="label label-info">'    + escHtml(t('ui.js.scheduled')) + '</span>',
		EXPIRED:   '<span class="label label-default">' + escHtml(t('ui.js.expired')) + '</span>',
		INACTIVE:  '<span class="label label-default">' + escHtml(t('ui.js.inactive')) + '</span>'
	};

	sorted.forEach(function(r){
		var spec  = priceRuleSpecificity(r);
		var state = priceRuleState(r);
		var who   = (r.scope === 'CUSTOMER')
			? priceRuleName('#prCustomerId', r.customerId)
			: customerTypeLabel(r.customerType);
		var what  = (r.target === 'PRODUCT')
			? (r.targetName || priceRuleName('#prProductId', r.productId))
			: (r.targetName || priceRuleName('#prCategoryId', r.categoryId));
		var price = (r.mode === 'PERCENT')
			? ('−' + Number(r.value || 0) + '% ' + t('ui.js.offCatalog'))
			: Number(r.value || 0).toFixed(2);
		var valid = (r.startsOn || r.endsOn)
			? (escHtml(r.startsOn || '…') + ' â†’ ' + escHtml(r.endsOn || '…'))
			: '<span class="text-muted">' + escHtml(t('ui.js.always')) + '</span>';

		var status = STATE_LABEL[state];
		var beaten = (state === 'LIVE' && winner[priceRuleKey(r)] !== r.id);
		if (beaten){
			// The whole point of the screen: say WHY a rule the owner created is doing nothing.
			status += ' <span class="label label-warning" title="' + escHtml(t('ui.js.overriddenHelp')) + '">'
				+ escHtml(t('ui.js.overridden')) + ' #' + escHtml(String(winner[priceRuleKey(r)])) + '</span>';
		}

		$tb.append('<tr data-rule-id="' + escHtml(String(r.id)) + '"' + (beaten ? ' class="warning"' : '') + '>'
			+ '<td><span class="text-muted">' + (4 - spec) + '.</span> ' + escHtml(SPEC_LABEL[spec] || '') + '</td>'
			+ '<td>' + escHtml(who) + '</td>'
			+ '<td>' + escHtml(what) + '</td>'
			+ '<td class="text-right">' + escHtml(price) + '</td>'
			+ '<td>' + valid + '</td>'
			+ '<td class="text-right">' + escHtml(String(r.priority || 0)) + '</td>'
			+ '<td>' + status + '</td>'
			+ '<td><button type="button" class="btn btn-xs btn-default" onclick="editPriceRule(' + r.id + ')">'
			+ escHtml(t('ui.js.edit')) + '</button> '
			+ '<button type="button" class="btn btn-xs btn-danger" onclick="deletePriceRule(' + r.id + ')">'
			+ escHtml(t('ui.js.delete')) + '</button></td>'
			+ '</tr>');
	});
}

/** The label already loaded into a picker — avoids a second round-trip just to name an id. */
function priceRuleName(selector, id){
	if (id == null) return '';
	var o = $(selector + ' option[value="' + id + '"]');
	return o.length ? o.text() : ('#' + id);
}

function onPriceRuleScope(){
	var byCustomer = $('#prScope').val() === 'CUSTOMER';
	$('#prCustomerId').toggle(byCustomer);
	$('#prCustomerType').toggle(!byCustomer);
}

function onPriceRuleTarget(){
	var byProduct = $('#prTarget').val() === 'PRODUCT';
	$('#prProductId').toggle(byProduct);
	$('#prCategoryId').toggle(!byProduct);
}

function onPriceRuleMode(){
	$('#prValueHint').text($('#prMode').val() === 'PERCENT'
		? t('ui.js.percentHint')
		: t('ui.js.fixedHint'));
}

function resetPriceRuleForm(){
	$('#prId').val('');
	$('#prScope').val('CUSTOMER');
	$('#prCustomerId').val('');
	$('#prCustomerType').val('RETAILER');
	$('#prTarget').val('PRODUCT');
	$('#prProductId').val('');
	$('#prCategoryId').val('');
	$('#prMode').val('FIXED');
	$('#prValue').val('');
	$('#prStartsOn').val(''); $('#prStartsOnTemp').val('');
	$('#prEndsOn').val('');   $('#prEndsOnTemp').val('');
	$('#prPriority').val(0);
	$('#prActive').prop('checked', true);
	$('#prError').text('');
	$('#prSaveLabel').text(t('ui.js.addRule'));
	$('#prFormTitle').text(t('ui.js.addRule'));
	onPriceRuleScope(); onPriceRuleTarget(); onPriceRuleMode();
}

function editPriceRule(id){
	var r = (window._priceRules || []).filter(function(x){ return x.id === id; })[0];
	if (!r) return;
	resetPriceRuleForm();
	$('#prId').val(r.id);
	$('#prScope').val(r.scope || 'CUSTOMER');
	$('#prCustomerId').val(r.customerId != null ? r.customerId : '');
	$('#prCustomerType').val(r.customerType || 'RETAILER');
	$('#prTarget').val(r.target || 'PRODUCT');
	$('#prProductId').val(r.productId != null ? r.productId : '');
	$('#prCategoryId').val(r.categoryId != null ? r.categoryId : '');
	$('#prMode').val(r.mode || 'FIXED');
	$('#prValue').val(r.value != null ? r.value : '');
	setPriceRuleDate('#prStartsOn', r.startsOn);
	setPriceRuleDate('#prEndsOn', r.endsOn);
	$('#prPriority').val(r.priority || 0);
	$('#prActive').prop('checked', r.active !== false);
	$('#prSaveLabel').text(t('ui.js.saveRule'));
	$('#prFormTitle').text(t('ui.js.editRule'));
	onPriceRuleScope(); onPriceRuleTarget(); onPriceRuleMode();
	$('#prFormTitle')[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
}

/** ISO into the hidden field the API reads, dd-MM-yyyy into the visible box the picker owns. */
function setPriceRuleDate(hiddenId, iso){
	$(hiddenId).val(iso || '');
	var visible = $(hiddenId + 'Temp');
	if (!iso){ visible.val(''); return; }
	var p = String(iso).split('-');
	visible.val(p.length === 3 ? (p[2] + '-' + p[1] + '-' + p[0]) : iso);
}

function savePriceRule(){
	var scope  = $('#prScope').val();
	var target = $('#prTarget').val();
	var mode   = $('#prMode').val();
	var value  = $('#prValue').val();
	$('#prError').text('');

	// Client-side checks are for a fast answer only — commerce-pricing remains the authority and re-checks
	// everything server-side. Nothing here is a rule this screen owns.
	if (scope === 'CUSTOMER' && !$('#prCustomerId').val()){ $('#prError').text(t('ui.js.pickACustomer')); return; }
	if (target === 'PRODUCT'  && !$('#prProductId').val()){ $('#prError').text(t('ui.js.pickAProduct'));  return; }
	if (target === 'CATEGORY' && !$('#prCategoryId').val()){ $('#prError').text(t('ui.js.pickACategory')); return; }
	if (value === '' || isNaN(Number(value))){ $('#prError').text(t('ui.js.enterAValue')); return; }
	if (mode === 'PERCENT' && (Number(value) < 0 || Number(value) > 100)){
		$('#prError').text(t('ui.js.percentRange')); return;
	}
	var from = $('#prStartsOn').val(), to = $('#prEndsOn').val();
	if (from && to && from > to){ $('#prError').text(t('ui.js.datesBackwards')); return; }

	var body = {
		scope: scope,
		customerId:   scope === 'CUSTOMER' ? Number($('#prCustomerId').val()) : null,
		customerType: scope === 'TYPE'     ? $('#prCustomerType').val()       : null,
		target: target,
		productId:  target === 'PRODUCT'  ? Number($('#prProductId').val())  : null,
		categoryId: target === 'CATEGORY' ? Number($('#prCategoryId').val()) : null,
		mode: mode,
		value: Number(value),
		priority: Number($('#prPriority').val() || 0),
		active: $('#prActive').is(':checked'),
		startsOn: from || null,
		endsOn: to || null
	};
	var id = $('#prId').val();
	if (id) body.id = Number(id);

	$.ajax({
		type: 'POST', url: serverContext + 'savePriceRule',
		contentType: 'application/json', dataType: 'json', data: JSON.stringify(body),
		success: function(resp){
			if (resp && resp.success === false){
				$('#prError').text((resp.message) || t('ui.js.couldNotSavePriceRule'));
				return;
			}
			resetPriceRuleForm();
			loadPriceRules();
			showSaleSuccess(t('ui.js.priceRuleSaved'));
		},
		error: function(){ $('#prError').text(t('ui.js.couldNotSavePriceRule')); }
	});
}

function deletePriceRule(id){
	uiConfirm({
		title: t('ui.js.deleteThisPriceRule'),
		message: t('ui.js.deletePriceRuleHelp'),
		confirmText: t('ui.js.delete'),
		tone: 'danger'
	}).then(function(ok){
		if (!ok) return;
		$.ajax({
			type: 'POST', url: serverContext + 'deletePriceRule',
			contentType: 'application/json', dataType: 'json', data: JSON.stringify({ id: id }),
			success: function(){ resetPriceRuleForm(); loadPriceRules(); },
			error: function(){
				uiAlert({ title: t('ui.js.deleteFailed'), message: t('ui.js.couldNotDeletePriceRule'), tone: 'danger' });
			}
		});
	});
}

// â”€â”€â”€ Multi-location: Stores (owner) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
function showStores(){
	$('.formDiv').hide();
	$('#StoresDiv').show();
	$('#storeMsg').hide();
	loadStores();
}
function storeMsg(msg, isErr){
	$('#storeMsg').removeClass('alert-success alert-danger')
		.addClass(isErr ? 'alert-danger' : 'alert-success').html(escHtml(msg)).show();
}
function loadStores(){
	$.get(serverContext + 'getStores', function(resp){
		var rows = (resp && (resp.collection || resp.data)) || [];
		var $tb = $('#tableStores tbody').empty();
		if(!rows.length){ $tb.append('<tr><td colspan="5" class="text-center">No stores yet — add your first store above.</td></tr>'); return; }
		rows.forEach(function(s){
			$tb.append('<tr><td>'+escHtml(s.name||'')+'</td><td>'+escHtml(s.code||'')+'</td><td>'+escHtml(s.address||'')
				+'</td><td>'+escHtml(s.phone||'')+'</td><td>'+escHtml(s.status||'')+'</td></tr>');
		});
	}, 'json').fail(function(){ $('#tableStores tbody').html('<tr><td colspan="5" class="text-center">Could not load stores.</td></tr>'); });
}
function saveStore(){
	var body = { name:($('#storeName').val()||'').trim(), code:($('#storeCode').val()||'').trim(),
		address:($('#storeAddress').val()||'').trim(), phone:($('#storePhone').val()||'').trim() };
	if(!body.name){ storeMsg('Please enter a store name.', true); return; }
	$.ajax({ type:'POST', url:serverContext+'addStore', contentType:'application/json', data:JSON.stringify(body), dataType:'json',
		success:function(resp){
			if(resp && (resp.status==='SUCCESS' || resp.object)){
				storeMsg('Store created. Pick it in the store switcher to sell from it.', false);
				$('#storeName,#storeCode,#storeAddress,#storePhone').val('');
				loadStores();
				loadMyStores();   // a second store makes the switcher relevant — show it without a re-login
			} else { storeMsg(apiMessage(resp, 'Could not create the store.'), true); }
		},
		error:function(){ storeMsg('Could not create the store.', true); }
	});
}
// â”€â”€ P5b: active-store switcher â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// The active store is what new sales/purchases/shifts get stamped with, and it lives in the JWT — so
// switching means asking auth-service for a fresh token, then reloading so every section refetches
// through the new scope. Hidden entirely for a single-store business (nothing to switch between).
function loadMyStores(){
	// Tier 1: reference data for the store picker — populate in the background.
	 bgGet(serverContext + 'getMyStores', function(resp){
		var rows = (resp && (resp.collection || resp.data)) || [];
		if(rows.length < 2){ $('#storeSwitcherWrap').hide(); return; }
		var $s = $('#storeSwitcher').empty();
		var hasActive = rows.some(function(st){ return st.active; });
		if(!hasActive){ $s.append($('<option>').val('').text('Select a store…')); }
		rows.forEach(function(st){
			var $o = $('<option>').val(st.id).text(st.name + (st.code ? (' (' + st.code + ')') : ''));
			if(st.active){ $o.prop('selected', true); }
			$s.append($o);
		});
		$('#storeSwitcherWrap').show();
	}, 'json').fail(function(){ $('#storeSwitcherWrap').hide(); });
}

function switchStore(){
	var storeId = $('#storeSwitcher').val();
	if(!storeId){ return; }
	$.ajax({ type:'POST', url:serverContext+'switchStore', contentType:'application/json',
		data:JSON.stringify({ storeId: Number(storeId) }), dataType:'json',
		success:function(res){
			// The session token now carries the new active store — reload so every list refetches under it.
			if(res && res.status === 'SUCCESS'){ window.location.reload(); }
			else { alert(apiMessage(res, 'Could not switch store.')); loadMyStores(); }
		},
		error:function(){ alert(t('ui.js.couldNotSwitchStore')); loadMyStores(); }
	});
}

// Populate the Manage-Users store-assignment multi-select.
function CIT(data){
	var q=ZERO,sr=ZERO,dis=ZERO,t=ZERO;
	data.forEach(function(d){
		q=d.quantity*ONE+q;
		sr=d.stock.bsellRate*ONE+sr;
		dis=d.stock.bsellDiscount*ONE+dis;
		t=d.totalAmount*ONE+t;
	});
	$("#itq").text(q);
	$("#itp").text(sr);
	$("#itd").text(dis);
	$("#itt").text(t-dis);
	$("#action").text("");
}
function resetCart(){
	data = [];
	tablesi.clear().draw();
	$("#sellRec,#sellCh,#sellDueThis,#sellPrevDue,#sellNewTotalDue").val('');
	window.selectedCustomerDue = null;
	$("#sellAccountRow").hide();
	onCustomerModeChange('select');
	$('input[name="customerInputMode"][value="select"]').prop('checked', true);
	exitSellEditMode();   // a save (incl. updateSell) ends the edit: clear flag/banner, restore button
	updateReadOnly(false);
}
// Product screen: build one "last rate" cell — the money to 2dp with the stamping date as the tooltip, or "—"
// when this product has never been purchased (nothing stamped yet; a blank would read as a zero rate). Shared by
// the last-purchase and last-sale columns so the two never drift apart in formatting or in what "no data" means.
function lastRateCell(rate, stampedAt, label){
	if (rate == null || rate === '' || isNaN(Number(rate))) return "<div class=prod-lastrate>—</div>";
	var day = stampedAt ? String(stampedAt).substring(0, 10) : '';   // ISO LocalDateTime â†’ yyyy-MM-dd
	// Numbers only + an escaped label â†’ XSS-safe.
	return "<div class=prod-lastrate title='" + escHtml(label + (day ? ' ' + day : '')) + "'>"
		+ Number(rate).toFixed(2) + "</div>";
}

/**
 * TIER-1c — the section's dropdowns, started as soon as the section opens.
 *
 * <p>These calls used to live inside the grid's AJAX success handler, which chained them behind the whole
 * record list: on the sale screen the customer picker could not BEGIN loading until every sale line the
 * tenant had ever recorded ("getUserSell?q=-1", uncapped) had finished downloading. Tier-1b made the picker
 * reads non-blocking, but a non-blocking read that has not been ISSUED yet buys nothing — the pickers were
 * still serialised behind the grid.
 *
 * <p>They belong to the SECTION, not to the grid, so they start with the section. The two now load in
 * parallel and the till is typeable in about picker time instead of grid time plus picker time.
 *
 * <p>The `pickerPreloadPending` flag this replaces existed only to fake "once per section open" from inside
 * a handler that also runs on every `datatable.ajax.reload()` — P6 rapid entry reloads the grid after every
 * saved line, which re-fetched the catalogue and rebuilt the pickers underneath the operator mid-entry.
 * Called from here that problem cannot arise: loadDataTable() IS "a section was opened", so the flag is
 * gone rather than left behind as a guard against something that can no longer happen.
 *
 * <p>Unchanged trade-off, restated because it still applies: a product or vendor created in ANOTHER section
 * does not appear in these pickers until this section is re-opened. That is already the documented behaviour
 * of the grid itself ("re-open the section to refresh from DB").
 */
function preloadSectionPickers(){
	var table = tableV.toLowerCase();
	if (getAll == "Vender") {
		loadUserCompanies(table);
	} else if (getAll == "Item") {
		loadUserCompanies(table);
		loadUserVenders(table);
	} else if (getAll == "Purchase") {
		loadUserItems(table);
		loadUserVenders(table);   // F1 (AP): populate the purchase form's Vendor select
	} else if (getAll == "Sell") {
		loadUserItems(table);
		loadSellCustomers();
	}
}

function loadDataTable(){
	tableSellReport.clear().draw();
	edit = false;

	var table = tableV.toLowerCase();
	// Read current page length from the active DataTable for this entity (not hardcoded to Sell)
	var offset = $("select[name='table" + tableV + "_length']").val();
	if(!offset) offset = 5;

	if (datatable != null){
		datatable.destroy();
		datatable = null;
	}
	// The associated dropdowns belong to the SECTION, not to a grid refresh — so they start HERE, in
	// parallel with the grid, instead of waiting for it to finish. See preloadSectionPickers().
	preloadSectionPickers();
	datatable = $("#table" + tableV).DataTable({
		lengthMenu: [[5, 20, 50, 100, -1], ['5', '20', '50', '100', 'All']],
		"iDisplayLength": offset,
		"pageLength": (offset == -1 ? 100 : Number(offset)),
		"order": [[0, "desc"]],
		"autoWidth": true,
		// A voided sale/purchase is a finalized, read-only record — grey the row so it reads as inactive. The
		// VOID badge (instead of edit/return actions) + the row-click guard already prevent any action on it.
		"createdRow": function(row){ if (typeof isVoidedRow==='function' && isVoidedRow(getDocument($(row).html()))) $(row).addClass('row-voided'); },
		dom: 'Bfrtip',
		buttons: [
			'pageLength'
		].concat((tableV === 'Purchase') ? [{
				// Purchase only: voiding a SALE deletes its line rows (Option B), so tableSell never has a voided row to
				// reveal — the toggle would be a dead no-op there. Voided sales remain traceable via the Audit Log + receipt.
			// Voided rows are hidden by default (they're finalized/read-only). This toggles them in/out of the list.
			text: (window.hideVoided === false ? 'Hide voided' : 'Show voided'),
			action: function(e, dt, node){
				window.hideVoided = (window.hideVoided === false);
				node.text(window.hideVoided === false ? 'Hide voided' : 'Show voided');
				dt.draw();
			}
		}] : []).concat([
			lazyExcelButton({footer: true}),          // PERF-4b — library on first click
			{extend: 'print', footer: true},
			lazyPdfButton({
				orientation: 'landscape',
				pageSize: 'LEGAL',
				footer: true
			})
		// I1: Template + Import CSV, from /js/common/data-import.js. Returns [] unless the SERVER has an
		// ImportSpec for this entity, so Sell/Purchase/Orders/Quote draw nothing — those are numbered
		// documents whose creation moves stock and posts to the ledger, and a row inserted behind the sale
		// path is a row the books disagree with. One registry decides; there is no list to keep in step here.
		]).concat((typeof importButtons === 'function') ? importButtons(tableV) : []),
		"ajax": {
			// Load ALL records so DataTables handles Next/Back pagination and search locally.
			// Search: DataTables filters the loaded set first; re-open the section to refresh from DB.
			"url": serverContext + "getUser" + getAll + "?q=-1" + ((getAll === "Product" && window.productShowInactive) ? "&includeInactive=true" : ""),
			"type": "GET",
			/*
			 * TIER-1c — the grid does NOT hold the blocking overlay.
			 *
			 * This is what makes the preload split above worth anything. The overlay covers the whole
			 * viewport, so pickers that are loaded and ready are still unreachable while it is up: with the
			 * grid left blocking, starting the pickers earlier would have changed nothing a cashier could
			 * feel.
			 *
			 * And the wait is genuinely open-ended. `q=-1` is uncapped — every branch of the server's
			 * visibleSells() returns the full scoped list, and Sell rows are per LINE, not per invoice, so a
			 * shop at 100 sales a day of 5 lines accumulates ~180k rows a year. Freezing the counter behind
			 * the shop's entire trading history to ring up the next sale is the wrong trade at any size, and
			 * it gets worse every day the tenant uses the product.
			 *
			 * The section's own record list is background work, exactly like the pickers: show the screen and
			 * fill it in. DataTables still renders its own in-table loading text, so the grid area keeps its
			 * local feedback — a blocking modal spinner was never the honest indicator for it.
			 *
			 * ⚠ `global: false` also excludes this from ajaxComplete. Nothing here depends on that hook: the
			 * pickers refresh themselves (see refreshSearchableSelect) and this handler builds its own rows.
			 */
			"global": false,
			"success": function(data) {
				if(reload != tableV) reload = tableV;

				// (The dropdown preload that used to sit here now runs from loadDataTable() — see
				//  preloadSectionPickers(). It was never grid work; chaining it here made the pickers
				//  wait for the entire record list before they could even be requested.)

				var collections = data.collection;
				if(!collections || collections.length <= 0){
					datatable.columns([0]).visible(false);
					$(".dataTables_empty")[0].innerHTML = "No Data Found";
					return false;
				}

				userId = collections[0].userId;
				datatable.columns([0]).visible(false);
				// NOTE: delete is retired for purchases (a posted bill is voided, never hard-deleted). We do NOT hide
				// the checkbox COLUMN — the per-row Edit button (ensureRowEditButtons) is injected into that same
				// cell and needs the checkbox present. Instead the delete checkbox itself is hidden via CSS
				// (#tablePurchase input[type=checkbox]) so Edit stays but there's no delete affordance.

				// Build all rows first, then add in one shot so draw() fires only once
				var allRows = [];
				if (getAll === "Company") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=companyId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=companyName>"+escHtml(obj.name)+"</div>","<div id=companyPhone>"+escHtml(obj.phone)+"</div>",
							"<div id=companyEmail>"+escHtml(obj.email)+"</div>","<div id=companyAddress>"+escHtml(obj.address)+"</div>",obj.updatedStr
						]);
					});
				} else if (getAll === "Vender") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=venderId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=venderName>"+escHtml(obj.name)+"</div>",
							// A supplier can represent several brands — show them all. editRecord() reads this cell back
							// into the multi-select, matching each comma-separated label against the options.
							"<div id=venderCompanyDD>"+escHtml(obj.companyNames || "")+"</div>",
							"<div id=venderPhone>"+escHtml(obj.phone)+"</div>","<div id=venderMobile>"+escHtml(obj.mobile)+"</div>",
							"<div id=venderEmail>"+escHtml(obj.email)+"</div>","<div id=venderAddress>"+escHtml(obj.address)+"</div>",
							"<div id=venderDue>"+(obj.dueAmount!=null?obj.dueAmount:0)+"</div>",
							"<div id=venderCreditLimit>"+(obj.creditLimit!=null?obj.creditLimit:'')+"</div>",obj.datedStr,
							"<div class='row-actions'>"
							// Pay only makes sense when something is owed — hide it when the payable is 0.
							+ ((Number(obj.dueAmount)||0) > 0 ? "<button type=button class='btn btn-xs btn-primary pay-vendor-btn' data-vid='"+obj.id+"' data-name=\""+escHtml(obj.name||'')+"\" data-due='"+obj.dueAmount+"' title='Pay this vendor'><span class='glyphicon glyphicon-usd'></span> Pay</button> " : "")
							+ "<button type=button class='btn btn-xs btn-default stmt-btn' data-ptype='VENDOR' data-pid='"+obj.id+"' data-name=\""+escHtml(obj.name||'')+"\" title='Statement of account'><span class='glyphicon glyphicon-list-alt'></span> Statement</button>"
							+ "</div>"
						]);
					});
					$("#venderName").prop("readonly", false);
				} else if (getAll === "Customer") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=customerId>"+obj.customerId+"</div>","<input type='checkbox' value="+ obj.customerId+ ">",
							"<div id=customerName>"+escHtml(obj.name)+"</div>","<div id=contact>"+escHtml(obj.contact)+"</div>",
							"<div id=email>"+escHtml(obj.email)+"</div>","<div id=address>"+escHtml(obj.address)+"</div>",
							// B2B-P0/P1: these three cells MUST exist. The header has a column for each, and DataTables
							// requires row arrays to match the column count exactly — a missing cell shifts every later
							// column and throws "Requested unknown parameter". editRecord() also refills the form FROM
							// this row, so the id on each div is the form field it feeds.
							"<div id=customerType>"+escHtml(obj.customerType||'WALK_IN')+"</div>",
							"<div id=creditLimit>"+(obj.creditLimit!=null?obj.creditLimit:'')+"</div>",
							"<div id=paymentTermsDays>"+(obj.paymentTermsDays!=null?obj.paymentTermsDays:'')+"</div>",
							"<div id=dueAmount>"+(obj.dueAmount!=null?obj.dueAmount:0)+"</div>",
							"<div id=creditBalance>"+(obj.creditBalance!=null?Number(obj.creditBalance).toFixed(2):'0.00')+"</div>",obj.updated,
							"<div class='row-actions'>"
							// Receive only makes sense when the customer owes something — hide it when the due is 0.
							+ ((Number(obj.dueAmount)||0) > 0 ? "<button type=button class='btn btn-xs btn-primary rcv-pay-btn' data-cid='"+obj.customerId+"' data-name=\""+escHtml(obj.name||'')+"\" data-due='"+obj.dueAmount+"' title='Receive a payment against this customer'><span class='glyphicon glyphicon-usd'></span> Receive</button> " : "")
							+ "<button type=button class='btn btn-xs btn-default stmt-btn' data-ptype='CUSTOMER' data-pid='"+obj.customerId+"' data-name=\""+escHtml(obj.name||'')+"\" title='Statement of account'><span class='glyphicon glyphicon-list-alt'></span> Statement</button>"
							// Contact-360: this customer's identity + roles across modules (the shared helper applies the
							// owner/admin gate and the "only when bridged" rule in one place for every vertical).
							+ contact360Button(obj.partyId)
							+ "</div>"
						]);
					});
				} else if (getAll === "ItemType") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=itemTypeId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=itemTypeName>"+escHtml(obj.name)+"</div>","<div id=itemTypeDescription>"+escHtml(obj.description)+"</div>",obj.datedStr
						]);
					});
				} else if (getAll === "ItemUnit") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=itemUnitId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=itemUnitName>"+escHtml(obj.name)+"</div>","<div id=itemUnitDescription>"+escHtml(obj.description)+"</div>",obj.datedStr
						]);
					});
				} else if (getAll === "Item") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=itemId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=itemCompanyDD>"+escHtml(obj.companyName)+"</div>","<div id=itemVenderDD>"+escHtml(obj.venderName)+"</div>","<div id=itemName>"+escHtml(obj.iname)+"</div>",
							"<div id=itemCode>"+escHtml(obj.icode)+"</div>","<div id=itemDesc>"+escHtml(obj.idesc)+"</div>",obj.updated
						]);
					});
				} else if (getAll === "Purchase") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=purchaseId>"+obj.purchaseId+"</div>","<input type='checkbox' value="+ obj.purchaseId+ ">",
							"<div id=purchaseInvoiceNo>"+escHtml(obj.purchaseInvoiceNo)+"</div>","<div id=purchaseItemDD>"+escHtml(obj.iname || obj.icode || (obj.productId ? ('Product #'+obj.productId) : ''))+"</div>",
							// Vendor column — same pattern as tableVender/venderCompanyDD: backend supplies the name, and
							// editRecord (main.js) preselects the #purchaseVenderDD option by this text on edit.
							"<div id=purchaseVenderDD>"+escHtml(obj.venderName||'')+"</div>",
							"<div id=purchaseQuantity>"+obj.quantity+"</div>",
							// B2B-P3a (#2): batch/lot. Header position 7, so the cell goes here — a <th> with no cell
							// shifts every later column (the tableCustomer lesson from P0).
							"<div id=purchaseBatchNo>"+escHtml((obj.stock && obj.stock.batchNo)||'')+"</div>",
							// SER-2 (fix): the serials this bill brought in. escHtml because a serial is operator
							// input, and the id is what editRecord copies into #purchaseSerials on edit. The grade
							// rides along HIDDEN in the same cell so the edit form can restore its selector without
							// a column nobody asked for — editRecord matches a SELECT by option VALUE, and
							// NEW/USED/REFURBISHED is exactly the text it needs to read.
							"<div id=purchaseSerials>"+escHtml(obj.serials||'')+"</div>"
								+"<span id=purchaseCondition style='display:none'>"+escHtml(obj.conditionGrade||'')+"</span>",
							"<div id=purchasePurchaseRate>"+obj.stock.bpurchaseRate+"</div>","<div id=purchaseSellRate>"+obj.stock.bsellRate+"</div>",
							// Total = the vendor bill you owe for this line = goods (totalAmount) + input tax (taxAmount,
							// 0 unless the org captures purchase tax). Aligns with the "Total" header. The orphaned
							// discount cells (no header) were removed — they were displaying UNDER the Total/Profit
							// headers, which is why "Profit" showed a discount. No Profit on a purchase.
							// (Their <th>s remain COMMENTED OUT in businessDashboard.html — header and cell agree.)
							"<div id=purchaseTotalAmount>"+(obj.totalAmount!=null?((Number(obj.totalAmount)||0)+(Number(obj.taxAmount)||0)):'')+"</div>",
							// Amount paid to the vendor. Shown here AND read by editRecord to pre-fill #purchasePaid on edit
							// (editRecord matches a form field id to the row cell of the same id).
							"<div id=purchasePaid>"+(obj.paidAmount!=null?obj.paidAmount:'')+"</div>",
							// Due = what's still owed on this bill = gross (goods + tax) − paid, floored at 0. Derived
							// (display-only; no form field), consistent with the Total and Paid columns.
							"<div id=purchaseDue>"+(obj.totalAmount!=null?Math.max(0,((Number(obj.totalAmount)||0)+(Number(obj.taxAmount)||0))-(Number(obj.paidAmount)||0)).toFixed(2):'')+"</div>",
							"<div id=purchaseExpiry>"+obj.stock.bexpDate+"</div>",
						"<div id=purchaseDate>"+obj.updated+"</div><span class='row-actions'>"+ (obj.status === 'VOID' ? "<span class='label label-default' title='Voided bill'>VOID</span>" : "<button type=button class='btn btn-xs btn-warning purchase-return-btn' data-pid='"+obj.purchaseId+"' data-qty='"+obj.quantity+"' data-inv=\""+escHtml(obj.purchaseInvoiceNo||'')+"\" title='Return some or all stock to the vendor — reduces on-hand and the payable by the returned portion. The bill stays active.'><span class='glyphicon glyphicon-share-alt'></span> Return</button>"   + (window.canVoidInvoice ? " <button type=button class='btn btn-xs btn-danger purchase-void-btn' data-pid='"+obj.purchaseId+"' data-inv=\""+escHtml(obj.purchaseInvoiceNo||'')+"\" title='Cancel the WHOLE bill — reverses all stock-in and the payable, and makes it read-only. Use for a mistaken purchase.'><span class='glyphicon glyphicon-ban-circle'></span> Void</button>" : ""))+ "</span>"
						]);
					});
				} else if (getAll === "Sell") {
					$.each(collections, function(ind, obj) {
						var ch = obj.customerHistory || null;
						var custName = (ch && ch.customer && ch.customer.name) ? ch.customer.name
									: (obj.customer && obj.customer.name ? obj.customer.name : '');
						// "This invoice's due": header dueAmount is stored as (paid − bill), negative while
						// owing; show the positive amount still owed (0 when fully paid).
						var chDue = (ch && ch.dueAmount != null) ? Number(ch.dueAmount) : 0;
						var owed = chDue < 0 ? (-chDue) : 0;
						// Discount + its type in ONE cell: "10%" or "10 (Amt)". A bare "10" in its own column,
						// with the type in another, made the reader join two cells to know what it meant.
						var discAmt = (obj.discount != null && obj.discount !== '') ? obj.discount
									: ((obj.stock && obj.stock.bsellDiscount != null) ? obj.stock.bsellDiscount : '');
						var discType = (obj.dt != null && obj.dt !== '') ? obj.dt
									: ((obj.stock && obj.stock.bsellDiscountType != null) ? obj.stock.bsellDiscountType : '');
						var discCell = (discAmt === '' || Number(discAmt) === 0) ? ''
									: (discType === '1' || discType === '%') ? (discAmt + '%') : (discAmt + ' (Amt)');
						// Columns must match tableSell's <thead> exactly, in order:
						// Dated Â· Invoice Â· Customer Â· Product Â· Qty Â· Unit Price Â· Discount Â· Tax Â· Line Total Â·
						// Payment Â· Invoice Due Â· Actions
						allRows.push([
							obj.updated,
							"<div id=sellInvoiceNo>"+escHtml(ch ? (ch.invoiceNo || '') : '')+"</div>",
							"<div id=sellCustomerName>"+escHtml(custName)+"</div>",
							"<div id=sellItemName>"+escHtml(obj.itemName||'')+"</div>",
							// #17 P3: the grid shows what LEFT, free goods included — see bonusSuffix.
							"<div id=sellItems>"+obj.quantity+bonusSuffix(obj)+"</div>",
							// The rate the line SOLD at. Read obj.sellRate FIRST: it is the server's authoritative
							// value; stock.bsellRate is the form's echo and is not what was persisted.
							"<div id=sellSellRate>"+(obj.sellRate!=null?obj.sellRate:(obj.stock&&obj.stock.bsellRate!=null?obj.stock.bsellRate:''))+"</div>",
							"<div id=sellDiscount>"+escHtml(String(discCell))+"</div>",
							"<div id=sellTaxAmount>"+(obj.taxAmount!=null?obj.taxAmount:'')+"</div>",
							// Line Total = what this line was charged (discounted base + tax) — the server derives it.
							"<div id=sellNetAmount>"+(obj.netAmount!=null?obj.netAmount:'')+"</div>",
							"<div id=sellPaymentMode>"+escHtml(ch&&ch.paymentMode?ch.paymentMode:'')+"</div>",
							"<div id=sellDueAmount>"+owed.toFixed(2)+"</div>",
							// Actions: G6 (slice 38) Print receipt + G2 (slice 34) Sale Return. Print uses the
							// invoice number; Return passes its row data via data-* for the partial-qty dialog.
							"<div class='row-actions'>"
							+ ((ch && ch.invoiceNo)
								? "<button type='button' class='btn btn-xs btn-default' title='Print receipt' onclick=\"printReceipt('"+escHtml(ch.invoiceNo)+"')\"><span class='glyphicon glyphicon-print'></span></button> "
								: "")
							// A voided invoice is read-only: show ONLY the VOID badge (no Return, no Void). Otherwise show
							// Return, plus Void for a privileged user.
							+ ((ch && ch.status === 'VOID')
								? "<span class='label label-default' title='Voided invoice — read-only'>VOID</span>"
								: ("<button type='button' class='btn btn-xs btn-warning' onclick='openSaleReturn(this)'"
									+ " title='Return some or all items — restocks them and refunds only the returned portion. The invoice stays active.'"
									+ " data-sellid='"+obj.sellId+"'"
									+ " data-stockid='"+(obj.stock&&obj.stock.stockId!=null?obj.stock.stockId:'')+"'"
									+ " data-qty='"+(obj.quantity!=null?obj.quantity:'')+"'"
									+ " data-invoice='"+escHtml(ch?(ch.invoiceNo||''):'')+"'"
									+ " data-item='"+escHtml(obj.itemName||'')+"'>"
									+ "<span class='glyphicon glyphicon-share-alt'></span> Return</button>"
									+ ((window.canVoidInvoice && ch && ch.customer_history_id)
										? " <button type='button' class='btn btn-xs btn-danger' title='Cancel the WHOLE invoice — reverses every line, refunds all paid, and makes it read-only. Use for a mistaken sale.' onclick='openVoidSell(this)' data-chid='"+ch.customer_history_id+"' data-invoice='"+escHtml(ch.invoiceNo||'')+"'><span class='glyphicon glyphicon-ban-circle'></span> Void</button>"
										: "")))
								+ "</div>"
						]);
					});
				} else if (getAll === "Product") {
					// Product master row, rendered through the shared DataTable (same path as Customer). Columns:
					// [id(hidden), checkbox, name, sku, unit, price, last-purchase-rate, last-sale-rate,
					//  tax%, category, MANUFACTURER, on-hand(lazy), add-stock control, status].
					// This array MUST stay exactly as long as the #tableProduct header — a missing cell shifts every
					// later column and DataTables throws "Requested unknown parameter".
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=productId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=name>"+escHtml(obj.name || '')+"</div>","<div id=sku>"+escHtml(obj.sku || '')+"</div>",
							"<div id=unit>"+escHtml(obj.unit || '')+"</div>",
							"<div id=sellingPrice>"+(obj.sellingPrice != null ? Number(obj.sellingPrice).toFixed(2) : '')+"</div>",
							// Last rates come stamped on the product itself (written by the purchase flow), so they
							// render straight from this row — no lazy fill, no extra request.
							lastRateCell(obj.lastPurchaseRate, obj.lastRateAt, t('ui.js.lastPurchased')),
							lastRateCell(obj.lastSaleRate,     obj.lastRateAt, t('ui.js.lastSold')),
							"<div id=taxRate>"+(obj.taxRate != null ? Number(obj.taxRate).toFixed(2) : '')+"</div>",
							"<div id=categoryName>"+escHtml(obj.categoryName || '')+"</div>",
							// Manufacturer/brand — already carried by getUserProduct, so this needed no new request.
							// escHtml because it is operator-typed free text (XSS-safe rendering rule).
							"<div id=manufacturer>"+escHtml(obj.manufacturer || '')+"</div>",
							"<div id=stk_"+obj.id+" class=prod-onhand>…</div>",
							"<div class='row-actions'>"
								+ "<input type=number min=0 step=any id=addstk_"+obj.id+" class='form-control input-sm prod-addstk' style='width:80px;display:inline-block'>"
								+ "<button type=button id=addstkbtn_"+obj.id+" class='btn btn-xs btn-success' style='margin-left:4px' title='Add to on-hand' onclick='addProductStock("+obj.id+")'><span class='glyphicon glyphicon-plus'></span></button>"
								+ "<button type=button id=lessstkbtn_"+obj.id+" class='btn btn-xs btn-warning' style='margin-left:4px' title='Correct / reduce on-hand' onclick='adjustProductStock("+obj.id+")'><span class='glyphicon glyphicon-minus'></span></button>"
								+ "</div>",
							(obj.isActive === false
								? "<span class='label label-default'>Inactive</span> <button type=button class='btn btn-xs btn-info' style='margin-left:6px' onclick='reactivateProduct("+obj.id+")' title='Reactivate this product'><span class='glyphicon glyphicon-refresh'></span> Reactivate</button>"
								: "<span class='label label-success'>Active</span>")
						]);
					});
				}
				// Single draw — much faster than calling draw() on every row.add()
				datatable.rows.add(allRows).draw();

				// SER-2 (fix): a capability-gated COLUMN is hidden through the DataTables API, never with
				// `data-capability` on its <th>. See applyPurchaseColumnCapabilities.
				if (getAll === "Purchase") applyPurchaseColumnCapabilities(datatable);

				// Product on-hand is inventory (not catalog). Fill EVERY row's on-hand in ONE batch call
				// (/productStockLevels â†’ inventory /stock/levels/detail) instead of a per-row /productStock request.
				// Show the honest SELLABLE count (what a sale can actually reserve) + a red "N expired" badge when
				// physical stock is locked in expired batches — so a 16-on-hand/0-sellable product no longer lies.
				if (getAll === "Product") {
					$.get(serverContext + "productStockLevels", function(resp){
						var levels = (resp && resp.success && resp.levels) ? resp.levels : {};
						$.each(collections, function(ind, obj){
							var d = levels[obj.id];
							var el = $('#stk_' + obj.id);
							if (d == null) { el.text('0'); return; }
							// Back-compat: a bare number means sellable only.
							var sellable = (typeof d === 'object') ? Number(d.sellable || 0) : Number(d);
							var expired  = (typeof d === 'object') ? Number(d.expired  || 0) : 0;
							var onHand   = (typeof d === 'object') ? Number(d.onHand   || 0) : sellable;
							/*
							 * U6 — say it in the language of the person counting the shelf.
							 *
							 * 9.5 is arithmetically true and operationally useless: nobody counts half a
							 * pack. It means nine sealed packs and five loose tablets, and until the screen
							 * says so the shop cannot reconcile what it holds against what we claim.
							 *
							 * An ordinary product has no pack size, so shelfText returns the plain number and
							 * this row renders exactly as it does today.
							 */
							var shelf = (typeof shelfText === 'function')
								? shelfText(sellable, obj.packSize, obj.looseUnitPlural || obj.looseUnit)
								: { text: String(sellable) };
							var html = "<span title='" + onHand + " physical on-hand'>"
								+ escHtml(shelf.text) + "</span>";
							if (expired > 0) {
								html += " <span class='label label-danger' style='margin-left:4px' title='" + expired
									+ " unit(s) in expired batches — physically present but not sellable'>" + expired + " expired</span>";
							}
							el.html(html);   // numbers only (no user data) â†’ XSS-safe
						});
					}).fail(function(){
						$.each(collections, function(ind, obj){ $('#stk_' + obj.id).text('—'); });
					});
				}
			},
			error: function(jqXHR, textStatus, errorThrown) {
				console.log(jqXHR, textStatus, errorThrown);
				handleAjaxFailure(jqXHR, errorThrown, "loadDataTable");   // was: unconditional redirect to /login
			}
		}
	});

	// Re-bind length-change for whichever table is currently active
	$("select[name='table" + tableV + "_length']").change(function(){
		loadDataTable();
	});

	//call it to enable the fields for editing the data in the table
	updateReadOnly(false);
}

function loadUserCustomers(table) {
    $.get(serverContext+ "getUserCustomers",function(data){
		console.log("User customers loaded successfully: "+data);
    })
	.fail(function(data) {
		console.log("Error while loading user customers : "+data);
	});
}

// ddId is optional and defaults to the sell screen's picker, so every existing loadSellCustomers() call is
// unchanged. B2B-P4b reuses this for the quote form rather than copying the option-building (which carries the
// credit limit and customer type the pricing/credit rules need) into a second place.
function loadSellCustomers(ddId) {
	var dd = $("#" + (ddId || "sellCustomerDD"));
	/*
	 * TIER-1b — a LOADING placeholder, not an empty one.
	 *
	 * The read below no longer blocks, so the till is typeable while this list is still arriving. That
	 * makes the picker's own text the only thing telling the cashier which state they are in, and
	 * "Select Customer" over an empty list is a lie: it reads as "this shop has no customers" exactly
	 * when the truth is "not here yet". It is replaced the moment the rows land.
	 *
	 * The picker is deliberately left ENABLED. `EnterChain.usable()` treats a disabled control as
	 * skippable, so disabling it while loading would send the new-sale cursor straight past the customer
	 * to #sellCN — silently undoing the entry point, and only on slow connections, which is precisely
	 * where nobody would be watching for it.
	 */
	dd.empty().append($('<option>').val('').text(t('ui.js.loadingCustomers')));
	// PERF: the LEAN read. `getUserCustomer` returns 22 fields per customer (~215KB for 441 rows,
	// unpaginated); this dropdown uses six of them, and `customerOptions` selects exactly those. Same
	// role-aware scoping on the server, same `collection` envelope, so nothing below changes.
	//
	// bgJson = `global: false`: populating a picker is background work and must not hold the blocking
	// overlay over a counter. The explicit refresh at the end is NOT optional — see refreshSearchableSelect.
	bgJson(serverContext + "customerOptions", function(res) {
		dd.empty().append('<option value=""> Select Customer </option>');
		if (res && res.collection) {
			$.each(res.collection, function(i, c) {
				// B2B-P1 (#9): carry the credit limit alongside the balance the option already carries, so the
				// screen can show "available" live while typing without a call per keystroke. Only a HINT —
				// the server re-checks against the current balance, which another till may have moved.
				// B2B-P2-UI: carry customerType too — a TIER price rule matches on it, and the till must quote
				// with the same inputs the server does or the two can disagree about the price.
				dd.append('<option value="' + c.customerId + '" data-contact="' + escHtml(c.contact || '') + '" data-due="' + (c.dueAmount != null ? c.dueAmount : 0) + '" data-credit-limit="' + (c.creditLimit != null ? c.creditLimit : '') + '" data-customer-type="' + escHtml(c.customerType || '') + '">' + escHtml(c.name) + '</option>');
			});
		}
		// The widget will NOT redraw on its own: a `global: false` request is excluded from the
		// ajaxComplete hook that normally keeps every AJAX-filled picker in sync. Without this the
		// options exist in the DOM and the cashier sees an empty list forever.
		refreshSearchableSelect(dd);
	}).fail(function() {
		console.log("Error loading customers for sell dropdown");
		// Say so, rather than leaving the loading text sitting there forever on a dead request.
		dd.empty().append($('<option>').val('').text(t('ui.js.couldNotLoadCustomers')));
		refreshSearchableSelect(dd);
	});
}

function onSellCustomerSelect(sel) {
	var opt = $(sel).find(':selected');
	var customerId = opt.val();
	if (customerId) {
		$("#sellCN").val(opt.text());
		$("#sellCC").val(opt.data('contact') || '');
		document.getElementById("sellCustomerDD").style.removeProperty('border-color');
		var due = Number(opt.data('due'));
		window.selectedCustomerDue = isNaN(due) ? 0 : due;   // existing customer's running balance
		var lim = opt.attr('data-credit-limit');
		window.selectedCustomerLimit = (lim === '' || lim == null || isNaN(Number(lim))) ? null : Number(lim);
		loadCustomerCredit(customerId);                      // SF-5 Model B: show/offer store credit
	} else {
		$("#sellCN").val('');
		$("#sellCC").val('');
		window.selectedCustomerDue = null;                   // no account context (nothing picked)
		window.selectedCustomerLimit = null;
		$('#sellStoreCreditWrap').hide(); $('#sellStoreCredit').val('');
	}
	refreshAccountDuePreview();
	// B2B-P2-UI: who is buying decides the price. Re-price anything already in the cart, and the line being
	// composed on the form, now that there is a buyer to price against.
	requoteSellCart();
	quoteSellFormPrice($('#sellItemDD').val());
}

// SF-5 Model B: fetch the selected customer's store-credit balance; show the "apply credit" field only if they have any.
function loadCustomerCredit(customerId){
	$('#sellStoreCreditWrap').hide(); $('#sellStoreCredit').val('');
	if(!customerId) return;
	$.get(serverContext + 'customerCredit', { customerId: customerId }, function(resp){
		var bal = (resp && resp.object != null) ? Number(resp.object) : 0;
		if(bal > 0){ $('#sellCreditAvail').text(bal.toFixed(2)); $('#sellStoreCreditWrap').show(); }
	}, 'json');
}

// â”€â”€â”€ B2B-P2-UI (#10): charge the price the buyer is actually entitled to â”€â”€â”€â”€â”€â”€
//
// THE BUG THIS FIXES. Server-side, the submitted rate WINS over a matched price rule — deliberately, because
// a cashier's override must beat a rule, and `price-override.cy.js` exists to keep it that way. But this
// screen has always prefilled the rate box from the CATALOG price the moment a product is picked. So on every
// real sale the basket was quoted, a contract rule matched, the line's priceReason was set — and the customer
// was charged catalog anyway. A receipt could read "Contract price −12%" beside an undiscounted amount.
//
// P2's gate missed it because it posts {productId, quantity} with NO sellRate — the one path that takes the
// server's fallback branch. It proved the engine, not the till.
//
// THE FIX IS HERE, NOT ON THE SERVER. The server cannot tell a deliberate 850-on-a-1000-item from this
// screen's prefill; both arrive as a number in the same field. So the till asks what the buyer pays and puts
// THAT in the box — visible, and still overridable. Server precedence is untouched.

/** The buyer to price against, or null when no rule could match anyway (walk-in / manually typed customer). */
function sellQuoteContext(){
	var opt = $('#sellCustomerDD').find(':selected');
	var customerId = opt.val();
	if(!customerId) return null;
	// customerType matters even WITH an id: a tier rule matches on it, and the server quotes with both. Send
	// the same inputs or the price shown here and the price the rules intend can differ.
	return { customerId: Number(customerId), customerType: opt.attr('data-customer-type') || null };
}

/** Ask what this buyer pays. Yields a productIdâ†’line map holding ONLY lines a rule actually priced. */
function quoteSellLines(ctx, lines, cb){
	if(!ctx || !lines || !lines.length){ cb({}); return; }
	$.ajax({
		type:'POST', url:serverContext+'priceQuote', contentType:'application/json', dataType:'json',
		data: JSON.stringify({ customerId: ctx.customerId, customerType: ctx.customerType, lines: lines }),
		success: function(resp){
			var byProduct = {};
			((resp && resp.lines) || []).forEach(function(l){
				// A line with no ruleId was priced at catalog — it would only restate what the box already has.
				if(l && l.productId != null && l.ruleId != null && l.unitPrice != null){
					byProduct[String(l.productId)] = l;
				}
			});
			cb(byProduct);
		},
		// A pricing outage must never stop a sale: fall back to the catalog price already in the box, which
		// is exactly today's behaviour. Same choice SagaSellService makes server-side.
		error: function(){ cb({}); }
	});
}

/**
 * Price the line being composed on the form. Runs alongside the productSellable call already made on pick,
 * so it adds no round trip to the critical path — the catalog price is written synchronously first and the
 * line is usable immediately; the contract price replaces it when it arrives.
 */
function quoteSellFormPrice(productId){
	$('#sellPriceReason').hide().text('');
	var ctx = sellQuoteContext();
	if(!ctx || !productId) return;
	var qty = $('#sellItems').val()*1>0 ? $('#sellItems').val()*ONE : 1;
	quoteSellLines(ctx, [{ productId: Number(productId), quantity: qty }], function(byProduct){
		var q = byProduct[String(productId)];
		if(!q) return;
		// Only ever replace a rate THIS code prefilled. If the cashier typed one while the quote was in
		// flight, theirs stands — that is the override the server is built to honour.
		if(window._sellAutoRate == null || Number($('#sellSellRate').val()) !== Number(window._sellAutoRate)) return;
		$('#sellSellRate').val(q.unitPrice);
		window._sellAutoRate = Number(q.unitPrice);
		$('#sellPriceReason').text(q.reason || '').show();
		calculateNetSell();
	});
}

/**
 * Re-price the cart when the customer is chosen AFTER items were added — the other half of the fix. Without
 * it, "scan first, ask who's buying second" (an ordinary counter habit) still charges catalog.
 *
 * ONE call for the whole cart, never one per line.
 */
function requoteSellCart(){
	var ctx = sellQuoteContext();
	if(!ctx || !data || !data.length) return;
	var lines = data.map(function(d){ return { productId: Number(d.productId), quantity: Number(d.quantity)||1 }; });
	quoteSellLines(ctx, lines, function(byProduct){
		var changed = 0;
		data.forEach(function(d){
			var q = byProduct[String(d.productId)];
			if(!q) return;
			// Same rule as the form: a rate the cashier set is not ours to move.
			if(d.autoRate == null || Number(d.sellRate) !== Number(d.autoRate)) return;
			var disc = (d.stock && d.stock.bsellDiscount) || 0;
			var dType = (d.stock && d.stock.bsellDiscountType) || '0';
			var m = sellLineMath(q.unitPrice, d.quantity, (d.stock && d.stock.bpurchaseRate) || 0, disc, dType);
			d.sellRate = Number(q.unitPrice);
			d.autoRate = Number(q.unitPrice);
			if(d.stock) d.stock.bsellRate = Number(q.unitPrice);
			d.totalAmount = m.total;      // the cart totals read these, so they must move WITH the rate
			d.netAmount = m.profit;
			d.priceReason = q.reason || '';
			changed++;
		});
		if(!changed) return;
		tablesi.rows().every(function(){
			var row = this.data();
			var d = data.filter(function(x){ return String(x.productId) === String(row[0]); })[0];
			if(!d) return;
			row[3] = d.sellRate;
			row[5] = sellLineMath(d.sellRate, d.quantity, 0,
				(d.stock && d.stock.bsellDiscount) || 0, (d.stock && d.stock.bsellDiscountType) || '0').receivable;
			this.data(row);
		});
		tablesi.draw(false);
		CIT(data);                                    // cart subtotals read data[], so refresh them too
		if(typeof calculateChange === 'function') calculateChange();
		if(typeof refreshAccountDuePreview === 'function') refreshAccountDuePreview();
		// Say it out loud: a cart whose prices changed by themselves is alarming if unexplained.
		showSaleSuccess(t('ui.js.repricedForCustomer').replace('{0}', String(changed)));
	});
}

function onCustomerModeChange(mode) {
	if (mode === 'select') {
		$('#customerSelectMode').show();
		$('#customerManualMode').hide();
		$('#sellCustomerDD').val('');
		$('#sellCN').val('');
		$('#sellCC').val('');
		$('#btnModeSelect').addClass('active');
		$('#btnModeManual').removeClass('active');
	} else {
		$('#customerSelectMode').hide();
		$('#customerManualMode').show();
		$('#sellCustomerDD').val('');
		$('#sellCN').val('');
		$('#sellCC').val('');
		$('#btnModeManual').addClass('active');
		$('#btnModeSelect').removeClass('active');
	}
	// Switching mode clears the selected customer, so drop the account-balance preview.
	window.selectedCustomerDue = null;
	if (typeof refreshAccountDuePreview === 'function') refreshAccountDuePreview();
}

function getDashboardData() {
    // Tier 1: the dashboard TILES are background population — they must not hold the blocking overlay
    // while a cashier is trying to open the till. See bgJson in js/common/ajax-overlay.js.
    bgJson(serverContext + 'getBusinessDashboardStats', function(res) {
        if (res.status === 'SUCCESS' && res.object) {
            var s = res.object;
            $('#dashCompanies').text(s.companies);
            $('#dashVenders').text(s.venders);
            $('#dashCustomers').text(s.customers);
            $('#dashItems').text(s.items);
            /*
             * Task #20 — stock value. ABSENT rather than zero when inventory-service could not answer: the
             * dashboard omits the key entirely in that case, and printing 0 for "we could not reach the
             * service" would be a lie about the shop's stock being worthless.
             */
            $('#dashStockValue').text(s.stockValue != null ? s.stockValue : '—');
            $('#dashMonthlySales').text(s.monthlySales);
            $('#dashMonthlyRevenue').text(s.monthlyRevenue);
            // C5 — present only when the tenant has the installments capability: the server skips the COUNT
            // entirely for everyone else, so the key is ABSENT rather than zero. Guarded on that, because
            // writing an undefined would print "undefined" in the tile for every other tenant — and the tile
            // is hidden for them, which is exactly the kind of bug nobody sees until the capability is
            // switched on and the number is wrong from the first render.
            if (s.installmentsDue !== undefined && s.installmentsDue !== null) {
                $('#dashInstallmentsDue').text(s.installmentsDue);
            }
        }
    }).fail(function() {
        console.log('Error loading dashboard stats');
    });
    loadDashboardCharts();
}

var _chartTrend = null, _chartDaily = null, _chartTopItems = null, _chartCustSales = null;

function loadDashboardCharts() {
    // Tier 1: the CHARTS were the slowest call on the page (~970ms measured) and blocked every other
    // screen behind them. Nothing a cashier does depends on a chart having rendered.
    bgJson(serverContext + 'getDashboardChartData', function(res) {
        if (res.status !== 'SUCCESS' || !res.object) return;
        var d = res.object;

        // destroy existing chart instances before redraw
        if (_chartTrend)     { _chartTrend.destroy();     _chartTrend = null; }
        if (_chartDaily)     { _chartDaily.destroy();     _chartDaily = null; }
        if (_chartTopItems)  { _chartTopItems.destroy();  _chartTopItems = null; }
        if (_chartCustSales) { _chartCustSales.destroy(); _chartCustSales = null; }

        // --- Revenue & Sales Trend (dual-axis line) ---
        var ctxTrend = document.getElementById('chartTrend');
        if (ctxTrend) {
            _chartTrend = new Chart(ctxTrend, {
                type: 'line',
                data: {
                    labels: d.monthLabels,
                    datasets: [
                        {
                            label: 'Revenue',
                            data: d.monthRevenue,
                            borderColor: '#337ab7',
                            backgroundColor: 'rgba(51,122,183,0.12)',
                            fill: true,
                            tension: 0.4,
                            yAxisID: 'yRev',
                            pointRadius: 4,
                            pointHoverRadius: 6
                        },
                        {
                            label: 'Sales Count',
                            data: d.monthSalesCount,
                            borderColor: '#5cb85c',
                            backgroundColor: 'rgba(92,184,92,0.12)',
                            fill: false,
                            tension: 0.4,
                            yAxisID: 'yCnt',
                            borderDash: [5, 3],
                            pointRadius: 4,
                            pointHoverRadius: 6
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    interaction: { mode: 'index', intersect: false },
                    plugins: {
                        legend: { position: 'top' },
                        tooltip: { callbacks: {
                            label: function(ctx) {
                                return ctx.dataset.label + ': ' + (ctx.dataset.yAxisID === 'yRev'
                                    ? ctx.parsed.y.toLocaleString() : ctx.parsed.y);
                            }
                        }}
                    },
                    scales: {
                        yRev: { type: 'linear', position: 'left',  title: { display: true, text: 'Revenue' }, beginAtZero: true },
                        yCnt: { type: 'linear', position: 'right', title: { display: true, text: 'Sales' },   beginAtZero: true, grid: { drawOnChartArea: false } }
                    }
                }
            });
        }

        // --- Daily Revenue Bar ---
        var ctxDaily = document.getElementById('chartDaily');
        if (ctxDaily) {
            _chartDaily = new Chart(ctxDaily, {
                type: 'bar',
                data: {
                    labels: d.dayLabels,
                    datasets: [{
                        label: 'Revenue',
                        data: d.dailyRevenue,
                        backgroundColor: d.dailyRevenue.map(function(v) {
                            return v > 0 ? 'rgba(92,184,92,0.75)' : 'rgba(200,200,200,0.4)';
                        }),
                        borderColor: d.dailyRevenue.map(function(v) {
                            return v > 0 ? '#3c763d' : '#aaa';
                        }),
                        borderWidth: 1,
                        borderRadius: 4
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { title: { display: true, text: 'Day of Month' } },
                        y: { beginAtZero: true, title: { display: true, text: 'Revenue' } }
                    }
                }
            });
        }

        // --- Top Items Horizontal Bar ---
        var ctxTop = document.getElementById('chartTopItems');
        if (ctxTop) {
            var palette = ['#337ab7','#5cb85c','#f0ad4e','#d9534f','#9b59b6'];
            _chartTopItems = new Chart(ctxTop, {
                type: 'bar',
                data: {
                    labels: d.topItemNames.length > 0 ? d.topItemNames : ['No data'],
                    datasets: [{
                        label: 'Qty Sold',
                        data: d.topItemQtys.length > 0 ? d.topItemQtys : [0],
                        backgroundColor: palette,
                        borderRadius: 4
                    }]
                },
                options: {
                    indexAxis: 'y',
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { beginAtZero: true, title: { display: true, text: 'Quantity' } }
                    }
                }
            });
        }

        // --- Sales by Customer (doughnut) ---
        var ctxCust = document.getElementById('chartCustSales');
        if (ctxCust) {
            var custPalette = ['#337ab7','#5cb85c','#f0ad4e','#d9534f','#9b59b6','#1abc9c','#e67e22','#e74c3c'];
            var custLabels = d.custSalesNames && d.custSalesNames.length > 0 ? d.custSalesNames : ['No sales'];
            var custData   = d.custSalesAmounts && d.custSalesAmounts.length > 0 ? d.custSalesAmounts : [0];
            _chartCustSales = new Chart(ctxCust, {
                type: 'doughnut',
                data: {
                    labels: custLabels,
                    datasets: [{
                        data: custData,
                        backgroundColor: custPalette.slice(0, custLabels.length),
                        borderWidth: 2,
                        hoverOffset: 10
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    cutout: '60%',
                    plugins: {
                        legend: { position: 'bottom', labels: { boxWidth: 12, padding: 10 } },
                        tooltip: { callbacks: {
                            label: function(ctx) {
                                var total = ctx.dataset.data.reduce(function(a,b){ return a+b; }, 0);
                                var pct = total > 0 ? ((ctx.parsed / total) * 100).toFixed(1) : 0;
                                return ' ' + ctx.label + ': ' + ctx.parsed.toLocaleString() + ' (' + pct + '%)';
                            }
                        }}
                    }
                }
            });
        }

        // --- Top customers with due payments (table) ---
        var tbody = document.getElementById('dueCustTableBody');
        if (tbody) {
            var dueList = d.dueCustomers || [];
            if (dueList.length === 0) {
                tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted">No outstanding dues</td></tr>';
            } else {
                var today = new Date(); today.setHours(0,0,0,0);
                var rows = dueList.map(function(c) {
                    var dueDate = c.dueDate ? new Date(c.dueDate) : null;
                    var statusHtml;
                    if (!dueDate || isNaN(dueDate.getTime())) {
                        statusHtml = '<span class="label label-default">No date</span>';
                    } else if (dueDate < today) {
                        var days = Math.floor((today - dueDate) / 86400000);
                        statusHtml = '<span class="label label-danger">Overdue ' + days + 'd</span>';
                    } else {
                        var days = Math.floor((dueDate - today) / 86400000);
                        statusHtml = days === 0
                            ? '<span class="label label-warning">Due today</span>'
                            : '<span class="label label-info">In ' + days + 'd</span>';
                    }
                    var dueDateStr = dueDate && !isNaN(dueDate.getTime())
                        ? dueDate.toLocaleDateString() : '—';
                    return '<tr>'
                        + '<td><strong>' + escHtml(c.name || '') + '</strong></td>'
                        + '<td>' + escHtml(c.contact || '') + '</td>'
                        + '<td><strong class="text-danger">' + parseFloat(c.due || 0).toLocaleString() + '</strong></td>'
                        + '<td>' + dueDateStr + '</td>'
                        + '<td>' + statusHtml + '</td>'
                        + '</tr>';
                });
                tbody.innerHTML = rows.join('');
            }
        }

    }).fail(function() {
        console.log('Error loading dashboard charts');
    });
}

function loadUserCompanies(table) {	
    $.get(serverContext+ "getUserCompanies",function(data){
    	$("#"+table.toLowerCase()+"CompanyDD").empty().append(data).selectpicker('refresh');
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"CompanyDD").empty().append("<option value = ''> System error  </option>");
	});
}

/**
 * B2B-P0 (#8): show what the chosen vendor is already owed, before more credit is taken on.
 *
 * Delegated from document so it survives selectpicker('refresh') replacing the control, and reads the
 * data-due the server already put on the option — no round trip per selection. Hidden for a cash purchase
 * (no vendor = no payable to report).
 */
$(document).on('change', '#purchaseVenderDD', function () {
	var due = Number($(this).find(':selected').data('due'));
	var wrap = document.getElementById('purchaseVendorDuesWrap');
	var box = document.getElementById('purchaseVendorDues');
	if (!wrap || !box) return;
	if (!$(this).val()) { wrap.style.display = 'none'; box.value = ''; return; }
	box.value = isNaN(due) ? 0 : due;
	wrap.style.display = '';
});

function loadUserVenders(table) {
    $.get(serverContext+ "getUserVenders",function(data){
    	// A rebuild must not silently un-answer the picker. P6 rapid entry keeps the purchase modal OPEN
    	// across saves, and every save reloads the grid, whose success handler lands here — so the vendor
    	// the operator chose for the BILL was being wiped a beat after each line. Re-select what was
    	// there; a value whose option no longer exists simply falls out, exactly as before.
    	var $dd = $("#"+table.toLowerCase()+"VenderDD"), keep = $dd.val();
    	$dd.empty().append(data);
    	if (keep) $dd.val(keep);
    	$dd.selectpicker('refresh');
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"VenderDD").empty().append("<option value = ''> System error  </option>");
	});
}

function laodUserItemTypes(table) {	
	$("#"+table+"TypeDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserItemTypes",function(data){
    	$("#"+table+"TypeDD").append(data);
    })
	.fail(function(data) {
		$("#"+table+"TypeDD").empty().append("<option value = ''> System error  </option>");
	});
}
function loadUserItemUnits(table) {	
	$("#"+table+"UnitDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserItemUnits",function(data){
    	$("#"+table+"UnitDD").append(data);
    })
	.fail(function(data) {
		$("#"+table+"UnitDD").empty().append("<option value = ''> System error  </option>");
	});
}

function loadUserItems(table) {
	// M4e.1b (slice 98): the sell/purchase picker lists catalog PRODUCTS (value = productId) sourced from the catalog
	// master — not the local Item table. data-product carries the same productId so the cart submits productId-native.
	// PERF-8: one CACHED, lean read (js/common/product-picker.js) instead of downloading the whole
	// product master here. This ran on every section open AND every cart-line edit, fetching all 23
	// fields of every product — deactivated ones included — to build a three-field <select>. Active
	// filtering now happens in SQL, so the isActive check that used to live in this loop is gone.
	ProductPicker.load(function(list){
		var html = ProductPicker.optionsHtml(list);
		// Survive the rebuild: see loadUserVenders. The item picker is worse than the vendor one, because
		// an item wiped mid-entry does not merely look wrong — main.js refuses the save outright
		// ("Select an item and enter a quantity greater than 0.") and the line the operator just typed
		// is lost with no request ever sent.
		var $dd = $("#"+table+"ItemDD"), keep = $dd.val();
		$dd.empty().append(html);
		if (keep) $dd.val(keep);
		if($dd.data('selectpicker')) $dd.selectpicker('refresh');
	}, function() {
		// onFail: PagedFetch.all is not a jqXHR, so the old .fail() chain moves in here.
		$("#"+table+"ItemDD").empty().append("<option value = ''> System error  </option>");
	});
}

function loadUserItem(table) {	
	$("#"+table+"UnitDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserItemUnits",function(data){
    	$("#"+table+"UnitDD").append(data);
    })
	.fail(function(data) {
		$("#"+table+"UnitDD").empty().append("<option value = ''> System error  </option>");
	});
}

function calculateNet(val){
	//return false;
	$('#itemSellAmount').removeClass("alert-danger");
	$('#itemPurchaseAmount').removeClass("alert-danger");
	$("#itemNet").val($("#itemSellAmount").val() - $("#itemPurchaseAmount").val());// - $("#itemDiscount").val());
	
/*	if($('#discountTypeDD').val() == "Amount"){
		$("#itemNet").val($("#itemSellAmount").val() - $("#itemPurchaseAmount").val() - $("#itemDiscount").val());
	}else{
		//Discount  =  List Price × Discount Rate 
		var discount =  ($("#itemSellAmount").val() - $("#itemPurchaseAmount").val()) * ($("#itemDiscount").val()*1 / 100);
		$("#itemNet").val($("#itemSellAmount").val() - $("#itemPurchaseAmount").val() - discount);		
	}
*/	
	if(($("#itemNet").val()*1) <0){
		//$("#itemSellAmount").val(0.0);
		$('#itemSellAmount').addClass("alert-danger"); 
		$('#itemPurchaseAmount').addClass("alert-danger"); 
		uiConfirm({
			title: t('ui.js.sellingBelowCost'),
			message: t('ui.js.theSellPriceIsLowerThanThe'),
			confirmText: t('ui.js.keepPrices'),
			cancelText: t('ui.js.clearThem'),
			tone: 'warning'
		}).then(function (keep) {
			if (!keep) {
				$("#itemSellAmount").val(0.0);
				$("#itemPurchaseAmount").val(0.0);
			}
		});
	}
}

var batchStock = 0;
var discountType = "";
var discountValue = "0";
function loadStock(label,value){
	bpurchaseDiscount: 0
	bpurchaseDiscountType: "%"
	bpurchaseRate: 0
	bsellDiscount: 0
	bsellDiscountType: "%"
	bsellRate: 0
	// edit = false;
	$("#purchasePurchaseRate").val("");
	$("#purchaseSellRate").val("")
	$("#sellPurchaseRate").val("");
	$("#sellSellRate").val("")
	$("#sellItems").removeClass("alert-danger");
	$("#sellBatchInfo").hide().empty();   // P10 (slice 54): FEFO batch/expiry shown when an item is picked
	syncSellNoticeRow();
	$("#sellSellableInfo").hide().empty();   // sellable/expired badge, refreshed on each item pick
				syncSellNoticeRow();
	$("pdt").html("      ");
	
    // M4e.1b (slice 98): the picker value is a productId now â†’ pre-fill from productStock (on-hand + price + FEFO
    // batches + description, by productId) instead of getStock(itemId).
    // U3: fetch this product's pack rules ONCE (cached per product) so the unit toggle and the live
    // per-piece hint can appear. Fire-and-forget: a failure leaves the line pack-only, exactly as today.
    if (window.LooseSell) LooseSell.onProductPicked(Number($('#sellItemDD').val()) || null);
    var catalogSellPrice = $("#"+(tableV?tableV.toLowerCase():'')+"ItemDD :selected").attr('data-price');
		// Fill the sell rate IMMEDIATELY from the catalog price (data-price on the option), independent of the async
		// on-hand/batch fetch below — so it shows on select even if /productStock is slow or unavailable.
		if (catalogSellPrice != null && catalogSellPrice !== '') {
			if (tableV=="Purchase") $("#purchaseSellRate").val(catalogSellPrice);
			else if (tableV=="Sell") $("#sellSellRate").val(catalogSellPrice);
		}
	    /*
	     * #22 — NON-BLOCKING. This fires on EVERY item selection, and it was a plain $.get, so the global
	     * overlay covered the till on every line a cashier rang. Behind it sat three chained cross-service
	     * calls, which is why picking an item could cost the better part of a second.
	     *
	     * Safe to background because the rate the cashier needs is ALREADY on screen: the block above fills
	     * it from the picker's own data-price before this request is even sent. What arrives here is on-hand
	     * and batch detail, which refine the line rather than gate typing it.
	     */
	    bgJson(serverContext+ "productStock?productId="+value,function(data){
    	if(data){
	    	discountValue = data.bsellDiscount;
	    	discountType = data.bsellDiscountType;
	    	batchStock = data.stock;
    		if(value && tableV=="Purchase"){
        		$("#discountTypeDD").val(discountType);    			
    			$("#purchaseDiscount").val(discountValue);//*1>0?$("#bpurchaseDiscount").val():0;
		    	$("#purchasePurchaseRate").val(data.bpurchaseRate);
		    	// $("#purchase SellRate").val((data.bsellRate!=null && data.bsellRate!=='') ? data.bsellRate : (catalogSellPrice||''))
				$("#purchaseSellRate").val((catalogSellPrice!=null && catalogSellPrice!=='') ? catalogSellPrice : (data.bsellRate||''))
		    	if($("#purchaseQuantity").val()*1<=0){
		    		$("#purchaseQuantity").val(1);
		    	}
		    	$("#purchaseItemDesc").val(data.idesc);
		    	$("#pdt").html(discountType+" Discount");
		    	calculateNetPurchase();
    		}else if(value && tableV=="Sell"){
        		// Guard: the type select only has "0" (amount) / "1" (percent). If the pre-fill value is anything
        		// else (e.g. legacy "%", empty, undefined) it wouldn't match an option â†’ selectedIndex -1 â†’ the
        		// field is omitted from the submission â†’ backend defaults to percent. Normalise "%"â†’"1", else "0",
        		// and refresh the selectpicker so a real option is always selected.
        		var sdt = (discountType == "1" || discountType == "%") ? "1" : "0";
        		$("#sellDiscountTypeDD").val(sdt);
        		if($("#sellDiscountTypeDD").data('selectpicker')) $("#sellDiscountTypeDD").selectpicker('refresh');
	    		// #23: blocking here is OPT-IN. Off (default) the count is shown for information and the
	    		// cashier carries on — refusing would not stop goods already on the counter leaving, it would
	    		// only stop them being recorded. resetBSDD() in particular threw away the entry mid-sale.
	    		if(batchStock <= 0 && window.posValidateStockOnSelect === true){
	    			$("#sellItems").addClass("alert-danger");
 	    			showFormError(t('ui.js.noStockAvailablePleasePurchaseThisItem'));
	    			resetBSDD('sellItemDD');
	    			return false;
	    		}else{
		    		$("#sellStock").val(batchStock);
		    		$("#sellItemDesc").val(data.desc);
			    	$("#bexpDate").val(data.bexpDate);
		    		$("#sellPurchaseRate").val(data.bpurchaseRate);
			    	// $("#sellSellRate").val((data.bsellRate!=null && data.bsellRate!=='') ? data.bsellRate : (catalogSellPrice||''))
					$("#sellSellRate").val((catalogSellPrice!=null && catalogSellPrice!=='') ? catalogSellPrice : (data.bsellRate||''))
					// B2B-P2-UI: remember what WE put in the box, then ask what this buyer actually pays. The
					// catalog price stands until the quote answers, so the line is usable immediately and a
					// pricing outage degrades to today's behaviour rather than blocking the sale.
					window._sellAutoRate = Number($("#sellSellRate").val());
					quoteSellFormPrice(value);
			    	$("#sellDiscount").val(discountValue);
			    	if($("#sellItems").val()*1<=0){
			    		// Per-tenant starting quantity: 1 at a retail counter, a carton size for a
			    		// wholesaler. Absent/invalid config falls back to 1 (posSettingInt guards it).
			    		$("#sellItems").val(window.posDefaultQty || 1);
			    	}
			    	$("#sellItemDesc").val(data.idesc);
			    	renderSellBatches(data.batches);   // P10: show the FEFO batch/expiry being dispensed
			    	calculateNetSell();
			    	// Sellable guard: re-key the qty guard to SELLABLE stock (non-expired, non-held) — what a sale can
			    	// actually reserve — so the cashier can't over-sell into expired/held stock the server would reject.
			    	// Also surfaces "Sellable: N (+ expired)" on the form.
			    	// #22/#23 — background. This is a SECOND round trip chained after /productStock, on the
			    	// hot path, and with blocking checks now opt-in its only job is to fill an informational
			    	// badge. It must never hold the till.
			    	bgJson(serverContext+"productSellable?productId="+value, function(sd){
			    		var sellable = (sd && sd.success && sd.sellable!=null) ? Number(sd.sellable) : Number(batchStock||0);
			    		var expired  = (sd && sd.success && sd.expired!=null)  ? Number(sd.expired)  : 0;
			    		batchStock = sellable;
			    		$("#sellStock").val(sellable);
			    		var badge = 'Sellable: <b>'+sellable+'</b>';
			    		if(expired>0) badge += ' <span class="label label-danger" title="expired stock is not sellable">'+expired+' expired</span>';
			    		$("#sellSellableInfo").html(badge).show();
				syncSellNoticeRow();
			    		// #23: the badge above still SAYS "Sellable: 0 (+2 expired)" — the cashier is told.
			    		// What is opt-in is refusing the line and discarding their selection.
			    		if(sellable <= 0 && window.posValidateStockOnSelect === true){
			    			$("#sellItems").addClass("alert-danger");
			    			showFormError(expired>0 ? t('ui.js.allStockExpired') : t('ui.js.noSellableStock'));
			    			resetBSDD('sellItemDD');
			    			$("#sellSellableInfo").hide();
				syncSellNoticeRow();
			    			return;
			    		}
			    		calculateNetSell();   // re-run the qty guard against sellable
			    	});
	    		}
    		}
    	}
    })
	.fail(function(data) {
		console.log(data);
	});
}

// P10 (slice 54): render the FEFO batch/expiry the next sale/dispense will draw from. First batch = next dispensed.
function renderSellBatches(batches){
	var el = $("#sellBatchInfo");
	if(!el.length) return;
	if(!batches || !batches.length){ el.hide().empty(); return; }
	var first = batches[0];
	var exp = first.expiryDate ? (' • Exp ' + first.expiryDate) : '';
	var more = batches.length > 1 ? (' <span class="text-muted">(+' + (batches.length-1) + ' more)</span>') : '';
	el.html('<span class="glyphicon glyphicon-barcode"></span> FEFO: Batch <b>' + escHtml(first.batchNo || 'n/a') + '</b>' + escHtml(exp) + more).show();
	syncSellNoticeRow();
}

function getBatchesByItem(itemId){
	 if (!itemId || itemId == '' || itemId.length <= 0){
		 return
	 }
	//  loadBSDD("getBatchesByItem?itemId="+itemId,tableV.to+'itemBatchDD');
}

//"getBatchesByItem(this.value);"
function getStockByBatch(batchNo){
	$("#"+tableV.toLowerCase()+'BatchNo').val('');
	 if (!batchNo || batchNo == '' || batchNo.length <= 0){
		 return
	 }else if(batchNo*ONE === 0){
		var purchaseItemDD = document.getElementById("purchaseItemDD");
		var itemId = purchaseItemDD.options[purchaseItemDD.selectedIndex].value;		
		var now = new Date();
    	$("#"+tableV.toLowerCase()+'BatchNo').val(itemId+""+now.getMonth()+""+now.getDate()+""+now.getFullYear());
    	return;
	 } else {
	    	
		$("#"+tableV.toLowerCase()+'BatchNo').val(batchNo);
		bpurchaseDiscount: 0
		bpurchaseDiscountType: "%"
		bpurchaseRate: 0
		bsellDiscount: 0
		bsellDiscountType: "%"
		bsellRate: 0
		edit = false;
		$("#purchasePurchaseRate").val("");
		$("#purchaseSellRate").val("")
		$("#sellPurchaseRate").val("");
		$("#sellSellRate").val("")
		$("#sellItems").removeClass("alert-danger");
		$("pdt").html("      ");
		// M4e.1b (slice 98): the picker value is a productId now â†’ ask the server by productId (inventory batches +
		// catalog master), no itemId/ItemCatalogMap lookup.
		var itemDDForBatch = document.getElementById(tableV.toLowerCase()+"ItemDD");
		var productIdForBatch = itemDDForBatch ? itemDDForBatch.options[itemDDForBatch.selectedIndex].value : '';
		$.get(serverContext+ "getStockByBatch?batchNo="+batchNo+"&productId="+productIdForBatch,function(data){
	    	if(data){
		    	discountValue = data.bsellDiscount;
		    	discountType = data.bsellDiscountType;
		    	batchStock = data.stock;
	    		if(tableV=="Purchase"){
	        		$("#discountTypeDD").val(discountType);    			
	    			$("#purchaseDiscount").val(discountValue);//*1>0?$("#bpurchaseDiscount").val():0;
			    	$("#purchasePurchaseRate").val(data.bpurchaseRate);
			    	$("#purchaseSellRate").val(data.bsellRate)
			    	if($("#purchaseQuantity").val()*1<=0){
			    		$("#purchaseQuantity").val(1);
			    	}
			    	// $("#purchaseItemDesc").val(data.idesc);
			    	$("#pdt").html(discountType+" Discount");
			    	calculateNetPurchase();
	    		}else if(tableV=="Sell"){
	        		// Same guard as above: normalise the discount type to a real option ("0" amount / "1" percent)
	        		// so it's never omitted from the submission (which made the backend default to percent).
	        		var sdt2 = (discountType == "1" || discountType == "%") ? "1" : "0";
	        		$("#sellDiscountTypeDD").val(sdt2);
	        		if($("#sellDiscountTypeDD").data('selectpicker')) $("#sellDiscountTypeDD").selectpicker('refresh');
		    		// #23: same rule as the sell path above — opt-in, and off by default.
		    		if(batchStock <= 0 && window.posValidateStockOnSelect === true){
		    			$("#sellItems").addClass("alert-danger");
 		    			showFormError(t('ui.js.noStockAvailablePleasePurchaseThisItem'));
		    			resetBSDD('sellItemDD');
		    			return false;
		    		}else{
			    		$("#sellStock").val(batchStock);
			    		// $("#sellItemDesc").val(data.desc);
				    	$("#bexpDate").val(data.bexpDate);
			    		$("#sellPurchaseRate").val(data.bpurchaseRate);
				    	$("#sellSellRate").val(data.bsellRate)
				    	$("#sellDiscount").val(discountValue);
				    	if($("#sellItems").val()*1<=0){
				    		$("#sellItems").val(1);
				    	}
				    	// $("#sellItemDesc").val(data.idesc);
				    	calculateNetSell();
		    		}
	    		}
	    	}
	    })
		.fail(function(data) {
			console.log(data);
		});
	 }
}

// "Stock In Hand" on the purchase form = the selected product's LIVE inventory on-hand — NOT the purchased
// quantity. On a fresh add, loadStock already fills it (batchStock from /productStock). On EDIT the generic
// editRecord() no longer has a stock column to copy, so it calls this to fetch on-hand for the edited product.
function refreshPurchaseOnHand(){
	var dd = document.getElementById("purchaseItemDD");
	var pid = dd ? dd.value : '';
	if(!pid || pid === 'default'){ $("#purchaseStock").val(''); window.purchaseEdit = null; return; }
	// #22 — background: this fills a READ-ONLY display field, and P6 rapid entry re-selects a product on
	// every saved line, so a blocking overlay here interrupts the operator once per bill line.
	bgJson(serverContext + "productStock?productId=" + encodeURIComponent(pid), function(data){
		var onHand = (data && data.stock != null) ? Number(data.stock) : 0;
		batchStock = onHand;
		// In EDIT mode the live on-hand ALREADY includes this purchase's old qty, so the on-hand that will
		// RESULT after saving = onHand - oldQty + newQty. Store the baseline so the quantity onchange previews it.
		var isEdit = ($("#purchaseId").val()*1 > 0);
		window.purchaseEdit = isEdit ? { base: onHand, oldQty: ($("#purchaseQuantity").val()*1 || 0) } : null;
		updatePurchaseProjectedOnHand();
	});
}

// Live "Stock In Hand" preview. EDIT: the on-hand that will result after saving (base − oldQty + newQty), so
// changing 9â†’5 on a 140 on-hand previews 136. ADD: just mirrors the current live on-hand.
function updatePurchaseProjectedOnHand(){
	// Only treat as edit when a purchase is actually loaded (#purchaseId set) — guards a stale purchaseEdit
	// left over from a previous edit when the user starts a NEW purchase.
	if(($("#purchaseId").val()*1 > 0) && window.purchaseEdit){
		var newQty = $("#purchaseQuantity").val()*1 || 0;
		var projected = window.purchaseEdit.base - window.purchaseEdit.oldQty + newQty;
		$("#purchaseStock").val(projected);
	}else{
		$("#purchaseStock").val(batchStock);
	}
}

/**
 * U5 — the buyer says whether they are counting PACKS or BOXES.
 *
 * The conversion itself happens on the SERVER (PurchaseService.convertBoxesToPacks); this only shows the
 * buyer what will be stored, before they save, so a wrong box size is caught by the person who knows the
 * answer. Nothing here decides anything.
 */
function setPurchaseUnit(unit){
	var box = (unit === 'BOX');
	$('#purchaseUnit').val(box ? 'BOX' : 'PACK');
	$('#purchaseUnitBox').toggleClass('active', box);
	$('#purchaseUnitPack').toggleClass('active', !box);
	$('#purchasePpbWrap').toggle(box);
	if(!box){
		// Leaving box mode clears the factor, so a stale number can never ride along with a pack purchase.
		$('#purchasePacksPerBox').val('');
	}
	calculateNetPurchase();
}

/** "10 boxes = 100 packs · 100.00 per pack · 10,000.00" — or hidden, on an ordinary purchase. */
function renderPurchaseBoxHint(){
	var $hint = $('#purchaseBoxHint');
	if(!$hint.length) return;
	if($('#purchaseUnit').val() !== 'BOX'){ $hint.hide().empty(); return; }

	var boxes = Number($('#purchaseQuantity').val());
	var ppb   = Number($('#purchasePacksPerBox').val());
	var cost  = Number($('#purchasePurchaseRate').val());
	if(!(boxes > 0) || !(ppb > 0)){
		$hint.text(t('ui.js.boxesNeedPacksPerBox')).show();
		return;
	}
	var packs = boxes * ppb;
	// Math.ceil to the paisa — the SERVER rounds a cost UP so it can never be understated, and a hint that
	// rounded the other way would promise a lower cost than the one actually recorded.
	var perPack = cost > 0 ? Math.ceil((cost / ppb) * 100) / 100 : 0;
	$hint.text(boxes + ' × ' + ppb + ' = ' + packs + ' · ' + perPack.toFixed(2) + ' · '
			+ (boxes * cost).toFixed(2)).show();
}

function calculateNetPurchase(){
	var p = $("#purchasePurchaseRate").val()*ONE;
	var s= $("#purchaseSellRate").val()*ONE;
	var qty= $("#purchaseQuantity").val()*ONE;
	renderPurchaseBoxHint();
	discountType = $("#discountTypeDD :selected").val();
	var purchaseDiscount = $("#purchaseDiscount").val()*1>0?$("#purchaseDiscount").val()*ONE:0;
	var purchaseTotalAmount = $($("#purchaseTotalAmount").val(parseFloat(qty * p).toFixed(2))).val();
	updatePurchaseProjectedOnHand();   // "Stock In Hand" = live on-hand (add) / projected after-save on-hand (edit)
	if(discountType == "%"){
		//Discount  =  List Price × Discount Rate 
		purchaseDiscount = purchaseTotalAmount * (purchaseDiscount*1 / 100);
	}else{
		purchaseDiscount = purchaseDiscount * qty;
	}
	if(s>0){
		$("#purchaseNetAmount").val(parseFloat((qty * s - purchaseTotalAmount) + purchaseDiscount).toFixed(2));
	}else{
		$("#purchaseNetAmount").val(0);
	}
}

/**
 * The arithmetic of one sale line, in one place.
 *
 * Extracted from calculateNetSell so re-pricing a cart line (B2B-P2-UI: the customer is chosen AFTER items
 * were added, so their contract price arrives late) uses the SAME maths the form does, instead of a second
 * copy that drifts. Behaviour is unchanged — the rounding order below is the original's, kept exactly:
 * the gross is fixed to 2dp FIRST and the percentage discount is taken off that string, so a 5% discount on
 * 99.99 cannot leak 3-4 decimals into the receivable.
 *
 * Note `profit` is what the form calls "net amount" — gross − cost − discount. That naming is the form's,
 * and renaming it here would only hide the mismatch.
 */
function sellLineMath(rate, qty, purchaseRate, discountValue, discountTypeValue){
	var s = rate*ONE, p = purchaseRate*ONE, q = qty*1>0 ? qty*ONE : 1;
	var total = parseFloat(q * s).toFixed(2);
	var disc = discountValue*1>0 ? discountValue*ONE : 0;
	if(discountTypeValue*ONE == 1){
		disc = parseFloat(total * (disc*1 / 100)).toFixed(2)*ONE;
	}else{
		disc = parseFloat(disc).toFixed(2)*ONE;
	}
	// Clamp: the discount can never exceed the line total (a >100% or oversized amount would
	// otherwise produce a negative receivable).
	if(disc > total*ONE) disc = total*ONE;
	return {
		total: total,                                                        // gross, qty x rate
		discount: disc,
		profit: parseFloat(total - (p*q) - disc).toFixed(2),                 // the form's "net amount"
		receivable: parseFloat(total - disc).toFixed(2)                      // what the line adds to the bill
	};
}

function calculateNetSell(){
	var p = $("#sellPurchaseRate").val()*ONE;
	var s= $("#sellSellRate").val()*ONE;
	$("#sellItems").removeClass("alert-danger");
	var qty= $("#sellItems").val()*1>0?$("#sellItems").val()*ONE:1;
	/*
	 * U4 — on a LOOSE line the box holds PIECES and the rate box holds the PACK price, so the maths below
	 * would compute 5 x 120 = 600.00 for five tablets that cost 60.00 — and the stock guard would compare
	 * 5 pieces against an on-hand counted in packs and refuse a sale the shop can make.
	 *
	 * Substituting packs + the effective pack rate mirrors what the server stores, so the cart row, the
	 * running total, the stock check and the invoice all agree. Null on every ordinary line.
	 */
	var looseOv = (window.LooseSell && LooseSell.lineOverride) ? LooseSell.lineOverride() : null;
	if(looseOv){ qty = looseOv.qty; s = looseOv.rate; }
	discountType = $("#sellDiscountTypeDD :selected").val();
	// editing an existing sale â†’ trust the displayed stock; key on editingInvoice, not the shared `edit`
	// global (which resetBSDD flips off after every cart add).
	if(window.editingInvoice){
		batchStock = $("#sellStock").val()*ONE;
	}
	$("#sellStock").val(batchStock);
	/*
	 * #23 — the QUANTITY guard, opt-in like the three selection guards.
	 *
	 * This one is the guard a cashier actually hits: it fires on every keystroke in the quantity box, and it
	 * `return false`s BEFORE the line math, so the totals stop updating too — the line simply refuses to
	 * price itself. At a counter that is the wrong answer: the customer is holding the goods, so the quantity
	 * is a fact being recorded, not a request to be approved.
	 *
	 * Off (default) the line prices normally and the sellable badge still tells the cashier where stock
	 * stands. The server's FEFO reservation at submit remains the real gate.
	 */
	if(batchStock < qty && window.posValidateStockOnSelect === true){
		$("#sellItems").addClass("alert-danger");
 		showFormError(t('ui.js.quantityExceedsAvailableStockPleaseReduceThe'));
		return false;
	}
	var m = sellLineMath(s, qty, p, $("#sellDiscount").val(), discountType);
	sellTotalAmount = m.total;
	$("#sellNetAmount").val(m.profit);
	if(m.profit<=0)
		$("#sellNetAmount").addClass("alert-danger");
	else
		$("#sellNetAmount").removeClass("alert-danger");

	$("#sellTotalAmount").val(m.total);
	$("#sellrm").val(m.receivable);
	// U3: the hint line is live — it redraws on every keystroke in the quantity box, because
	// calculateNetSell is already bound to onkeyup. The cashier sees what they are about to charge
	// BEFORE committing, which is the whole point of the slice.
	if (window.LooseSell) LooseSell.render();
}

// function calculateNetSell(){
// 	var p = $("#sellPurchaseRate").val()*ONE;
// 	var s= $("#sellSellRate").val()*ONE;
// 	$("#sellItems").removeClass("alert-danger");
// 	var qty= $("#sellItems").val()*1>0?$("#sellItems").val()*ONE:1;
// 	discountType = $("#sellDiscountTypeDD :selected").val();
// 	if(edit){
// 		batchStock = $("#sellStock").val()*ONE;
// 	}
// 	$("#sellStock").val(batchStock);

// 	if(batchStock < qty){
// 		$("#sellItems").addClass("alert-danger");
// 		alert("You can not select more item than availabe in stock, Please purchase or select some other item to sell.")
// 		$(".form-control").val("");
// 		return false;
// 	}

// 	var sellDiscount= $("#sellDiscount").val()*1 > 0 ? $("#sellDiscount").val()*ONE : 0;
// 	sellTotalAmount = parseFloat(qty * s).toFixed(2);
// 	if(discountType*ONE == 1){
// 		//Discount  =  List Price × Discount Rate 
// 		sellDiscount =  sellTotalAmount * (sellDiscount*1 / 100);
// 	}else {

// 		sellDiscount = sellTotalAmount - sellDiscount;
// 		//$("#sellDiscount").val(sellDiscount);
// 	}
// 	var profit = parseFloat(sellTotalAmount- (p*qty) - sellDiscount).toFixed(2);
// 	$("#sellNetAmount").val(profit);
// 	if(profit<=0)
// 		$("#sellNetAmount").addClass("alert-danger");
// 	else
// 		$("#sellNetAmount").removeClass("alert-danger");
	
// 	$("#sellTotalAmount").val(sellTotalAmount);
// 	$("#sellrm").val($("#sellTotalAmount").val()-sellDiscount);
// }

function calculateSRP(){
	var s= $("#sellSellRate").val()*ONE;
	if(!s || s<=0){
 		showFormError(t('ui.js.pleaseSelectAValidSoldItemRecord'));
		return false;
	}
	var qty= $("#sellItems").val()*1>0?$("#sellItems").val()*ONE:1;
	var srp= $("#sellsrp").val()*1>0?$("#sellsrp").val()*ONE:0;
	sellTotalAmount = parseFloat(qty * s).toFixed(2);
	var type = $("#srpDD :selected" ).val();
	if(type == "%"){
		srp =  sellTotalAmount * (srp*1 / 100);
	}
	$("#sellReturn").val($("#sellrm").val()*ONE+srp);
}

function calculateChange() {

	var recAm = ($("#sellRec").val() * ONE) || 0;
    // P12 (slice 59): insurance covers part of the bill; the patient only owes the remainder (the co-pay).
    var insured = ($("#sellInsured") && $("#sellInsured").val() ? $("#sellInsured").val() * ONE : 0) || 0;
    var sellTotal = ($("#sellTotal")[0] ? $("#sellTotal")[0].innerHTML * ONE : 0) || 0;
    // SF-1/SF-2: while EDITING, the bill is already partly covered by what was paid before, so the preview must
    // count it: due = bill − (priorPaid + additionalReceived + insured). The server derives the real due the same way.
    var priorPaid = (window.editingInvoice && window.editingPaid) ? Number(window.editingPaid) : 0;
    // SF-5 Model B: applied store credit counts as paid (capped at the bill for the preview; server caps at balance).
    var storeCredit = ($("#sellStoreCredit").val() * ONE) || 0;
    // SF-7: round money to 2 decimals so the on-screen change/due can't show float drift (e.g. 0.30000000004).
    var change = Math.round((recAm + insured + priorPaid + storeCredit - sellTotal) * 100) / 100;

    // sellCh keeps the SIGNED change/due (received − bill) — addSell submits this as customer.dueAmount.
    // Do not change its meaning; the display fields below are derived from it.
    $("#sellCh").val(change);

    // Due (this sale) = positive amount still owed on the current cart (0 when fully paid/overpaid).
    var dueThis = change < 0 ? -change : 0;
    $("#sellDueThis").val(dueThis.toFixed(2));

    /*
     * DUE TODAY vs ON THE PLAN — a display split, never an arithmetic one.
     *
     * `change` above keeps its exact meaning: received − bill, submitted as customer.dueAmount and
     * negative while owing. That is right, and the comment above it says so. On a financed sale the
     * customer really does still owe the financed part; the books are not confused.
     *
     * The CASHIER was. The till showed "40,000 due" on a 45,000 handset with 5,000 down, so the only
     * number on screen was the one they must NOT type into Amount Received. Naming the two amounts
     * separately is the whole fix: 5,000 is what the counter collects now, 40,000 is what the plan will
     * collect later.
     */
    var financedNow = (typeof posFinancedAmount === 'function' ? (posFinancedAmount() || 0) : 0);
    if (financedNow > 0) {
        var dueToday = Math.round((dueThis - financedNow) * 100) / 100;
        $('#sellDueTodayWrap').show();
        $('#sellDueToday').val(dueToday.toFixed(2));
        $('#sellOnPlan').val(financedNow.toFixed(2));
    } else {
        $('#sellDueTodayWrap').hide();
    }

    // Account preview (existing customer only): previous balance + this sale = new total outstanding.
    refreshAccountDuePreview(dueThis);

    // The Due Date only makes sense when the sale leaves a balance: show the WHOLE field group (label + input) and
    // require it when Due (this sale) > 0; hide it entirely when fully paid.
    $('#sellDueDateWrap').toggle(change < 0);
}

// Payment method change. "Credit (on account)" collects nothing now — the whole bill goes on account — so the
// Amount Received field is meaningless: hide it and zero it (Due (this sale) then shows the full amount, and the
// Due Date becomes required via calculateChange). Any other method shows Received again. Change is left as-is.
function onSellPayMethodChange(){
    var isCredit = ($('#sellPayMethod').val() === 'CREDIT');
    if (isCredit) { $('#sellRec').val(''); $('#sellRecWrap').hide(); }
    else { $('#sellRecWrap').show(); }
    calculateChange();
}

// Show the running-balance impact for a known (dropdown-selected) customer. window.selectedCustomerDue
// holds their current outstanding balance; null for a walk-in/manual customer or while editing, in which
// case the account row stays hidden. Re-derives this sale's due if not passed (e.g. on customer select).
/**
 * B2B-P1 (#9): show the customer's limit and what is left of it, and flag an overage before the cashier
 * hits Complete Sale. Purely a HINT — the authoritative check runs server-side at submit, because this
 * page's copy of the balance is as old as the dropdown and another till may have sold to them since.
 * Hidden entirely for a customer with no limit, which is every customer until an owner sets one.
 */
function refreshCreditLimitHint(newTotalDue) {
	var wrap = document.getElementById('sellCreditLimitWrap');
	var availWrap = document.getElementById('sellCreditAvailableWrap');
	if (!wrap || !availWrap) { return; }
	var limit = window.selectedCustomerLimit;
	// Both fields appear and disappear together — an "Available" with no "Limit" beside it means nothing.
	if (limit == null) { wrap.style.display = 'none'; availWrap.style.display = 'none'; return; }
	var available = limit - (Number(newTotalDue) || 0);
	$('#sellCreditLimit').val(limit.toFixed(2));
	$('#sellCreditAvailable').val(available.toFixed(2));
	// Red only when actually over — an "available" of 0 is at the limit, which is allowed.
	$('#sellCreditAvailable').css('background-color', available < 0 ? '#ffd7d7' : '#eaffea');
	wrap.style.display = '';
	availWrap.style.display = '';
}

function refreshAccountDuePreview(dueThis) {
	if (dueThis == null) {
		var recAm = ($("#sellRec").val() * ONE) || 0;
		var sellTotal = ($("#sellTotal")[0] ? $("#sellTotal")[0].innerHTML * ONE : 0) || 0;
		var ch = Math.round((recAm - sellTotal) * 100) / 100;   // SF-7: round money to 2dp
		dueThis = ch < 0 ? -ch : 0;
	}
	var prev = Number(window.selectedCustomerDue);
	if (window.selectedCustomerDue == null || isNaN(prev)) {
		$("#sellAccountRow").hide();
		return;
	}
	$("#sellPrevDue").val(prev.toFixed(2));
	$("#sellNewTotalDue").val((prev + dueThis).toFixed(2));
	$("#sellAccountRow").show();
	refreshCreditLimitHint(prev + dueThis);
}

// function calculateChange(){
// 	// $("#dueDateTemp").css("visibility", "hidden");
// 	$("#dueDateTemp").css("display", "block");
// 	 $('#displayDateWrapper').hide();
// 	var recAm = $("#sellRec").val()*ONE;
// 	var sellTotal = $("#sellTotal")[0].innerHTML*ONE;
// 	$("#sellCh").val(recAm - sellTotal);
// 	if($("#sellCh").val()<0){
// 		// $("#dueDateTemp").css("visibility", "visible");
// 		$("#dueDateTemp").css("display", "none");
// 		// $("#dueDate").placeholder = "Enter Due Days";
// 	}
	
// }

// â”€â”€â”€ Sale Detail Report â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Number/money/escape helpers (function declarations â†’ hoisted, so the DataTable footerCallback
// defined at the top of the file can call srNum/srMoney safely).
function srNum(n){
	n = (typeof n === 'number') ? n : (parseFloat(n) || 0);
	return (Math.round(n * 100) / 100).toLocaleString(undefined, { maximumFractionDigits: 2 });
}
function srMoney(n){
	n = (typeof n === 'number') ? n : (parseFloat(n) || 0);
	return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
// Money for a table cell — blank/absent shows an em dash instead of a misleading 0.00.
function srMoneyCell(v){
	if (v === null || v === undefined || v === '') return '—';
	return srMoney(v);
}
// Always escape user-supplied strings before injecting into DataTables HTML (XSS-safe rendering).
function escSR(s){
	if (typeof escHtml === 'function') return escHtml(s == null ? '' : String(s));
	return (s == null ? '' : String(s)).replace(/[&<>"']/g, function(c){
		return { '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c];
	});
}

// The custom date-range fields (#srsd/#sred) carry class="datetimepicker", so the shared picker
// (/js/common/date-picker.js) already owns them — re-binding a second plugin here is exactly what made date
// fields clear on blur elsewhere. It also removes the reason this function existed: the old eonasdan widget
// mis-initialised when bound to a display:none field, so the pickers had to be re-created each time the
// wrapper became visible. The shared picker builds its calendar on focus and positions it then, so a field
// that starts hidden is a non-issue. Format is unchanged (dd-MM-yyyy HH:mm:ss — what the backend's
// getDateTime() parses for sd/ed). Kept as a hook for the toggle below.
function initSRDatePickers(){
	if (typeof initDatePickers === 'function') initDatePickers();   // pick up any newly rendered field
}
function toggleSRCustomRange(){
	var custom = $('#dateRangeDDSR').val() === '4';
	$('#srStartWrap, #srEndWrap').toggle(custom);
	if (custom){
		initSRDatePickers();
	} else {
		$('#srsd').val(''); $('#sred').val('');
	}
}

// KPI summary — aggregates the line collection. Invoice-level figures (due) are counted once per
// distinct invoice so multiple lines on the same invoice don't double-count.
function renderSRKpis(rows){
	var gross = 0, tax = 0, qty = 0, invoices = {}, dueByInv = {};
	rows.forEach(function(o){
		gross += parseFloat(o.totalAmount) || 0;
		tax   += parseFloat(o.taxAmount)   || 0;
		qty   += parseFloat(o.quantity)    || 0;
		var inv = o.invoiceNo || ('#' + (o.sellId || ''));
		invoices[inv] = true;
		if (!(inv in dueByInv)){
			var d = parseFloat(o.dueAmount);
			dueByInv[inv] = (!isNaN(d) && d < 0) ? -d : 0;   // dueAmount<0 = customer still owes
		}
	});
	var due = 0;
	Object.keys(dueByInv).forEach(function(k){ due += dueByInv[k]; });
	$('#srkGross').text(srMoney(gross));
	$('#srkTax').text(srMoney(tax));
	$('#srkBilled').text(srMoney(gross + tax));
	$('#srkItems').text(srNum(qty));
	$('#srkInvoices').text(Object.keys(invoices).length);
	$('#srkDue').text(srMoney(due));
	$('#srKpis').css('display', 'grid');
}

/**
 * B2B-P3e-1 (#6): mount the SHARED filter rail on the sale report, once.
 * The export href always carries the current filters, so the file matches the screen.
 */
/**
 * B2B-P3e-2 (#6): render the subtotals the server aggregated. Shown ABOVE the detail table, because the
 * grouped view is the answer and the detail is the evidence. Hidden entirely when nothing is grouped.
 */
/**
 * #17 P3 — " + 1 free", or nothing at all.
 *
 * ONE definition, used by every place a sold quantity is displayed: the sale grid, the Sale Detail Report and
 * the printed document. Free goods leave the shelf and carry cost, so a screen showing 10 for a sale that
 * issued 11 understates what was supplied — and three screens each deciding that for themselves is how two of
 * them end up disagreeing.
 *
 * Returns '' on an ordinary line, so the overwhelming majority of rows render exactly as they always did.
 */
function bonusSuffix(o){
	var bonus = Number(o && o.bonusQuantity);
	if (!bonus || bonus <= 0) return '';
	return ' + ' + bonus + ' ' + escHtml(t('ui.js.free'));
}

/** The quantity a Sale Detail Report row shows, free goods included. */
function srQtyText(o){
	var base = o.soldUnit ? looseQtyText(o) : srNum(o.quantity);
	return base + bonusSuffix(o);
}

function renderSRGroups(groups){
	var host = document.getElementById('srGroups');
	if (!host) return;
	if (!groups || !groups.length) { host.style.display = 'none'; host.innerHTML = ''; return; }
	var h = '<table class="table table-striped" style="width:100%"><thead><tr>'
		+ '<th>' + escHtml(t('ui.js.groupBy')) + '</th>'
		+ '<th class="text-right">' + escHtml(t('ui.js.invoices')) + '</th>'
		+ '<th class="text-right">' + escHtml(t('ui.js.qty2')) + '</th>'
		+ '<th class="text-right">' + escHtml(t('ui.js.total')) + '</th>'
		+ '<th class="text-right">' + escHtml(t('ui.js.taxAmount')) + '</th>'
		+ '<th class="text-right">' + escHtml(t('ui.js.grossSales')) + '</th></tr></thead><tbody>';
	groups.forEach(function(g){
		h += '<tr><td>' + escHtml(g.label || '') + '</td>'
			+ '<td class="text-right">' + (g.invoices != null ? g.invoices : '') + '</td>'
			+ '<td class="text-right">' + escHtml(g.soldUnit ? looseQtyText(g)
					: (g.quantity != null ? String(Number(g.quantity)) : '')) + '</td>'
			+ '<td class="text-right">' + (g.total != null ? Number(g.total).toFixed(2) : '') + '</td>'
			+ '<td class="text-right">' + (g.tax != null ? Number(g.tax).toFixed(2) : '') + '</td>'
			+ '<td class="text-right"><b>' + (g.gross != null ? Number(g.gross).toFixed(2) : '') + '</b></td></tr>';
	});
	host.innerHTML = h + '</tbody></table>';
	host.style.display = '';
}

function mountSRFilters(){
	if (window.srFilters || typeof mountReportFilters !== 'function') return;
	window.srFilters = mountReportFilters({
		container : 'srFilterRail',
		dimensions: ['groupBy','customer','product','category','company','channel'],
		onApply   : function(){ loadSR(); },
		exportUrl : function(v){
			var q = 'rp=' + encodeURIComponent($('#dateRangeDDSR').val() || '0')
				+ '&sd=' + encodeURIComponent($('#srsd').val() || '')
				+ '&ed=' + encodeURIComponent($('#sred').val() || '')
				+ '&customerId=' + encodeURIComponent(v.customerId || '')
				+ '&productId=' + encodeURIComponent(v.productId || '')
				+ '&category=' + encodeURIComponent(v.category || '')
				+ '&manufacturer=' + encodeURIComponent(v.manufacturer || '')
				+ '&customerType=' + encodeURIComponent(v.customerType || '')
				+ '&groupBy=' + encodeURIComponent(v.groupBy || '');
			return serverContext + 'saleReport.csv?' + q;
		}
	});
}

function loadSR(){
	mountSRFilters();
	tableSellReport.clear().draw();
	$('#srKpis').hide();
	clearFormError();
	// Self-contained params (the report bypasses the shared form-scan machinery): rp = period,
	// sd/ed = custom range. Backend contract unchanged.
	/*
	 * Default to the CURRENT MONTH (0) when the control has not answered.
	 *
	 * `.val()` yields undefined if the select is not in the DOM yet — the report can be opened from a nav
	 * entry, a deep link or a back-button restore, and the screen is not always built by then. jQuery then
	 * omits the field, `rp` binds as null on the server, and what the operator gets is either a bare error or
	 * "no sales found" for a shop that has plenty.
	 *
	 * The dropdown ships with "Current month" selected, so sending 0 is the same question the screen appears
	 * to be asking. The server defaults the same way, deliberately: neither side should depend on the other
	 * remembering.
	 */
	var rp = $('#dateRangeDDSR').val();
	if (rp === undefined || rp === null || rp === '') rp = '0';
	var sd = $('#srsd').val() || '';
	var ed = $('#sred').val() || '';
	if (rp === '4' && !sd && !ed){
		showFormError(t('ui.js.pleasePickAStartAndOrEnd'));
		return;
	}
	$.ajax({
		type : "POST",
		url : serverContext + "loadSR",
		dataType : "json",
		// B2B-P3e-1 (#6): the rail's values use the SAME names the backend binds, so they pass straight
		// through. Absent = today's report, unchanged.
		data : $.extend({ rp: rp, sd: sd, ed: ed }, (window.srFilters ? window.srFilters.values() : {})),
		success : function(data) {
			if(data.status!=="SUCCESS"){
				showFormError((data.status || '') + (data.message ? ': ' + data.message : ''));
				return;
			}
			var rows = (data && data.collection) ? data.collection : [];
			if(!rows.length){
				showFormError(t('ui.js.noSalesFoundForTheSelectedPeriod'));
				return;
			}
			clearFormError();
			if (window.srFilters) window.srFilters.categoriesFrom(rows);   // B2B-P3e-1: real categories only
			if (window.srFilters) window.srFilters.companiesFrom(rows);    // #18: real companies only
			renderSRGroups(data.object);   // B2B-P3e-2 (#6): subtotals, from the same response
			rows.forEach(function(o){
				var product = escSR((o.itemCode ? o.itemCode + ' — ' : '') + (o.itemName || ''));
				var dueRaw  = parseFloat(o.dueAmount);
				var owed    = (!isNaN(dueRaw) && dueRaw < 0) ? -dueRaw : 0;
				var dueCell = owed > 0
					? '<span class="sr-due-owing">' + srMoney(owed) + '</span>'
					: '<span class="sr-due-clear">Paid</span>';
				tableSellReport.row.add([
					escSR(o.dated || ''),
					'<span class="sr-inv">' + escSR(o.invoiceNo || '—') + '</span>',
					product,
					// U4: pieces on a loose line, packs on every other. The row's money is untouched.
					// #17 P3: free goods are shown here too. They left the shelf and carry cost, so a report
					// that says 10 for a sale that issued 11 understates what was supplied — the same reason
					// the receipt now prints "10 + 1 free". The money columns are unaffected: a bonus unit is
					// not charged, so revenue and tax stay exactly as they were.
					srQtyText(o),
					srMoneyCell(o.catalogPrice),
					srMoneyCell(o.sellRate),
					srMoneyCell(o.totalAmount),
					srMoneyCell(o.taxAmount),
					srMoneyCell(o.netAmount),
					escSR(o.cn || ''),
					escSR(o.cc || ''),
					escSR(o.paymentMode || '—'),
					dueCell,
					// SF-10: per-line margin = net revenue minus COGS (cost x qty); blank when no cost captured.
					((!isNaN(parseFloat(o.costPrice)) && !isNaN(parseFloat(o.netAmount)) && !isNaN(parseFloat(o.quantity)))
						? srMoneyCell(parseFloat(o.netAmount) - parseFloat(o.costPrice) * parseFloat(o.quantity)) : '')
				]);
			});
			tableSellReport.draw();
			renderSRKpis(rows);
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
        	handleAjaxFailure(data, errorThrown, "loadSR");   // was: unconditional redirect to /login
        }
	});
}

function resetPurchaseForm(){
	resetBSDD('purchaseItemDD');
	// B2B-P0 (#8): the vendor dues row is driven by the vendor SELECT's change event, so a native form reset
	// clears the number but leaves the row on screen — an empty "Outstanding due" against no vendor at all.
	// Hide it with the rest of the form; it reappears the moment a vendor is picked.
	var wrap = document.getElementById('purchaseVendorDuesWrap');
	var box = document.getElementById('purchaseVendorDues');
	if (wrap) wrap.style.display = 'none';
	if (box) box.value = '';
}

// Toolbar "+ New Purchase" â†’ open the form modal fresh (mirrors newProduct/newEntity, but also
// visually resets the bootstrap-select item picker which the generic resetForm() leaves stale).
function newPurchase(){
	resetForm();
	resetPurchaseForm();
	assertPurchaseFieldsAccounted();   // names any field that would silently go sticky
	$('#purchaseId').val('');
	$('#PurchaseModalTitle').text('New Purchase');
	purchaseAddAnother = false;   // P6: a fresh bill starts with no pending "add another" intent
	purchaseLineCount = 0;
	$('#purchaseLineCount').hide().text('');
	refreshPurchaseTaxRow();   // Phase B: show the tax field only when the org enabled "Purchase tax"
	openModal('PurchaseModal');
}

/* ------------------------------------------------------------------------------------------------
 * P6 — rapid purchase line entry.
 *
 * A register (Company, Vendor, Product) creates ONE record, so the shared post-save handler wipes the
 * form and closes the modal. A purchase is not that shape: one delivery is one vendor, one invoice and
 * one date with MANY items, each saved as its own Purchase row. Inheriting the register behaviour meant
 * a 30-line delivery cost 30 modal opens and 30 retyped headers.
 *
 * "Save & Add Another" keeps the modal open and clears only the LINE fields. #addPurchase ("Save &
 * Close") is untouched and still runs the generic path, so single-line entry behaves exactly as before.
 * ---------------------------------------------------------------------------------------------- */
var purchaseAddAnother = false;   // which button submitted the form (consumed once, in afterSavePurchase)
var purchaseLineCount  = 0;       // lines saved onto the bill currently open

// Fields cleared between lines, listed explicitly.
//
// ⚠ NOTE ON THE DIRECTION, corrected 2026-08-17. An earlier comment here claimed this list was the
// safer choice because "a field added later will fail by NOT clearing (a visible annoyance) instead
// of by silently going sticky". Those are the same outcome: a field this list does not name is
// retained, which IS going sticky. The default here is therefore the UNSAFE one, and the proof is a
// line below — `purchasePaid` had to be added by hand precisely to stop a payment repeating per line.
//
// The register screens (Customer/Vender/Company/Product) use the opposite polarity — clear everything
// except a declared keep-list, so a new field starts out CLEARED. See registerAddAnother() in
// crud-modal.js. This list is not flipped to match because the purchase flow is gated by 28 Cypress
// cases and has no live defect (every field on the form today is accounted for); instead
// assertPurchaseFieldsAccounted() below makes an unaccounted field announce itself.
/* ── SER-3c: a serialled PURCHASE line is one unit ───────────────────────────────────────────────
 *
 * The mirror of applySerialQuantityLock() on the sale side, and it exists for the same reason: a serial
 * number identifies ONE physical thing, so the moment one is entered the quantity is 1 and there is nothing
 * for the operator to decide.
 *
 * The server already enforced it from the other direction — SerialUnitService.validateForPurchase refuses a
 * line whose serial COUNT does not equal its quantity, because "a purchase of three handsets with two IMEIs
 * means one unit is unaccounted for, and the shop would never find out which". Before this, goods-in met
 * that rule as a REFUSAL after the whole receipt had been typed.
 *
 * ⚠ READONLY, NOT DISABLED. #purchaseQuantity carries name="quantity" and is form-serialized, and a disabled
 * input is dropped by FormData and by jQuery serialize — so `disabled` would post a purchase line with NO
 * quantity, recording a receipt of zero units or failing for a reason nothing on the screen could explain.
 * That is the silent-drop shape this codebase has already paid for twice (the bonus column, the GL outbox
 * fields). `readonly` cannot be typed into, looks the same, and still submits.
 *
 * Ten handsets are now ten lines, which "Save & Add Another" (P6) turns into scan-and-repeat.
 */
/**
 * SER-2 (fix) — hide the purchase grid's capability-gated columns THROUGH THE DATATABLES API.
 *
 * `data-capability` cannot do this job. capabilities.js applies `.cap-off{display:none!important}` to the
 * element it finds, and on a `<th>` that hides the header cell alone: the body cells stay, every later
 * header slides one column left, and the grid then labels the wrong data — P/U Price over the batch,
 * Total over P/U Price, and so on down the row. Batch # carried the attribute and did exactly that for
 * every tenant without batchTracking, which is most of them.
 *
 * `column().visible(false)` drops the header and its cells together, which is the only way a table column
 * can be hidden without lying about the ones beside it.
 *
 * Column order (see the <thead>): 6 = Batch #, 7 = Serial / IMEI.
 *
 * Fails OPEN via hasCapability(), matching capabilities.js: an unknown code shows the column, because a
 * tenant mid-upgrade must not lose a column the server is still sending data for.
 */
/**
 * SER-2 (fix) — ask the register a question from the SCREEN.
 *
 * `/serialHistory` and `/serialUnits` shipped with the register and no caller ever reached them from a page,
 * so the only way to look a handset up was to type the URL. Someone did — with a BILL number, which the
 * server could not match, and the empty answer was reasonably read as "the purchase lost the serial".
 *
 * One box, three questions. The server tries serial, then sale invoice, then purchase bill, and stamps
 * `matchedBy` on every row, so a result never leaves the reader guessing which of the three it answered.
 *
 * Renders through escHtml(): every value here — serial, invoice, bill — is operator input that has been
 * round-tripped through the database, which is precisely the shape of a stored XSS.
 */
function lookupSerialUnit(){
	var q = $.trim($('#serialLookup').val() || '');
	var $msg = $('#serialLookupMsg'), $out = $('#serialLookupResult');
	$out.empty(); $msg.text('').css('color', '');
	if (q === '') { $('#serialLookup').focus(); return; }

	var t = (typeof window.t === 'function') ? window.t : function(k){ return k; };
	$.get(serverContext + 'serialHistory', { serial: q }, function(resp){
		var rows = (resp && resp.collection) ? resp.collection : [];
		if (!rows.length) {
			$msg.text(t('ui.js.serialLookupNone')).css('color', '#c0392b');
			return;
		}
		$msg.text(rows.length + ' ' + t('ui.js.serialLookupFound')).css('color', '#0f6e56');

		var html = '<table class="table table-bordered table-condensed" style="margin-bottom:0">'
			+ '<thead><tr>'
			+ '<th>' + escHtml(t('ui.js.serialNumbers')) + '</th>'
			+ '<th>' + escHtml(t('ui.js.condition')) + '</th>'
			+ '<th>' + escHtml(t('ui.js.serialStatus')) + '</th>'
			+ '<th>' + escHtml(t('ui.js.serialReceivedOn')) + '</th>'
			+ '<th>' + escHtml(t('ui.js.serialSoldOn')) + '</th>'
			+ '</tr></thead><tbody>';
		$.each(rows, function(i, r){
			// IN_STOCK is the state a shop acts on, so it is the one that gets a colour.
			var inStock = (r.status === 'IN_STOCK');
			html += '<tr>'
				+ '<td><strong>' + escHtml(r.serialNo || '') + '</strong></td>'
				+ '<td>' + escHtml(r.conditionGrade || '') + '</td>'
				+ '<td style="color:' + (inStock ? '#0f6e56' : '#727374') + '">'
				+ escHtml(r.status || '') + '</td>'
				+ '<td>' + escHtml(r.purchaseInvoiceNo || '') + '</td>'
				+ '<td>' + escHtml(r.invoiceNo || '') + '</td>'
				+ '</tr>';
		});
		$out.html(html + '</tbody></table>');
	}, 'json').fail(function(){
		$msg.text(t('ui.js.serialLookupNone')).css('color', '#c0392b');
	});
}

function applyPurchaseColumnCapabilities(dt){
	if (!dt) return;
	var can = (typeof hasCapability === 'function') ? hasCapability : function(){ return true; };
	try {
		// `false` as the second argument defers the redraw, so two column changes cost one adjust().
		dt.column(6).visible(can('batchTracking'), false);
		dt.column(7).visible(can('serialTracking'), false);
		dt.columns.adjust();
	} catch (e) { /* a grid not yet built has no columns to hide */ }
}

function applyPurchaseSerialQuantityLock() {
	var $serial = $('#purchaseSerials');
	var $qty = $('#purchaseQuantity');
	if (!$serial.length || !$qty.length) return;

	var hasSerial = $.trim($serial.val() || '') !== '';
	if (hasSerial) {
		$qty.val(1).prop('readonly', true).addClass('is-locked-by-serial')
			.attr('title', t('ui.js.qtyLockedBySerial', 'One serial number is one unit.'));

		/*
		 * PACK / BOX goes with it — a serialled line CANNOT be a box.
		 *
		 * BOX means "quantity x packsPerBox" (U5): ten boxes of ten is a hundred units. A serial identifies
		 * ONE physical thing, so the two are contradictory by construction — and leaving the toggle on screen
		 * beside a quantity locked at 1 offers the operator a choice whose only outcomes are a refused
		 * receipt or, worse, one unit booked in as a hundred with a single IMEI against it.
		 *
		 * Forced back to PACK through setPurchaseUnit rather than by hiding the buttons: that function also
		 * clears packsPerBox and hides its input and the box hint, so a factor typed a moment ago cannot ride
		 * along invisibly on a purchase that is no longer in box mode.
		 */
		if (typeof setPurchaseUnit === 'function' && $('#purchaseUnit').val() === 'BOX') {
			setPurchaseUnit('PACK');
		}
		$('.purchase-unit-wrap').addClass('is-locked-by-serial').hide();

		// The line total follows the quantity, and nothing else recalculates it for us: the field is
		// readonly now, so the keyup/blur handlers that normally drive calculateNetPurchase() never fire.
		// Without this the operator sees the previous quantity's total against a quantity of 1.
		if (typeof calculateNetPurchase === 'function') calculateNetPurchase();
	} else if ($qty.hasClass('is-locked-by-serial')) {
		// Release only a lock THIS rule applied — a quantity made readonly by anything else is not ours.
		$qty.prop('readonly', false).removeClass('is-locked-by-serial').removeAttr('title');
		// Same rule for the unit toggle: only restore the one this lock hid. A shop whose products are not
		// divisible may have it hidden for its own reasons, and un-hiding that would be inventing a control.
		$('.purchase-unit-wrap.is-locked-by-serial').removeClass('is-locked-by-serial').show();
	}
}

// `input` as well as `change`, so a SCANNER locks the quantity the instant the code lands rather than when
// focus finally leaves the field.
$(document).on('input change', '#purchaseSerials', applyPurchaseSerialQuantityLock);

/** Exposed so the P6 "Save & Add Another" path can release the lock as it clears the line. */
window.applyPurchaseSerialQuantityLock = applyPurchaseSerialQuantityLock;

var PURCHASE_LINE_FIELDS = [
	'purchaseId', 'purchaseItemDesc', 'purchaseQuantity', 'purchasePurchaseRate', 'purchaseSellRate',
	'purchaseBatchNo', 'purchaseExpiry', 'purchaseTaxRate', 'purchaseTotalAmount', 'purchaseNetAmount',
	'purchaseStock',
	// purchasePaid is NOT a header field despite looking like one. Its placeholder is "Blank = paid in
	// full (cash)", so carrying a typed value onto the next line posts that payment again and credits
	// the vendor once per line. Everything else in this list is convenience; this one is money.
	'purchasePaid',
	// SER-3c: a serial identifies ONE unit, so it can never survive onto the next line. It was in
	// neither list before this — assertPurchaseFieldsAccounted() was already warning about it — and
	// with one serial per line a sticky IMEI would register the previous handset against the next.
	'purchaseSerials'
];

// The fields that SURVIVE a line deliberately — the bill header. Declared so the two sets together
// account for the whole form, which is what makes the check below possible.
var PURCHASE_HEADER_FIELDS = [
	'purchaseVenderDD', 'purchaseInvoiceNo', 'purchaseDate', 'purchaseItemDD', 'purchaseVendorDues',
	// SER-3c: condition SURVIVES a line, deliberately — a carton of handsets is one grade, and retyping
	// "NEW" twenty times is the kind of friction that gets a feature switched off. Sticky here is visible
	// on screen and cheap to correct; a sticky SERIAL above is neither, which is why they differ.
	'purchaseCondition'
];

/**
 * Fail LOUDLY when a field is neither cleared nor a declared header field.
 *
 * With a clear-list, adding a field to the form silently makes it sticky, and "sticky" on this form
 * has already meant repeating a payment. This turns that into a named console warning the first time
 * the modal is used, so the omission is discovered while someone is looking at the form rather than
 * from a vendor's statement.
 */
function assertPurchaseFieldsAccounted() {
	var box = document.getElementById('PurchaseModal');
	if (!box || !global_consoleWarnOnce) return;
	var unaccounted = [];
	$(box).find('input, select, textarea').each(function () {
		var id = this.id;
		if (!id || this.type === 'button' || this.type === 'submit' || this.type === 'reset') return;
		if (PURCHASE_LINE_FIELDS.indexOf(id) >= 0 || PURCHASE_HEADER_FIELDS.indexOf(id) >= 0) return;
		unaccounted.push(id);
	});
	if (unaccounted.length) {
		global_consoleWarnOnce = false;
		console.warn('Purchase "Save & Add Another": these fields are neither cleared nor declared '
			+ 'header fields, so they will go STICKY between lines — ' + unaccounted.join(', ')
			+ '. Add each to PURCHASE_LINE_FIELDS or PURCHASE_HEADER_FIELDS in business.js.');
	}
}
var global_consoleWarnOnce = true;

/**
 * Post-save hook consumed by $.fn.callAjax in main.js. Returning TRUE means "this module handled the
 * reset, the modal and the grid" — the generic register path is then skipped.
 */
window.afterSavePurchase = function () {
	// Consume the intent exactly once, whatever we decide below, so it can never leak into a later save.
	var addAnother = purchaseAddAnother;
	purchaseAddAnother = false;

	// "Add another" has no meaning when EDITING an existing purchase — there is nothing to add another
	// of. Fall through to the generic close in that case.
	if (!addAnother) return false;

	// Clear the line, keep the header (vendor + invoice # + date + the vendor dues row).
	resetBSDD('purchaseItemDD');
	for (var i = 0; i < PURCHASE_LINE_FIELDS.length; i++) {
		var el = document.getElementById(PURCHASE_LINE_FIELDS[i]);
		if (el) el.value = '';
	}
	// SER-3c: the loop above blanks the fields by id, which fires no input event — so the quantity lock
	// applied to the LAST line would survive onto the next one, leaving the operator with a quantity stuck
	// at 1 and nothing on screen explaining why. Released explicitly, right where the line is cleared.
	if (typeof applyPurchaseSerialQuantityLock === 'function') applyPurchaseSerialQuantityLock();
	if (typeof updatePurchaseProjectedOnHand === 'function') updatePurchaseProjectedOnHand();

	// Refresh the grid WITHOUT clear().draw() — blanking the table between every line is the flicker
	// that makes rapid entry feel slow.
	try { if (typeof datatable !== 'undefined' && datatable) datatable.ajax.reload(null, false); } catch (e) {}
	if (typeof clearFormError === 'function') clearFormError();

	purchaseLineCount++;
	$('#purchaseLineCount')
		.text(t('ui.js.purchaseLinesOnBill').replace('{0}', purchaseLineCount))
		.show();

	// Back to the item picker — the first field of the next line. bootstrap-select hides the real
	// <select> behind a button, so focus the button when the picker has been enhanced.
	var $dd = $('#purchaseItemDD'), $wrap = $dd.next('.bootstrap-select');
	if ($wrap.length) $wrap.find('button').first().focus(); else $dd.focus();

	return true;
};

/* ------------------------------------------------------------------------------------------------
 * P6 — keyboard-first purchase entry.
 *
 * Receiving a delivery is the same shape of work as ringing up a sale: one operator, a stack of items,
 * a keyboard. The sale screen got an Enter-chain in P1-P3; this gives the purchase form the same thing
 * using the SAME engine (/js/common/enter-chain.js), so the two cannot drift apart.
 *
 *   Enter          next field (hidden/off fields are skipped, so the chain follows configuration)
 *   Shift+Enter    previous field
 *   Enter on the last field   -> Save & Add Another   (the multi-line case, which is the common one)
 *   Ctrl+Enter     -> Save & Close                    (finish the bill from ANY field)
 *   Escape         -> Cancel
 *
 * Note pos-keyboard.js deliberately stands down while a .crud-overlay is open, so the sale chain and
 * this one can never both respond to the same keystroke.
 * ---------------------------------------------------------------------------------------------- */
/**
 * P7.1: the chain is DERIVED from the form, not listed here.
 *
 * It used to be an explicit array of 11 ids. That array was a second copy of the form, and this
 * codebase has twice paid for exactly that: a picker listed in the sale chain that the movement
 * handler did not know about (a keyboard dead end on the busiest screen), and P6's own first attempt,
 * which grouped fields by MEANING while the form was laid out differently, so Enter jumped from the
 * invoice box down to the date and back up to the item picker.
 *
 * EnterChain.fieldsIn('#Purchase') reads the form itself, in DOM order — which IS the order the eye
 * follows. Fields the tenant hid (#purchaseTaxRow when purchase tax is off), read-only boxes, and the
 * computed totals marked data-kbd-skip in the template all fall out automatically.
 *
 * For a tenant without purchase tax the walk is exactly:
 *   invoice -> batch -> vendor -> item -> serial -> condition -> qty -> cost -> sell -> date -> expiry -> paid
 * which is the sequence the form already showed; nothing about the behaviour changed, only where the
 * knowledge of it lives.
 *
 * SER-3d demonstrated the point. Moving the serial box in front of QTY put it in the walk with NO code
 * change here, because the walk is read from the DOM. The sale screen's chain is a literal list of ids in
 * pos-keyboard.js, and the same move left `sellSerials` out of it entirely — a keyboard-only cashier could
 * not reach a field the server then refused the sale for. Deriving beats enumerating, and that is the
 * evidence.
 */
var PURCHASE_FORM = '#Purchase';
// (no picker list: enter-chain asks the DOM whether a field is a bootstrap-select)

function purchaseModalOpen(){
	return $('#PurchaseModal').hasClass('open');
}

$(function () {
	window.EnterChain.bind('purchase', {
		container: PURCHASE_FORM,   // derived per keystroke — see PURCHASE_FORM above
		active:  purchaseModalOpen,
		// Enter past the last field saves and stays — a delivery rarely has exactly one line, and the
		// operator who does have one line has Ctrl+Enter.
		onEnd:       function () { $('#addPurchaseAnother').click(); return true; },
		onCtrlEnter: function () { $('#addPurchase').click(); },
		onEscape:    function () { closeModal('PurchaseModal'); return true; }
	});

	// NOTE: nothing here auto-focuses the first field. openModal() already does it — it calls
	// focusFirstField() on the next animation frame, which lands on #purchaseInvoiceNo, the first
	// field of the chain. An earlier version of P6 added a second, timer-based auto-focus that raced
	// it; two things moving the cursor is exactly the class of bug this codebase's DRY rule exists to
	// prevent, and the fix was to delete the duplicate rather than arbitrate between them.

	// Submit via the SAME validated path as Save & Close — set the intent, then trigger that button.
	$(document).on('click', '#addPurchaseAnother', function () {
		// Editing an existing row: "add another" is meaningless, so behave as a plain save.
		purchaseAddAnother = !$('#purchaseId').val();
		$('#addPurchase').click();
	});

	// A REAL click on Save & Close cancels any stale intent — e.g. a previous "Add another" whose save
	// failed, so afterSavePurchase never ran to consume it. Delegated on document deliberately: the
	// generic binder does $("#addPurchase").off(), which would remove a handler bound to the element.
	// jQuery's programmatic .click() above carries no originalEvent, so it does not clear the flag.
	$(document).on('click', '#addPurchase', function (e) {
		if (e.originalEvent) purchaseAddAnother = false;
	});
});

// Phase B: the purchase-form tax field is only meaningful when the org's "Purchase tax (input credit)" toggle is on.
function refreshPurchaseTaxRow(){
	$.get(serverContext + 'getTaxSetting', function(resp){
		var s = (resp && resp.object) ? resp.object : {};
		if (s.inputTaxEnabled === true) $('#purchaseTaxRow').show(); else { $('#purchaseTaxRow').hide(); $('#purchaseTaxRate').val(''); }
	});
}

// G2 (slice 34): Sale Return. The per-row "Return" button opens a small self-contained dialog that supports a
// PARTIAL return (1..sold qty) + an optional reason, then posts to /saleReturn. The server decides whether the
// sale is a saga sell (-> inventory inverse saga) or a legacy local-Stock sell; the UI just sends sellId/qty.
function buildSaleReturnDialog(){
	var d = document.getElementById('saleReturnDialog');
	if (d) return d;
	d = document.createElement('div');
	d.id = 'saleReturnDialog';
	d.style.cssText = 'position:fixed;inset:0;z-index:10000;display:none;'
		+ 'background:rgba(0,0,0,.45);align-items:center;justify-content:center';
	d.innerHTML =
		"<div style='background:#fff;border-radius:10px;max-width:420px;width:92%;padding:22px 24px;"
		+ "box-shadow:0 12px 40px rgba(0,0,0,.3)'>"
		+ "<h4 style='margin:0 0 4px;font-weight:700'>Sale Return</h4>"
		+ "<div style='font-size:12px;color:#7a889c;margin-bottom:12px'>Take back some or all items — restocks them and refunds the returned portion. The invoice stays active. (To cancel the whole sale, use Void.)</div>"
		+ "<div style='font-size:13px;color:#444;margin-bottom:12px'>"
		+ "Invoice <b id='srInvoice'></b> &middot; <span id='srItem'></span><br>"
		+ "Sold quantity: <b id='srSold'></b></div>"
		+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Return quantity</label>"
		+ "<input type='number' id='srQty' class='form-control' step='any' min='1' style='margin-bottom:12px'>"
		+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Reason (optional)</label>"
		+ "<input type='text' id='srReason' class='form-control' maxlength='200' placeholder='e.g. damaged, expired, customer change' style='margin-bottom:8px'>"
		// SF-5 Model B: any overpayment on the return goes back as cash (default) or as store credit.
		+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Refund overpayment as</label>"
		+ "<select id='srRefundAs' class='form-control' style='margin-bottom:8px'><option value='CASH'>Cash</option><option value='CREDIT'>Store credit</option></select>"
		+ "<label style='display:block;font-size:13px;margin-bottom:8px'><input type='checkbox' id='srQuarantine' style='margin-right:6px'>Quarantine returned stock (do not restock)</label>"
		+ "<div id='srError' style='color:#c0392b;font-size:12px;min-height:16px;margin-bottom:8px'></div>"
		+ "<div style='text-align:right'>"
		+ "<button type='button' class='btn btn-default' onclick='closeSaleReturn()'>Cancel</button> "
		+ "<button type='button' id='srSubmit' class='btn btn-warning' onclick='submitSaleReturn()'>"
		+ "<span class='glyphicon glyphicon-share-alt'></span> Confirm Return</button>"
		+ "</div></div>";
	document.body.appendChild(d);
	return d;
}

function openSaleReturn(btn){
	var d = buildSaleReturnDialog();
	var sold = parseFloat(btn.getAttribute('data-qty')) || 0;
	d.dataset.sellid  = btn.getAttribute('data-sellid') || '';
	d.dataset.stockid = btn.getAttribute('data-stockid') || '';
	d.dataset.sold    = sold;
	document.getElementById('srInvoice').textContent = btn.getAttribute('data-invoice') || '—';
	document.getElementById('srItem').textContent    = btn.getAttribute('data-item') || '';
	document.getElementById('srSold').textContent    = sold;
	var qtyInput = document.getElementById('srQty');
	qtyInput.value = sold;
	qtyInput.max   = sold;
	document.getElementById('srReason').value = '';
	// Pharmacy returns default to quarantine (returned meds can't be re-dispensed); other verticals default off.
	document.getElementById('srQuarantine').checked = (window.MODULE === 'PHARMA');
	document.getElementById('srError').textContent = '';
	d.style.display = 'flex';
}

function closeSaleReturn(){
	var d = document.getElementById('saleReturnDialog');
	if (d) d.style.display = 'none';
}

function submitSaleReturn(){
	var d = document.getElementById('saleReturnDialog');
	var sellId = d.dataset.sellid, stockId = d.dataset.stockid;
	var sold = parseFloat(d.dataset.sold) || 0;
	var qty = parseFloat(document.getElementById('srQty').value);
	var err = document.getElementById('srError');
	if (!qty || qty <= 0) { err.textContent = t('ui.js.enterAQuantityGreaterThan0'); return false; }
	if (qty > sold)       { err.textContent = t('ui.js.cannotReturnMoreThanTheSoldQuantity') + sold + ').'; return false; }

	var btn = document.getElementById('srSubmit');
	btn.disabled = true;
	$.ajax({
		type: 'POST',
		url: serverContext + "saleReturn",
		dataType: "json",
		data: { 'sellId': sellId, 'sellSId': stockId, 'quantity': qty, 'reason': document.getElementById('srReason').value,
			'quarantine': document.getElementById('srQuarantine').checked,
			'refundAs': document.getElementById('srRefundAs').value },
		success: function(data){
			btn.disabled = false;
			if (data && (data.status === 'SUCCESS' || data.message)) {
				closeSaleReturn();
				showSaleSuccess((data.message) || 'Sale returned successfully.');
				datatable.clear().draw();
				datatable.ajax.reload();
				// A return moves the customer's BALANCE, and #sellCustomerDD caches it per option
				// (data-due / data-credit-limit) so the till can show "available credit" while typing
				// without a call per keystroke. Refresh the one list this write actually invalidated.
				//
				// This used to happen by accident: the grid reload above re-ran the section's dropdown
				// preload as a side effect, which also re-fetched 2000 catalog products nobody asked
				// for. That preload is now correctly tied to opening a section, so the refresh has to
				// be stated — by the writer, at the point of the change, and for that list only.
				if (typeof loadSellCustomers === 'function') loadSellCustomers();
				// Task #15: offer the CREDIT NOTE while the customer is still at the counter — that is the
				// moment the paper is wanted, and it is the only place the note number surfaces today (there
				// is no returns list screen to reprint from yet). Offered, not auto-printed: a shop that does
				// not hand out credit notes should not get a print dialog on every return.
				offerReturnDocument('credit', data && data.object);
			} else {
				err.textContent = (data && data.status ? data.status : 'Return failed') + (data && data.message ? ': ' + data.message : '.');
			}
		},
		error: function (e) {
			btn.disabled = false;
			err.textContent = t('ui.js.anErrorOccurredPleaseTryAgain');
		}
	});
}


/**
 * Task #15 — after a return is taken, offer to print its note.
 *
 * <p>Shared by both sides so the sale and purchase flows cannot drift into two different behaviours, and so
 * the "is there a number to print?" guard exists once. A return whose note number is missing predates the
 * note series entirely and has no printable document — silently offering nothing is correct there, because
 * the alternative is a button that always fails.
 *
 * @param kind   'credit' (sale return) or 'debit' (purchase return)
 * @param noteNo the note number the server just allocated and returned
 */
function offerReturnDocument(kind, noteNo) {
	if (!noteNo || typeof printReturnDocument !== 'function') return;
	var debit = (kind === 'debit');
	uiConfirm({
		title: t(debit ? 'ui.js.docDebitNote' : 'ui.js.docCreditNote') + ' ' + noteNo,
		message: t('ui.js.printThisReturnDocument'),
		confirmText: t('ui.js.print')
	}).then(function (ok) {
		if (ok) printReturnDocument(kind, noteNo);
	});
}

// â”€â”€â”€ Receive Payment (AR subledger) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// A "Receive" action on each customer row opens the modal; submit FIFO-allocates the receipt to the customer's
// open invoices (business-service), recomputes their due, and records it in the shared finance ledger.
$(document).on('click', '.rcv-pay-btn', function (e) {
	e.stopPropagation();   // don't let the row-click also open the edit modal
	openReceivePayment($(this).data('cid'), $(this).data('name'), $(this).data('due'));
});

// Audit #5: one idempotency key per submit attempt so a double-click / retry can't double-charge. Generated when the
// modal opens (reused across retries of the SAME payment); the server dedups on it. Fresh key each time the modal reopens.
function newIdemKey() {
	try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID(); } catch (e) {}
	return String(Date.now()) + '-' + Math.random().toString(16).slice(2);
}

function openReceivePayment(customerId, name, due) {
	$("#rcvCustomerId").val(customerId);
	$("#rcvCustomerName").text(name || ('Customer #' + customerId));
	var d = Number(due || 0);
	$("#rcvDue").text(d);
	$("#rcvAmount").val(d > 0 ? d : '');
	$("#rcvMethod").val('CASH');
	$("#rcvReference").val('');
	$("#rcvDate").val(new Date().toISOString().slice(0, 10));
	window.rcvIdemKey = newIdemKey();   // Audit #5
	openModal('ReceivePaymentModal');
}

function submitReceivePayment() {
	var customerId = $("#rcvCustomerId").val();
	var amount = $("#rcvAmount").val() * 1;
	if (!customerId || !(amount > 0)) { showFormError(t('ui.js.enterAPositiveAmountToReceive')); return; }
	if (window._rcvBusy) return; window._rcvBusy = true;   // Audit #5: submit-lock (belt-and-braces with the server key)
	$.post(serverContext + "receivePayment", {
		customerId: customerId,
		amount: amount,
		method: $("#rcvMethod").val(),
		paidOn: $("#rcvDate").val(),
		reference: $("#rcvReference").val(),
		idempotencyKey: window.rcvIdemKey
	}, function (resp) {
		if (resp && resp.status === "SUCCESS") {
			var o = resp.object || {};
			var msg = 'Payment received.' + (o.receiptNo ? ' Receipt ' + o.receiptNo : '');
			if (typeof showSaleSuccess === 'function') showSaleSuccess(msg); else clearFormError();
			closeModal('ReceivePaymentModal');
			loadDataTable();   // refresh the customer list — due is updated
		} else {
			showFormError(apiMessage(resp, 'Could not record the payment.'));
		}
	}, 'json').fail(function () { showFormError(t('ui.js.couldNotRecordThePayment')); })
		.always(function () { window._rcvBusy = false; });
}

// F1 (AP): Pay Vendor — mirror of Receive Payment. Opens the modal from the vendor row's Pay button, posts /payVendor.
$(document).on('click', '.pay-vendor-btn', function (e) {
	e.stopPropagation();   // don't let the row-click also open the edit modal
	openPayVendor($(this).data('vid'), $(this).data('name'), $(this).data('due'));
});

function openPayVendor(venderId, name, due) {
	$("#pvVendorId").val(venderId);
	$("#pvVendorName").text(name || ('Vendor #' + venderId));
	var d = Number(due || 0);
	$("#pvDue").text(d);
	$("#pvAmount").val(d > 0 ? d : '');
	$("#pvMethod").val('CASH');
	$("#pvReference").val('');
	$("#pvDate").val(new Date().toISOString().slice(0, 10));
	window.pvIdemKey = newIdemKey();   // Audit #5
	openModal('PayVendorModal');
}

function submitPayVendor() {
	var venderId = $("#pvVendorId").val();
	var amount = $("#pvAmount").val() * 1;
	if (!venderId || !(amount > 0)) { showFormError(t('ui.js.enterAPositiveAmountToPay')); return; }
	if (window._pvBusy) return; window._pvBusy = true;   // Audit #5: submit-lock
	$.post(serverContext + "payVendor", {
		venderId: venderId,
		amount: amount,
		method: $("#pvMethod").val(),
		paidOn: $("#pvDate").val(),
		reference: $("#pvReference").val(),
		idempotencyKey: window.pvIdemKey
	}, function (resp) {
		if (resp && resp.status === "SUCCESS") {
			var o = resp.object || {};
			var msg = 'Vendor paid.' + (o.voucherNo ? ' Voucher ' + o.voucherNo : '');
			if (typeof showSaleSuccess === 'function') showSaleSuccess(msg); else clearFormError();
			closeModal('PayVendorModal');
			loadDataTable();   // refresh the vendor list — due is updated
		} else {
			showFormError(apiMessage(resp, 'Could not record the payment.'));
		}
	}, 'json').fail(function () { showFormError(t('ui.js.couldNotRecordThePayment')); })
		.always(function () { window._pvBusy = false; });
}

// Audit #3: Void an entire invoice — the books-safe cancel (reverses stock + customer balance + GL). Confirm +
// optional reason, POST /voidSell, then refresh the report.
function openVoidSell(btn){
	var chId = btn.getAttribute('data-chid');
	var inv = btn.getAttribute('data-invoice') || '';
	if(!chId) return;
	// One dialog for the whole decision — this used to be a confirm() followed by a second prompt() popup.
	uiPromptConfirm({
		title: t('ui.js.voidInvoice') + inv + '?',
		message: t('ui.js.thisReversesTheStockTheCustomerBalance'),
		input: { label: 'Reason for voiding (optional)', placeholder: 'e.g. wrong customer', maxlength: 255 },
		confirmText: t('ui.js.voidInvoice2'),
		tone: 'danger'
	}).then(function(reason){
		if(reason === null) return;
		$.post(serverContext + 'voidSell', { customerHistoryId: chId, reason: reason }, function(resp){
			if(resp && resp.status === 'SUCCESS'){ if(typeof showSaleSuccess==='function') showSaleSuccess(t('ui.js.invoiceVoided')); try { loadDataTable(); } catch(e){} }
			else { uiAlert({ title: t('ui.js.voidFailed'), message: apiMessage(resp, 'The invoice could not be voided.'), tone: 'danger' }); }
		}).fail(function(){ uiAlert({ title: t('ui.js.voidFailed'), message: t('ui.js.theInvoiceCouldNotBeVoided'), tone: 'danger' }); });
	});
}

// Audit #3: Void a bill — reverses stock-in + vendor payable + GL. POST /voidPurchase, then refresh purchases.
$(document).on('click', '.purchase-void-btn', function (e) {
	e.stopPropagation();
	var pid = this.getAttribute('data-pid'), inv = this.getAttribute('data-inv') || '';
	if(!pid) return;
	uiPromptConfirm({
		title: t('ui.js.voidBill') + inv + '?',
		message: t('ui.js.thisReversesTheStockInTheVendor'),
		input: { label: 'Reason for voiding (optional)', placeholder: 'e.g. duplicate entry', maxlength: 255 },
		confirmText: t('ui.js.voidBill2'),
		tone: 'danger'
	}).then(function(reason){
		if(reason === null) return;
		$.post(serverContext + 'voidPurchase', { purchaseId: pid, reason: reason }, function(resp){
			if(resp && resp.status === 'SUCCESS'){ if(typeof showSaleSuccess==='function') showSaleSuccess(t('ui.js.billVoided')); try { loadDataTable(); } catch(e){} }
			else { uiAlert({ title: t('ui.js.voidFailed'), message: apiMessage(resp, 'The bill could not be voided.'), tone: 'danger' }); }
		}).fail(function(){ uiAlert({ title: t('ui.js.voidFailed'), message: t('ui.js.theBillCouldNotBeVoided'), tone: 'danger' }); });
	});
});

// Purchase Return (debit note) — a per-row Return button opens a small dialog and posts /purchaseReturn.
$(document).on('click', '.purchase-return-btn', function (e) {
	e.stopPropagation();
	openPurchaseReturn(this.getAttribute('data-pid'), parseFloat(this.getAttribute('data-qty')) || 0, this.getAttribute('data-inv'));
});

function openPurchaseReturn(purchaseId, soldQty, inv){
	var d = document.getElementById('purchaseReturnDialog');
	if(!d){
		d = document.createElement('div'); d.id = 'purchaseReturnDialog';
		d.style.cssText = 'position:fixed;inset:0;z-index:10000;display:none;background:rgba(0,0,0,.45);align-items:center;justify-content:center';
		d.innerHTML = "<div style='background:#fff;border-radius:10px;max-width:420px;width:92%;padding:22px 24px;box-shadow:0 12px 40px rgba(0,0,0,.3)'>"
			+ "<h4 style='margin:0 0 12px;font-weight:700'>Return to Vendor</h4>"
			+ "<div style='font-size:13px;color:#444;margin-bottom:10px'>Purchase <b id='prInv'></b> &middot; purchased qty <b id='prSold'></b></div>"
			+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Return quantity</label>"
			+ "<input type='number' id='prQty' class='form-control' step='any' min='1' style='margin-bottom:10px'>"
			+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Reason (optional)</label>"
			+ "<input type='text' id='prReason' class='form-control' maxlength='200' placeholder='e.g. damaged, wrong item' style='margin-bottom:8px'>"
			+ "<div id='prError' style='color:#c0392b;font-size:12px;min-height:16px;margin-bottom:8px'></div>"
			+ "<div style='text-align:right'><button type='button' class='btn btn-default' onclick=\"document.getElementById('purchaseReturnDialog').style.display='none'\">Cancel</button> "
			+ "<button type='button' class='btn btn-warning' onclick='submitPurchaseReturn()'><span class='glyphicon glyphicon-share-alt'></span> Confirm Return</button></div></div>";
		document.body.appendChild(d);
	}
	d.dataset.pid = purchaseId; d.dataset.sold = soldQty;
	document.getElementById('prInv').textContent = inv || '—';
	document.getElementById('prSold').textContent = soldQty;
	var q = document.getElementById('prQty'); q.value = soldQty; q.max = soldQty;
	document.getElementById('prReason').value = ''; document.getElementById('prError').textContent = '';
	d.style.display = 'flex';
}

function submitPurchaseReturn(){
	var d = document.getElementById('purchaseReturnDialog');
	var pid = d.dataset.pid, sold = parseFloat(d.dataset.sold) || 0;
	var qty = parseFloat(document.getElementById('prQty').value);
	var err = document.getElementById('prError');
	if(!qty || qty <= 0){ err.textContent = t('ui.js.enterAQuantityGreaterThan0'); return; }
	if(qty > sold){ err.textContent = t('ui.js.cannotReturnMoreThanPurchased') + sold + ').'; return; }
	$.post(serverContext + 'purchaseReturn', { purchaseId: pid, quantity: qty, reason: document.getElementById('prReason').value }, function(resp){
		if(resp && resp.status === 'SUCCESS'){
			d.style.display='none';
			if(typeof showSaleSuccess==='function') showSaleSuccess(t('ui.js.purchaseReturnedToVendor'));
			loadDataTable();
			// Task #15: the DEBIT NOTE the supplier reconciles against. Unlike the sale side the number is
			// nested — purchaseReturn answers with a result map, not the bare number.
			offerReturnDocument('debit', resp.object && resp.object.debitNoteNo);
		}
		else { err.textContent = apiMessage(resp, 'Return failed.'); }
	}, 'json').fail(function(){ err.textContent = t('ui.js.anErrorOccurredPleaseTryAgain'); });
}

// F2: Statement of account + Aging — self-contained dialogs (no template modal needed), like the sale-return dialog.
$(document).on('click', '.stmt-btn', function (e) {
	e.stopPropagation();
	openStatement($(this).data('ptype'), $(this).data('pid'), $(this).data('name'));
});

function buildFinanceDialog(id){
	var d = document.getElementById(id);
	if (d) return d;
	d = document.createElement('div');
	d.id = id;
	d.style.cssText = 'position:fixed;inset:0;z-index:10000;display:none;background:rgba(0,0,0,.45);align-items:center;justify-content:center';
	d.innerHTML = "<div style='background:#fff;border-radius:10px;max-width:760px;width:94%;max-height:86vh;overflow:auto;padding:20px 22px;box-shadow:0 12px 40px rgba(0,0,0,.3)'>"
		+ "<div style='display:flex;align-items:center;margin-bottom:12px'><h4 id='"+id+"Title' style='margin:0;font-weight:700;flex:1'></h4>"
		+ "<button type='button' class='btn btn-default btn-sm' onclick=\"document.getElementById('"+id+"').style.display='none'\">Close</button></div>"
		+ "<div id='"+id+"Body'></div></div>";
	document.body.appendChild(d);
	return d;
}

/**
 * B2B-P3d (#5): put a Download button in the statement dialog header, beside Close.
 * A plain link, not an ajax call — the browser handles the Content-Disposition and saves the file.
 */
function addStatementDownload(partyType, partyId){
	var title = document.getElementById('StatementDialogTitle');
	if (!title || !title.parentNode) return;
	var old = document.getElementById('StatementDownloadBtn');
	if (old) old.parentNode.removeChild(old);   // re-opened for another party: never leak the previous link
	var url = serverContext + (partyType === 'VENDOR'
		? 'vendorStatement.csv?venderId=' : 'customerStatement.csv?customerId=') + encodeURIComponent(partyId);
	var a = document.createElement('a');
	a.id = 'StatementDownloadBtn';
	a.className = 'btn btn-default btn-sm';
	a.style.marginRight = '8px';
	a.setAttribute('href', url);
	a.textContent = t('ui.js.download');
	title.parentNode.insertBefore(a, title.nextSibling);
}

/**
 * B2B-P3f: statement line types, translated. The column rendered the raw enum ('BILL', 'PAYMENT'), and 3f adds
 * three more — CREDIT_NOTE, DEBIT_NOTE, VOID — so leaving it raw would put untranslated shouting SQL-ish tokens
 * on a document customers read. Defined ONCE here beside the only screen that renders a statement; an unknown
 * type falls through to itself rather than rendering blank, so a future type is visible instead of invisible.
 * Keys MUST carry the ui.js.* prefix — that is the only prefix LocaleInterceptor ships to the browser.
 */
var STATEMENT_TYPE_KEYS = {
	BILL: 'ui.js.stmtTypeBill',
	PAYMENT: 'ui.js.stmtTypePayment',
	CREDIT_NOTE: 'ui.js.stmtTypeCreditNote',
	DEBIT_NOTE: 'ui.js.stmtTypeDebitNote',
	VOID: 'ui.js.stmtTypeVoid',
	// INST-2. Not a transaction: a SCHEDULE row is an instalment still to fall due, shown so a customer
	// reading the statement can see what they owe and WHEN without asking for a second document.
	SCHEDULE: 'ui.js.stmtTypeSchedule'
};
function statementTypeLabel(type){
	var key = STATEMENT_TYPE_KEYS[type];
	return key ? t(key) : (type || '');
}

function openStatement(partyType, partyId, name){
	var url = (partyType === 'VENDOR' ? 'vendorStatement?venderId=' : 'customerStatement?customerId=') + encodeURIComponent(partyId);
	buildFinanceDialog('StatementDialog').style.display = 'flex';
	// ui.js.statementFor, not ui.js.statement: this is a PREFIX joined to the party name
	// ("Statement — Acme Traders"), so it carries the separator. The two were ONE key until a later
	// slice re-declared it as the bare word for a button label; the last definition in a .properties
	// file wins, so this title silently lost its separator and rendered "StatementAcme Traders".
	//
	// The space is supplied HERE rather than as a trailing space in the bundle: only English ever had
	// one, so the other five languages ran the name straight onto the dash — and a trailing space is
	// invisible in review and stripped by most editors, so it cannot be relied on as a contract.
	document.getElementById('StatementDialogTitle').textContent = t('ui.js.statementFor') + ' ' + (name || ((partyType === 'VENDOR' ? 'Vendor #' : 'Customer #') + partyId));
	// B2B-P3d (#5): a statement is only useful if the customer can take it away. The CSV comes from the SAME
	// service method the table below renders, so the file and the screen can never disagree.
	addStatementDownload(partyType, partyId);
	document.getElementById('StatementDialogBody').innerHTML = '<div style="padding:8px">Loading…</div>';
	$.get(serverContext + url, function(resp){
		var lines = (resp && (resp.collection || resp.data)) || [];
		if (!lines.length) { document.getElementById('StatementDialogBody').innerHTML = '<div style="padding:8px;color:#777">No documents.</div>'; return; }
		var h = '<table class="table table-striped" style="width:100%"><thead><tr><th>Date</th><th>Doc #</th><th>Type</th><th class="text-right">Debit</th><th class="text-right">Credit</th><th class="text-right">Balance</th></tr></thead><tbody>';
		// INST-2: the ledger, then the forward schedule. They are separated by a caption rather than merged,
		// because everything above has HAPPENED and everything below has not.
		var ledger = lines.filter(function(l){ return l.type !== 'SCHEDULE'; });
		var schedule = lines.filter(function(l){ return l.type === 'SCHEDULE'; });
		var row = function(l, muted){
			return '<tr'+(muted?' style="color:#777;font-style:italic"':'')+'><td>'+escHtml(l.date||'')+'</td><td>'+escHtml(l.docNo||'')+'</td><td>'+escHtml(statementTypeLabel(l.type))+'</td>'
				+ '<td class="text-right">'+(l.debit!=null?Number(l.debit).toFixed(2):'')+'</td>'
				+ '<td class="text-right">'+(l.credit!=null?Number(l.credit).toFixed(2):'')+'</td>'
				+ '<td class="text-right"><b>'+(l.balance!=null?Number(l.balance).toFixed(2):'')+'</b></td></tr>';
		};
		ledger.forEach(function(l){ h += row(l, false); });
		if (schedule.length) {
			h += '<tr><td colspan="6" style="background:#f5f5f5;font-weight:600">'+escHtml(t('ui.js.stmtScheduleNote'))+'</td></tr>';
			schedule.forEach(function(l){ h += row(l, true); });
		}
		// The closing balance is the last LEDGER line's, never the last row's. A schedule row's balance is what
		// would remain after paying that instalment — it counts down toward zero, so reading the footer off
		// lines[last] would have printed a closing balance of 0.00 for a customer who owes the lot.
		var closing = Number((ledger.length ? ledger[ledger.length-1].balance : 0) || 0).toFixed(2);
		h += '</tbody><tfoot><tr><th colspan="5" class="text-right">Closing balance</th><th class="text-right">'+closing+'</th></tr></tfoot></table>';
		document.getElementById('StatementDialogBody').innerHTML = h;
	}, 'json').fail(function(){ document.getElementById('StatementDialogBody').innerHTML = '<div style="padding:8px;color:#c0392b">Could not load the statement.</div>'; });
}

// Contact-360 moved to the shared /js/common/party-contact.js (P4c): education, welfare and pharmacy show the
// same panel, so openContact360/contact360Button live in ONE place and every vertical calls them.

// Aging report (Receivables = CUSTOMER, Payables = VENDOR). Trigger buttons live in the Customer/Vendor toolbars.
function openAging(partyType){
	var url = partyType === 'VENDOR' ? 'vendorAging' : 'customerAging';
	buildFinanceDialog('AgingDialog').style.display = 'flex';
	document.getElementById('AgingDialogTitle').textContent = (partyType === 'VENDOR' ? 'Payables' : 'Receivables') + ' Aging';
	document.getElementById('AgingDialogBody').innerHTML = '<div style="padding:8px">Loading…</div>';
	$.get(serverContext + url, function(resp){
		var rows = (resp && (resp.collection || resp.data)) || [];
		if (!rows.length) { document.getElementById('AgingDialogBody').innerHTML = '<div style="padding:8px;color:#777">Nothing outstanding.</div>'; return; }
		var t=[0,0,0,0,0];
		var h = '<table class="table table-striped" style="width:100%"><thead><tr><th>'+(partyType==='VENDOR'?'Vendor':'Customer')+'</th><th class="text-right">0–30</th><th class="text-right">31–60</th><th class="text-right">61–90</th><th class="text-right">90+</th><th class="text-right">Total</th></tr></thead><tbody>';
		rows.forEach(function(r){
			var v=[Number(r.b0_30||0),Number(r.b31_60||0),Number(r.b61_90||0),Number(r.b90plus||0),Number(r.total||0)];
			for(var i=0;i<5;i++) t[i]+=v[i];
			h += '<tr><td>'+escHtml(r.partyName||('#'+r.partyId))+'</td>'+v.map(function(x){return '<td class="text-right">'+x.toFixed(2)+'</td>';}).join('')+'</tr>';
		});
		h += '</tbody><tfoot><tr><th>Total</th>'+t.map(function(x){return '<th class="text-right">'+x.toFixed(2)+'</th>';}).join('')+'</tr></tfoot></table>';
		document.getElementById('AgingDialogBody').innerHTML = h;
	}, 'json').fail(function(){ document.getElementById('AgingDialogBody').innerHTML = '<div style="padding:8px;color:#c0392b">Could not load aging.</div>'; });
}

// â”€â”€â”€ Finance Reports page (org-wide GL statements + tax register + audit trail) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Dedicated #FinanceDiv view with a report switcher + per-report filter criteria (replaces the old
// modal dialogs). Remembers the last report + filter values in localStorage so reopening lands where
// you left off. Backend contracts: trial-balance/balance-sheet take ?asOf; pnl/tax-register take
// ?from&to; audit takes ?action&limit.
var FIN_REPORTS = {
	trialBalance: { fields:['asOf'],          run:finRunTrialBalance },
	pnl:          { fields:['from','to'],     run:finRunPnl },
	balanceSheet: { fields:['asOf'],          run:finRunBalanceSheet },
	taxRegister:  { fields:['from','to'],     run:finRunTaxRegister },
	auditLog:     { fields:['action','limit'], run:finRunAuditLog },
	periodClose:  { fields:[],                run:finRunPeriodClose }
};
var finCurrent = 'trialBalance';

function finToday(){ return new Date().toISOString().slice(0,10); }
function finMonthStart(){ var d=new Date(); return new Date(d.getFullYear(),d.getMonth(),1).toISOString().slice(0,10); }
function finPrefs(){ try{ return JSON.parse(localStorage.getItem('finPrefs')||'{}'); }catch(e){ return {}; } }
function finSavePrefs(){
	try{ localStorage.setItem('finPrefs', JSON.stringify({
		report:finCurrent, asOf:$('#finAsOf').val(), from:$('#finFrom').val(),
		to:$('#finTo').val(), action:$('#finAction').val(), limit:$('#finLimit').val() })); }catch(e){}
}

// Open the Finance view on a given report — called by the sidebar menu + the in-page tab buttons.
function showFinance(report){
	if(!FIN_REPORTS[report]) report='trialBalance';
	finCurrent=report;
	$('.formDiv').hide();
	$('#FinanceDiv').show();
	document.querySelectorAll('#finTabs .fin-tab').forEach(function(b){ b.classList.toggle('active', b.getAttribute('data-report')===report); });
	// seed inputs from saved prefs / sensible defaults (only when empty, so a user's edits survive tab switches)
	var saved=finPrefs();
	if(!$('#finAsOf').val()) $('#finAsOf').val(saved.asOf||finToday());
	if(!$('#finFrom').val()) $('#finFrom').val(saved.from||finMonthStart());
	if(!$('#finTo').val())   $('#finTo').val(saved.to||finToday());
	if(saved.action!=null && !$('#finAction').val()) $('#finAction').val(saved.action);
	if(saved.limit && !$('#finLimit').val())         $('#finLimit').val(saved.limit);
	// show only the filters this report uses
	var use=FIN_REPORTS[report].fields;
	[['asOf','#finAsOfWrap'],['from','#finFromWrap'],['to','#finToWrap'],['action','#finActionWrap'],['limit','#finLimitWrap']]
		.forEach(function(f){ $(f[1]).toggle(use.indexOf(f[0])>=0); });
	runFinanceReport();
}

function runFinanceReport(){
	finSavePrefs();
	document.getElementById('FinanceResults').innerHTML='<div style="padding:10px">Loading…</div>';
	FIN_REPORTS[finCurrent].run();
}
function finSet(html){ document.getElementById('FinanceResults').innerHTML=html; }
function finFail(){ finSet('<div style="padding:10px;color:#c0392b">Could not load the report. Check that finance-service is running.</div>'); }
function finSection(title, rows, total){
	var h='<h5 style="font-weight:700;margin:12px 0 4px">'+escHtml(title)+'</h5><table class="table table-condensed" style="width:100%"><tbody>';
	(rows||[]).forEach(function(r){ h+='<tr><td>'+escHtml((r.code?r.code+' ':'')+(r.name||''))+'</td><td class="text-right">'+Number(r.amount||0).toFixed(2)+'</td></tr>'; });
	if(!(rows||[]).length) h+='<tr><td colspan="2" style="color:#777">None.</td></tr>';
	h+='<tr><th class="text-right">Total '+escHtml(title)+'</th><th class="text-right">'+Number(total||0).toFixed(2)+'</th></tr></tbody></table>';
	return h;
}

function finRunTrialBalance(){
	var asOf=$('#finAsOf').val();
	$.get(serverContext+'gl/trialBalance', asOf?{asOf:asOf}:{}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp; var rows=d.rows||[];
		var h='<table class="table table-striped" style="width:100%"><thead><tr><th>Code</th><th>Account</th><th class="text-right">Debit</th><th class="text-right">Credit</th></tr></thead><tbody>';
		rows.forEach(function(r){ h+='<tr><td>'+escHtml(r.code||'')+'</td><td>'+escHtml(r.name||'')+'</td><td class="text-right">'+Number(r.debit||0).toFixed(2)+'</td><td class="text-right">'+Number(r.credit||0).toFixed(2)+'</td></tr>'; });
		if(!rows.length) h+='<tr><td colspan="4" class="text-center" style="color:#777">No ledger entries yet — post a sale or purchase to populate the GL.</td></tr>';
		h+='</tbody><tfoot><tr><th colspan="2" class="text-right">Total</th><th class="text-right">'+Number(d.totalDebit||0).toFixed(2)+'</th><th class="text-right">'+Number(d.totalCredit||0).toFixed(2)+'</th></tr></tfoot></table>';
		h+='<div style="text-align:right;font-weight:700;color:'+(d.balanced?'#0f6e56':'#c0392b')+'">'+(d.balanced?'Balanced âœ“':'NOT balanced')+'</div>';
		finSet(h);
	}, 'json').fail(finFail);
}

function finRunPnl(){
	$.get(serverContext+'gl/pnl', {from:$('#finFrom').val(), to:$('#finTo').val()}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var h=finSection('Income', d.income, d.totalIncome)+finSection('Expenses', d.expense, d.totalExpense);
		var np=Number(d.netProfit||0);
		h+='<div style="text-align:right;font-size:16px;font-weight:800;color:'+(np>=0?'#0f6e56':'#c0392b')+'">Net Profit: '+np.toFixed(2)+'</div>';
		finSet(h);
	}, 'json').fail(finFail);
}

function finRunBalanceSheet(){
	var asOf=$('#finAsOf').val();
	$.get(serverContext+'gl/balanceSheet', asOf?{asOf:asOf}:{}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var h=finSection('Assets', d.assets, d.totalAssets)+finSection('Liabilities', d.liabilities, d.totalLiabilities);
		var eq=(d.equity||[]).slice();
		if(Number(d.netIncome||0)!==0) eq.push({code:'',name:'Net income (current period)',amount:d.netIncome});
		h+=finSection('Equity', eq, d.totalEquity);
		h+='<div style="text-align:right;font-weight:700;color:'+(d.balanced?'#0f6e56':'#c0392b')+'">Assets '+Number(d.totalAssets||0).toFixed(2)+' = Liab + Equity '+(Number(d.totalLiabilities||0)+Number(d.totalEquity||0)).toFixed(2)+(d.balanced?' âœ“':' — NOT balanced')+'</div>';
		finSet(h);
	}, 'json').fail(finFail);
}

function finRunTaxRegister(){
	$.get(serverContext+'taxRegister', {from:$('#finFrom').val(), to:$('#finTo').val()}, function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var f=function(x){return Number(x||0).toFixed(2);};
		var h='<div style="color:#777;margin-bottom:8px">Period: '+escHtml((d.from||'').toString())+' â†’ '+escHtml((d.to||'').toString())+'</div>';
		h+='<table class="table" style="width:100%"><tbody>'
			+'<tr><td>Output tax (sales)</td><td class="text-right">'+f(d.outputTax)+'</td></tr>'
			+'<tr><td>Less adjustments (returns/voids)</td><td class="text-right">-'+f(d.outputAdjusted)+'</td></tr>'
			+'<tr><th>Net output tax</th><th class="text-right">'+f(d.netOutput)+'</th></tr>'
			+'<tr><td>Input tax (purchases)</td><td class="text-right">'+f(d.inputTax)+'</td></tr>'
			+'<tr><td>Less adjustments (purchase returns)</td><td class="text-right">-'+f(d.inputAdjusted)+'</td></tr>'
			+'<tr><th>Net input tax</th><th class="text-right">'+f(d.netInput)+'</th></tr>'
			+'</tbody></table>';
		var np=Number(d.netPayable||0);
		h+='<div style="text-align:right;font-size:16px;font-weight:800;color:'+(np>=0?'#0f6e56':'#c0392b')+'">Net tax payable: '+f(np)+'</div>';
		var lines=d.lines||[];
		if(lines.length){
			h+='<h5 style="font-weight:700;margin:14px 0 4px">Register</h5><table class="table table-striped" style="width:100%"><thead><tr><th>Date</th><th>Source</th><th>Ref</th><th class="text-right">Output (Cr)</th><th class="text-right">Adjust/Input (Dr)</th></tr></thead><tbody>';
			lines.forEach(function(l){
				h+='<tr><td>'+escHtml((l.date||'').toString())+'</td><td>'+escHtml(l.source||'')+'</td><td>'+escHtml(l.ref||'')+'</td>'
					+'<td class="text-right">'+(Number(l.credit||0)?f(l.credit):'')+'</td>'
					+'<td class="text-right">'+(Number(l.debit||0)?f(l.debit):'')+'</td></tr>';
			});
			h+='</tbody></table>';
		}
		finSet(h);
		finAppendTaxBreakdown();   // multi-rate: per-rate breakdown beneath the net-payable summary
	}, 'json').fail(finFail);
}

// Multi-rate tax: append a "taxable + tax by rate" table (from the transactional lines) below the register.
function finAppendTaxBreakdown(){
	$.get(serverContext+'taxBreakdown', {from:$('#finFrom').val(), to:$('#finTo').val()}, function(resp){
		var d=(resp && resp.object) ? resp.object : ((typeof resp==='string')?JSON.parse(resp):resp);
		var rows=(d && d.rows) ? d.rows : [];
		if(!rows.length) return;
		var f=function(x){return Number(x||0).toFixed(2);};
		var h='<h5 style="font-weight:700;margin:16px 0 4px">Breakdown by rate</h5>'
			+'<table class="table table-striped" style="width:100%"><thead><tr><th class="text-right">Rate %</th>'
			+'<th class="text-right">Output taxable</th><th class="text-right">Output tax</th>'
			+'<th class="text-right">Input taxable</th><th class="text-right">Input tax</th>'
			+'<th class="text-right">Net tax</th></tr></thead><tbody>';
		rows.forEach(function(r){
			h+='<tr><td class="text-right">'+Number(r.rate||0)+'</td>'
				+'<td class="text-right">'+f(r.outputTaxable)+'</td><td class="text-right">'+f(r.outputTax)+'</td>'
				+'<td class="text-right">'+f(r.inputTaxable)+'</td><td class="text-right">'+f(r.inputTax)+'</td>'
				+'<td class="text-right">'+f(r.netTax)+'</td></tr>';
		});
		h+='</tbody><tfoot><tr><th class="text-right">Total</th>'
			+'<th class="text-right">'+f(d.totalOutputTaxable)+'</th><th class="text-right">'+f(d.totalOutputTax)+'</th>'
			+'<th class="text-right">'+f(d.totalInputTaxable)+'</th><th class="text-right">'+f(d.totalInputTax)+'</th>'
			+'<th class="text-right">'+f(d.netPayable)+'</th></tr></tfoot></table>';
		document.getElementById('FinanceResults').insertAdjacentHTML('beforeend', h);
	}, 'json');
}

function finRunAuditLog(){
	var q={limit:$('#finLimit').val()||200}; var a=$('#finAction').val(); if(a) q.action=a;
	$.get(serverContext+'getAuditLog', q, function(resp){
		var rows=(typeof resp==='string')?JSON.parse(resp):resp;
		if(!Array.isArray(rows)){ finSet('<div style="padding:10px;color:#c0392b">Could not load the audit log. Check that audit-service is running.</div>'); return; }
		if(!rows.length){ finSet('<div style="padding:10px;color:#777">No audit events yet.</div>'); return; }
		var h='<table class="table table-striped" style="width:100%"><thead><tr><th>When</th><th>Action</th><th>Entity</th><th class="text-right">Amount</th><th>User</th><th>Source</th><th>Details</th></tr></thead><tbody>';
		rows.forEach(function(r){
			var entity=escHtml((r.entityType||'')+(r.entityRef?(' '+r.entityRef):''));
			h+='<tr><td>'+escHtml((r.occurredAt||'').toString().replace('T',' '))+'</td>'
				+'<td>'+escHtml(r.action||'')+'</td><td>'+entity+'</td>'
				+'<td class="text-right">'+(r.amount!=null?Number(r.amount).toFixed(2):'')+'</td>'
				+'<td>'+escHtml(r.userId!=null?('#'+r.userId):'')+'</td>'
				+'<td>'+escHtml(r.sourceService||'')+'</td>'
				+'<td>'+escHtml(r.details||'')+'</td></tr>';
		});
		h+='</tbody></table>';
		finSet(h);
	}, 'json').fail(finFail);
}

// Period close: read the org's lock state, and (owner/admin) close/reopen. The finance-service is the single
// source of truth; every dated business op (sale/purchase/payment/edit/void) is rejected in a locked period.
function finRunPeriodClose(){
	$.get(serverContext+'gl/periodLock', function(resp){
		var d=(typeof resp==='string')?JSON.parse(resp):resp;
		var locked=(d && d.lockedThrough) ? d.lockedThrough : null;
		var h='<div style="max-width:560px">';
		h+='<p style="color:#555">Closing the books through a date locks it: sales, purchases, payments, edits and voids dated on or before it are rejected until you reopen. Transactions dated after the lock are unaffected.</p>';
		h+='<div style="padding:12px;border-radius:6px;margin:10px 0;font-weight:700;background:'+(locked?'#fdecea':'#eafaf1')+';color:'+(locked?'#c0392b':'#0f6e56')+'">'
			+(locked?('Books are CLOSED through '+escHtml(locked)):'Books are OPEN — no period lock.')+'</div>';
		if(window.canClosePeriod){
			h+='<div class="form-group"><label>Lock the books through</label>'
				+'<input type="date" id="finLockDate" class="form-control" style="max-width:220px" value="'+escHtml(locked||finToday())+'"></div>';
			h+='<button class="btn btn-danger" onclick="finSetPeriodLock()">Close period</button> ';
			if(locked) h+='<button class="btn btn-default" onclick="finReopenPeriod()">Reopen (clear lock)</button>';
		}else{
			h+='<div style="color:#777">Only an owner/admin can change the period lock.</div>';
		}
		h+='</div>';
		finSet(h);
	}, 'json').fail(finFail);
}
function finSetPeriodLock(){
	var d=$('#finLockDate').val(); if(!d){ uiAlert({ title:t('ui.js.pickADate'), message:t('ui.js.chooseTheDateToLockTheBooks'), tone:'warning' }); return; }
	uiConfirm({
		title: t('ui.js.closeTheBooksThrough') + d + '?',
		message: t('ui.js.backDatedSalesPurchasesPaymentsEditsAnd'),
		confirmText: t('ui.js.closeTheBooks'),
		tone: 'warning'
	}).then(function(ok){
		if(!ok) return;
		$.post(serverContext+'gl/periodLock', {lockedThrough:d}, function(){ finRunPeriodClose(); })
			.fail(function(){ uiAlert({ title:t('ui.js.couldNotCloseThePeriod'), message:t('ui.js.youMayNotHavePermissionOrFinance'), tone:'danger' }); });
	});
}
function finReopenPeriod(){
	uiConfirm({
		title: t('ui.js.reopenTheBooks'),
		message: t('ui.js.thisClearsThePeriodLockBackDated'),
		confirmText: t('ui.js.reopenPeriod'),
		tone: 'warning'
	}).then(function(ok){
		if(!ok) return;
		$.post(serverContext+'gl/periodLock', {}, function(){ finRunPeriodClose(); })
			.fail(function(){ uiAlert({ title:t('ui.js.couldNotReopen'), message:t('ui.js.thePeriodLockCouldNotBeCleared'), tone:'danger' }); });
	});
}

// Back-compat shims — any old caller (or the sidebar menu) routes into the page view.
function openTrialBalance(){ showFinance('trialBalance'); }
function openPnl(){ showFinance('pnl'); }
function openBalanceSheet(){ showFinance('balanceSheet'); }
function openTaxRegister(){ showFinance('taxRegister'); }
function openAuditLog(){ showFinance('auditLog'); }

// ===== Owner-configurable POS feature flags (common-settings) applied to the UI =====
// Some Configuration toggles change what the POS UI shows (not just server behaviour). We read them once on load
// from the same /getBusinessConfig catalog and apply them. Defaults are ON, so a fresh org / a config-read hiccup
// leaves every feature visible (fail-open) -- EXCEPT pos.barcode.enabled, which fails CLOSED. A shop without a
// scanner should never be shown a scan box, and that box is the first field on the sale screen and the first
// place focus lands, so guessing wrong costs a keystroke on every sale. A shop that scans turns it on once.
function loadPosFeatureFlags(){
	$.get(serverContext + 'getBusinessConfig', function(res){
		var items = (res && res.data) || [];
		var byKey = {};
		// `chosen` = keys this tenant has actually SAVED. byKey holds every catalogue entry (with its
		// effective value), so "is the key present" cannot tell a deliberate choice from a default —
		// which is exactly the distinction a preset has to respect. The settings payload answers it
		// directly with isDefault, so no inference is needed.
		var chosen = {};
		items.forEach(function(it){
			byKey[it.key] = String(it.value) === 'true';
			if (it.isDefault === false) { chosen[it.key] = true; }
		});
		// absent key â†’ default ON (the feature ships enabled)
		// Barcode absent = OFF. The catalog default is now false, so an absent key means "this tenant has
		// never chosen", and the answer for a tenant that has never chosen is no scan box. Reading it as ON
		// put a scanner field at the top of the sale screen -- and the first focus stop -- for every shop
		// that does not own a scanner.
		window.posBarcodeEnabled = ('pos.barcode.enabled' in byKey) ? byKey['pos.barcode.enabled'] : false;
		window.posAutoPrintReceipt = ('pos.receipt.autoPrint' in byKey) ? byKey['pos.receipt.autoPrint'] : true;
		// UI/UX P1 — the line-entry ROW. The CATALOG default is now ON, so the normal response carries
		// "true" and the compact row is what a tenant gets without configuring anything.
		//
		// This read still fails CLOSED — an absent key or a failed config call yields the OLD stacked
		// layout, not the new one. That is deliberate and remains the safe direction: the tall form is
		// the layout every operator already knows and every screen size handles, so degrading to it
		// costs familiarity, never function. Re-laying-out a till mid-sale because a settings call
		// hiccuped is the surprise worth avoiding, whichever way the default points.
		window.posKeyboardEnabled = byKey['pos.keyboard.enabled'] === true;
		// Ask before completing a sale. Fails OPEN (absent => ON) — the opposite of its neighbours, and
		// deliberately: those fail closed because the risk is an unexpected FEATURE arming itself on a
		// till, whereas the risk here is a sale completing with no question asked. A config hiccup should
		// leave the safety net in place, not remove it.
		window.posConfirmOnComplete = ('pos.sale.confirmOnComplete' in byKey)
			? byKey['pos.sale.confirmOnComplete'] : true;
		// P7.2 — registration-form keyboard nav. Fails OPEN (absent => on), the opposite of the POS
		// flags above, because the risk is opposite: a stray function key on a till can complete a
		// sale, whereas Enter moving to the next box does nothing Tab could not.
		window.kbdFormNavEnabled = ('ui.keyboard.formNav.enabled' in byKey) ? byKey['ui.keyboard.formNav.enabled'] : true;
		/*
		 * #23 — check stock when an item is selected. Fails CLOSED-TO-BLOCKING, i.e. absent => FALSE => the
		 * till does NOT block. The direction is inverted from its neighbours on purpose: for them a config
		 * hiccup must not ARM behaviour, whereas here a config hiccup must not STOP a sale. At a counter the
		 * goods are already in the customer's hands, so the till keeps selling.
		 */
		window.posValidateStockOnSelect = byKey['pos.stock.validateOnSelect'] === true;
		window.kbdEnterSubmits   = ('ui.keyboard.enterSubmits' in byKey) ? byKey['ui.keyboard.enterSubmits'] : true;
		// The compact ROW is a SEPARATE setting from the keyboard flow. pos-keyboard.js addresses
		// fields by id, so Enter walks the sale on the stacked layout too. Fails closed.
		window.posRowLayoutEnabled = byKey['pos.entry.compactRow'] === true;
		// P2 (shortcut keys + the 12*CODE scan multiplier). Fails CLOSED for the same reason: never arm
		// function keys, or change what a '*' in a scanned code means, because a settings call hiccuped.
		window.posShortcutsEnabled = byKey['pos.keyboard.shortcuts.enabled'] === true;
		// P3 (quick-pick tiles). Fails CLOSED — never put an unexpected grid above the cart on a live till.
		window.posQuickPickEnabled = byKey['pos.quickpick.enabled'] === true;
		window.posQuickPickCount = posSettingInt(res, 'pos.quickpick.count', 9);
		window.posQuickPickDays = posSettingInt(res, 'pos.quickpick.days', 30);
		// Per-tenant sale-screen composition. One POS serves a corner shop, a wholesale distributor
		// and a pharmacy, so WHICH fields belong on the sale is the tenant's answer, not ours. Every
		// one of these fails OPEN (absent key => shown): the default is today's full screen, and a
		// config hiccup must never make a field the shop relies on silently disappear mid-sale.
		// The BUSINESS-TYPE preset resolves first, then the tenant's own switches override it.
		// See POS_PRESETS / posFieldsFor for why that ordering is the contract.
		window.posFields = posFieldsFor(posSettingText(res, 'pos.entry.preset', 'CUSTOM'), byKey, chosen);
		window.posPriceEditable   = byKey['pos.entry.priceEditable'] !== false;
		// Fails OPEN (absent => required), because required IS today's behaviour — an unreadable
		// config must not quietly stop a wholesaler's invoices from naming their account.
		window.posCustomerRequired = byKey['pos.customer.required'] !== false;
		window.posWalkInName = posSettingText(res, 'pos.customer.walkInName', 'Walk-in Customer');
		window.posDefaultQty      = posSettingInt(res, 'pos.entry.defaultQty', 1);
		window.posDefaultTender   = posSettingText(res, 'pos.tender.default', 'CASH');
		// INST-1 — one settings read, one answer. installment.js reads these rather than fetching again:
		// a second read is a second opinion about whether the feature exists.
		window.posInstallmentEnabled   = byKey['pos.installment.enabled'] === true;
		window.posInstallmentCount     = posSettingInt(res, 'pos.installment.defaultCount', 6);
		window.posInstallmentFrequency = posSettingText(res, 'pos.installment.frequency', 'monthly');
		if (typeof applyInstallmentVisibility === 'function') applyInstallmentVisibility();
		window.posDefaultCustomerMode = posSettingText(res, 'pos.customer.defaultMode', 'select');
		// Pharmacy: must a SEVERE drug interaction be acknowledged before dispensing? Fail-open like the others
		// means fail-SAFE here — absent key / config hiccup â‡’ the acknowledgement is still required.
		window.pharmaBlockSevere = ('pharmacy.interaction.blockSevere' in byKey) ? byKey['pharmacy.interaction.blockSevere'] : true;
		applyPosBarcodeVisibility();
		applyPosRowEntry();
		applyPosFieldVisibility();
		if (typeof applyPosKeyboard === 'function') applyPosKeyboard();
		if (typeof renderQuickPick === 'function') renderQuickPick();
	}, 'json').fail(function(){
		window.posBarcodeEnabled = false;       // fail CLOSED — a config hiccup must not conjure a scan box
		window.posAutoPrintReceipt = true; window.pharmaBlockSevere = true;
		window.posKeyboardEnabled = false;      // fail CLOSED — see above
		window.kbdFormNavEnabled = true;        // fail OPEN — losing form nav to a hiccup is the worse outcome
		window.kbdEnterSubmits = true;
		window.posShortcutsEnabled = false;     // fail CLOSED
		window.posQuickPickEnabled = false;     // fail CLOSED
		window.posQuickPickCount = 9;
		window.posQuickPickDays = 30;
		window.posFields = {};                  // {} => every field shown (the !== false defaults)
		window.posPriceEditable = true;
		window.posCustomerRequired = true;      // fail OPEN — required is today's behaviour
		window.posWalkInName = 'Walk-in Customer';
		window.posDefaultQty = 1;
		window.posDefaultTender = 'CASH';
		window.posDefaultCustomerMode = 'select';
		applyPosBarcodeVisibility();
		applyPosRowEntry();
		applyPosFieldVisibility();
		if (typeof applyPosKeyboard === 'function') applyPosKeyboard();
		if (typeof renderQuickPick === 'function') renderQuickPick();
	});
}

/** A non-BOOL setting's raw value from the catalog response, or `dflt` when absent/unreadable. */
function posSettingRaw(res, key){
	var items = (res && res.data) || [];
	for (var i = 0; i < items.length; i++) {
		if (items[i] && items[i].key === key) return items[i].value;
	}
	return null;
}
function posSettingInt(res, key, dflt){
	var v = parseInt(posSettingRaw(res, key), 10);
	// A zero or negative default quantity would put an unsellable line on every sale, so an
	// out-of-range configured value falls back rather than being honoured.
	return (isNaN(v) || v < 1) ? dflt : v;
}
function posSettingText(res, key, dflt){
	var v = posSettingRaw(res, key);
	return (v == null || String(v) === '') ? dflt : String(v);
}
/**
 * Keep the FEFO notice wrapper in step with the notices inside it.
 *
 * The wrapper is a full-width block sitting BETWEEN the QTY fields and the price fields — put there
 * because that is where loadStock() populates it. On the stacked form an empty wrapper costs nothing.
 * On the single-row till it is a full-width flex item, so an empty one CUTS THE ROW IN HALF: Stock,
 * Expiry, Discount and Type were pushed onto a second band with a gap in front of them.
 *
 * Done here rather than in CSS on purpose. A `:has()` rule looked tidy and did not work — the wrapper's
 * inner .col-* is itself a flex item and takes a line whether or not its children are visible. The two
 * alerts are already shown and hidden by this file, in five places; one helper called from those places
 * is honest, whereas a selector trying to infer the same state from inline styles is a second mechanism
 * that has to keep guessing right.
 */
function syncSellNoticeRow(){
	var $wrap = $("#sellBatchInfo").closest(".pos-fullrow");
	if(!$wrap.length) return;
	var anyVisible = $("#sellBatchInfo").is(":visible") || $("#sellSellableInfo").is(":visible");
	// A CLASS, not .toggle(): an inline display would fight pos-rowentry.css the way the config
	// visibility flags already learned not to.
	$wrap.toggleClass("pos-notice-empty", !anyVisible);
}

function applyPosBarcodeVisibility(){
	// === true, not !== false: an UNSET flag (config not loaded yet, or a screen reached before the settings
	// call returned) must read as OFF. `!== false` treated undefined as ON, which is why the scan box appeared
	// on tenants that had barcode scanning switched off.
	var on = window.posBarcodeEnabled === true;
	$('#sellScanRow').toggle(on);          // sell screen scan box
	$('#prodBarcodeLabel').toggle(on);     // product form Barcode label
	$('#prodBarcodeWrap').toggle(on);      // product form Barcode input
}

/**
 * UI/UX P1 — switch the sell form between today's stacked layout and the one-row line entry.
 *
 * This is the WHOLE mechanism: one class on one element. Everything else lives in
 * /css/pos-rowentry.css, every rule of which is scoped to `.pos-rowentry`, so with the flag off not
 * one declaration matches and the screen is byte-identical to today.
 *
 * Nothing here touches the form's CONTENT. No input is added, removed, renamed or reordered — the
 * row is a CSS re-flow of the very same controls, so formToJSON("Sell"), #addInviceItem,
 * calculateNetSell() and loadStock() keep reading and writing exactly the ids they always have.
 */
function applyPosRowEntry(){
	$('#sellDiv').toggleClass('pos-rowentry', window.posRowLayoutEnabled === true);
	// The notice row starts EMPTY — loadStock() has not run on a fresh screen, and never runs at all for
	// a shop that does not track batches. Without this the wrapper takes a full-width line from the
	// moment the till opens, which is the state the operator sees most of the time.
	syncSellNoticeRow();
}

/**
 * The sale line, per kind of shop — the named answers behind `pos.entry.preset`.
 *
 * WHY THESE EXIST. The nine per-field switches are correct and stay. What they are not is a question a
 * shopkeeper can answer: nine booleans describe 512 possible tills, none of them designed, and all nine
 * default ON — so a corner shop meets the busiest screen in the product and must switch nine things off
 * to reach the four fields it uses. "I run a pharmacy" is a question they can answer.
 *
 * WHAT A PRESET DOES NOT DECIDE. Column ORDER. A till is read by position; a shop that can reorder its
 * own columns has a screen no two of its staff read the same way. Presets choose which fields are
 * PRESENT — Item, Qty, Price, Total and the Add button are on every one of them.
 *
 * Each entry lists ONLY what it turns off. Anything unnamed keeps the platform default (shown), so a
 * field added to the product in future appears everywhere until a preset deliberately hides it —
 * failing OPEN, exactly as the individual switches do.
 */
var POS_PRESETS = {
	// Item · Qty · Price · Total. The four fields a counter sale actually needs.
	RETAIL: {
		description: false, bonus: false, stock: false, expiry: false,
		lineDiscount: false, discountType: false, receivable: false
	},
	// Adds BATCH and EXPIRY: on medicines these are a traceability obligation, not a nicety.
	// Veterinary is the same trade with the same rules, so it is the same preset.
	PHARMACY: {
		description: false, bonus: false, receivable: false,
		lineDiscount: false, discountType: false
	},
	// Adds BONUS (free goods, "20 billed 2 free") and the line discount a rep negotiates per product.
	DISTRIBUTION: {
		description: false, expiry: false
	},
	// Item and quantity. Price is fixed on a menu, and nobody discounts a plate of food at the counter.
	RESTAURANT: {
		description: false, bonus: false, stock: false, expiry: false,
		lineDiscount: false, discountType: false, receivable: false
	}
};

/**
 * Resolve the sale-screen field set: PRESET first, tenant overrides second.
 *
 * The ordering is the whole contract, and it is Strategy with an explicit escape hatch:
 *
 *   1. every field starts SHOWN (the platform default, and today's behaviour)
 *   2. the preset turns off what that kind of shop does not use
 *   3. anything the tenant has explicitly saved WINS over both
 *
 * Step 3 is what makes a preset safe to offer. Without it, choosing "Pharmacy" would silently destroy a
 * deliberate choice a shop had already made, and the only safe advice would be "never touch the preset
 * once configured" — which is not a setting, it is a trap. With it, a pharmacy that also gives bonus
 * goods switches Bonus back on and keeps it.
 *
 * CUSTOM is not an error case. It means "leave the nine exactly as they are", which is precisely what
 * every existing tenant needs on the deploy that introduces this: a new setting must not move a screen
 * somebody already configured.
 *
 * @param preset  the tenant's pos.entry.preset value
 * @param byKey   raw settings map; an ABSENT key means the tenant never chose, a present one that they did
 */
function posFieldsFor(preset, byKey, chosen){
	byKey = byKey || {};
	// Absent => nothing was explicitly saved, so the preset governs. Passing {} is what a unit test does.
	chosen = chosen || {};
	var base = POS_PRESETS[String(preset || '').toUpperCase()] || {};

	// key on window.posFields  ->  the setting that overrides it
	var MAP = {
		description:     'pos.entry.showDescription',
		bonus:           'pos.entry.showBonus',
		// SER-3d: the hook existed in the markup from SER-3 but was never mapped, so the field could not
		// be switched off however the setting was saved. Registering the key without this line would
		// have put a control in Configuration that does nothing.
		serial:          'pos.entry.showSerial',
		stock:           'pos.entry.showStock',
		expiry:          'pos.entry.showExpiry',
		lineDiscount:    'pos.entry.lineDiscountEnabled',
		discountType:    'pos.entry.showDiscountType',
		receivable:      'pos.entry.showReceivable',
		tradeDiscount:   'pos.invoice.tradeDiscountEnabled',
		customerBalance: 'pos.customer.showBalance',
		park:            'pos.park.enabled'
	};

	var out = {};
	Object.keys(MAP).forEach(function(field){
		var key = MAP[field];
		if (chosen[key]) {
			// The tenant SAVED this one, so it wins over the preset. Note the test is `chosen`, not
			// `key in byKey`: byKey carries every catalogue entry, defaults included, so presence proves
			// nothing. Keying on presence would have made the preset dead code — it would never once
			// have applied, and the feature would have looked implemented while doing nothing.
			out[field] = byKey[key] !== false;
		} else if (field in base) {
			out[field] = base[field];               // the preset's answer for this kind of shop
		} else {
			out[field] = true;                      // platform default: shown
		}
	});
	return out;
}

/**
 * Compose the sale screen for THIS business — the same POS serves a corner shop, a wholesale
 * distributor and a pharmacy, and they do not want the same fields.
 *
 * Every optional control carries `data-pos-field="<name>"` on its `.pos-cell` — the ONE element that
 * holds both the caption and the field, so they can never be hidden out of step. This walks those
 * hooks and hides the ones the tenant turned off.
 *
 * (Before the cell existed the attribute was repeated on the label AND its column, and the pair stayed
 * together only for as long as nobody edited one of them.)
 *
 * ⚠ HIDDEN IS NOT REMOVED, AND NEVER `disabled`.
 * formToJSON("Sell") builds the payload from `new FormData(form)`, which omits DISABLED controls but
 * INCLUDES ones hidden with display:none. So a hidden field still submits its value exactly as
 * before — `description` and `stock.bsellDiscountType` in particular are read straight out of
 * FormData. Disabling them instead would silently drop columns from the invoice. Anything that must
 * genuinely not apply (a line discount that is switched off) is CLEARED as well as hidden, so the
 * screen and the submitted document agree.
 */
function applyPosFieldVisibility(){
	var f = window.posFields || {};

	// DEPENDENT FIELDS. A control whose only job is to MODIFY another control has no meaning once that
	// other control is gone. The discount type (% or amount) says how the line discount is applied, so
	// a tenant who switched the line discount off was left with a chooser for a field that is not on
	// the screen — visually a control with nothing to control, and in the keyboard chain a dead stop
	// the cashier has to Enter past on every line.
	//
	// The reverse dependency was already handled below (no chooser => discounts are a fixed amount).
	// This is the same relationship read the other way round.
	//
	// DERIVED, never stored: this writes to a COPY, so the tenant's own discountType setting is left
	// alone and comes back intact the moment they switch the line discount on again. Mutating
	// window.posFields here would quietly turn a temporary consequence into a saved preference.
	if (f.lineDiscount === false) { f = $.extend({}, f, { discountType: false }); }
	// A CLASS, not .toggle(). jQuery's .toggle(true) → .show() sets an INLINE display:block whenever
	// the element is hidden by a stylesheet — which would beat pos-rowentry.css's `.pos-more{display:none}`
	// and drag fields back onto the compact row the moment settings were applied. The two mechanisms are
	// orthogonal (config decides IF a field exists, the row layout decides WHERE), so neither may write
	// inline styles the other has to fight.
	// SER-3d: the PURCHASE modal is walked too. `serial` is the first field that means the same thing on
	// both screens, and a tenant that hides it on the till but keeps it on goods-in would be describing a
	// distinction nobody asked for. Scoped to the two containers rather than the document, so a stray
	// data-pos-field elsewhere cannot be hidden by a setting that was never about it.
	$('#sellDiv [data-pos-field], #PurchaseModal [data-pos-field]').each(function(){
		var name = $(this).attr('data-pos-field');
		$(this).toggleClass('pos-hidden', f[name] === false);   // absent => shown (fail open)
	});

	// A switched-off line discount must not keep applying a value the cashier can no longer see.
	if (f.lineDiscount === false) { $('#sellDiscount').val(''); }
	if (f.discountType === false) {
		// No chooser => discounts are a fixed AMOUNT ("0"), not a stray percent from an earlier sale.
		$('#sellDiscountTypeDD').val('0');
		if ($('#sellDiscountTypeDD').data('selectpicker')) $('#sellDiscountTypeDD').selectpicker('refresh');
	}
	if (f.tradeDiscount === false) { $('#sellTradeDiscount').val(''); }
	if (f.bonus === false) { $('#sellBonus').val(''); }

	// Price: readonly, not disabled — a disabled input is dropped from FormData, which would strip
	// the rate off every line. Readonly still submits and still takes a programmatic .val().
	$('#sellSellRate').prop('readonly', window.posPriceEditable === false);

	// ── Defaults the tenant chose for a FRESH sale ────────────────────────────────────────────────
	// Guarded on "no sale in progress". These reset controls the cashier may have set deliberately —
	// and onCustomerModeChange() clears the customer field as it switches — so applying them while a
	// cart is part-rung would undo their work. This function now also runs when a setting is saved
	// (see saveBusinessConfigToggle), so that moment is real, not theoretical.
	var saleInProgress = (typeof data !== 'undefined' && data && data.length > 0) || window.editingInvoice;
	if (saleInProgress) return;

	// The one-shot latch stops a routine flag refresh from stomping a tender the cashier just picked.
	// An explicit config change CLEARS it (saveBusinessConfigToggle) so a newly chosen default lands
	// without a reload — the latch is about not fighting the operator, not about ignoring the owner.
	if (window.posDefaultTender && $('#sellPayMethod').length && !$('#sellPayMethod').data('posDefaulted')) {
		$('#sellPayMethod').val(window.posDefaultTender).data('posDefaulted', true);
		if ($('#sellPayMethod').data('selectpicker')) $('#sellPayMethod').selectpicker('refresh');
		if (typeof onSellPayMethodChange === 'function') onSellPayMethodChange();
	}
	// Apply WHICHEVER mode is configured, not only 'manual'. The earlier version could switch a till
	// into manual entry but never switch it back, so changing the setting to 'select' looked broken.
	if (typeof onCustomerModeChange === 'function') {
		var mode = (window.posDefaultCustomerMode === 'manual') ? 'manual' : 'select';
		onCustomerModeChange(mode);
	}
}

/* ── SER-3b: a serialled line is ONE unit ────────────────────────────────────────────────────────
 *
 * A serial number identifies ONE physical thing. Two handsets are two IMEIs, which is two lines — so the
 * moment a serial is entered, the quantity is 1 and there is nothing to decide.
 *
 * The server already enforces this from the other direction: SerialUnitService.validateForSale refuses a
 * line whose serial COUNT does not equal its quantity ("a purchase of three handsets with two IMEIs means
 * one unit is unaccounted for"). Before this, the cashier met that rule as a refusal AFTER pressing
 * Complete Sale, having typed a number the app could already work out.
 *
 * ⚠ READONLY, NOT DISABLED — and this is the whole reason this function is not two lines.
 * A DISABLED input is dropped by FormData and by jQuery's serialize, so the manual Add-to-Cart path
 * (formToJSON("Sell"), which reads the form BY NAME) would have posted a line with NO quantity at all. The
 * sale would have been recorded for zero, or refused, for a reason nothing on the screen could explain —
 * the same silent-drop shape that cost this codebase the bonus column and the GL outbox fields.
 * `readonly` cannot be typed into, is styled the same way, and is still submitted.
 */
function applySerialQuantityLock() {
	var $serial = $('#sellSerials');
	var $qty = $('#sellItems');
	if (!$serial.length || !$qty.length) return;

	var hasSerial = $.trim($serial.val() || '') !== '';
	if (hasSerial) {
		$qty.val(1).prop('readonly', true).addClass('is-locked-by-serial')
			.attr('title', t('ui.js.qtyLockedBySerial', 'One serial number is one unit.'));
	} else if ($qty.hasClass('is-locked-by-serial')) {
		// Only release a lock THIS rule applied. A quantity made readonly by anything else — a loose line,
		// a future rule — is not ours to unlock.
		$qty.prop('readonly', false).removeClass('is-locked-by-serial').removeAttr('title');
	}
}

// `input` rather than `change`, so a SCANNER — which fires no change event until focus leaves — locks the
// quantity the instant the code lands. A cashier scanning three handsets in a row never sees a stale 3.
$(document).on('input change', '#sellSerials', applySerialQuantityLock);

/*
 * Release the lock when the LINE is cleared, however it was cleared.
 *
 * After Add-to-Cart the sale line goes through the generic resetForm(), which empties #sellSerials WITHOUT
 * firing an input event — so a lock applied to the previous line would survive onto the next one, leaving
 * the cashier with a quantity stuck at 1 and nothing on screen explaining why.
 *
 * Bound to the form's own `reset` event rather than patched into each call site, so a reset path added later
 * is covered without anybody remembering this rule. Deferred by a tick because `reset` fires BEFORE the
 * browser clears the fields — reading the serial box during the event still returns the old value.
 */
$(document).on('reset', 'form', function () {
	setTimeout(function () {
		applySerialQuantityLock();
		// The PURCHASE lock too. newPurchase() resets the modal's form, and a lock left applied there would
		// open a fresh bill with the quantity stuck at 1 and the Pack/Box toggle missing — with nothing on
		// the screen to say why, because the serial that caused it has just been cleared.
		if (typeof applyPurchaseSerialQuantityLock === 'function') applyPurchaseSerialQuantityLock();
	}, 0);
});

/** Exposed so a caller that clears the line by hand can release the lock too. */
window.applySerialQuantityLock = applySerialQuantityLock;

/* ── OB-1: opening balances at cutover ───────────────────────────────────────────────────────────
 *
 * What each customer and supplier owed on the day this shop started using MaxTheService.
 *
 * WHY THERE IS NO CUTOVER-DATE FIELD HERE
 * It is an ordinary tenant setting (business.cutoverDate) and the Configuration screen self-renders it from
 * the catalog. A second control writing the same value is two answers to one question, and the one nobody
 * uses drifts. The link goes there instead.
 *
 * EVERY RULE IS THE SERVER'S. The cutover requirement, the lock, the org check, the part-paid refusal and
 * the owner/admin gate all live in business-service — a rule enforced in this file stops existing the moment
 * somebody posts the endpoint directly. What this file owns is telling the operator what happened.
 */
function showOpeningBalances(){
	$('.formDiv').hide();
	$('#OpeningBalanceDiv').show();
	$('#openingBalanceMsg').hide().empty();
	$('#obAmount').val('');
	$('#obReference').val('');
	obLoadParties();
	obLoadState();
}

/** The migration's state, in the operator's words rather than the setting's. */
function obLoadState(){
	$.get(serverContext + 'openingBalanceSummary').done(function(resp){
		var d = (resp && (resp.object || resp.data)) || {};
		var parts = [];
		if(d.cutoverDate){
			parts.push(t('ui.js.obCutoverIs','System of record from {0}').replace('{0}', escHtml(d.cutoverDate)));
		}else{
			// Said plainly and first: nothing can be recorded until this is set, and the operator should not
			// discover that by being refused.
			parts.push('<b>' + escHtml(t('ui.js.obNoCutover',
				'Set the cutover date before recording any balance.')) + '</b>');
		}
		if(d.locked === true){
			parts.push(escHtml(t('ui.js.obLocked',
				'Locked — balances have been recorded against this date.')));
		}
		var cust = Number(d.customerTotal || 0), supp = Number(d.supplierTotal || 0);
		if(cust > 0 || supp > 0){
			parts.push(t('ui.js.obEnteredSoFar','Entered so far: {0} from customers, {1} to suppliers.')
				.replace('{0}', cust.toLocaleString()).replace('{1}', supp.toLocaleString()));
		}
		$('#openingBalanceState').html(parts.join(' &middot; '));
	}).fail(function(){
		$('#openingBalanceState').text(t('ui.js.obStateUnavailable',
			'Could not read the migration state.'));
	});
}

/** Fill the party picker for whichever side is selected. */
function obLoadParties(){
	var isCustomer = $('#obPartyType').val() === 'customer';
	var url = isCustomer ? 'getUserCustomer?q=-1' : 'getUserVender?q=-1';
	$('#obParty').empty();
	$.get(serverContext + url).done(function(resp){
		var rows = (resp && (resp.collection || resp.data || resp.object)) || [];
		var html = '<option value="">' + escHtml(t('ui.js.obChoose','— choose —')) + '</option>';
		for(var i = 0; i < rows.length; i++){
			var r = rows[i];
			var id = isCustomer ? (r.customerId || r.id) : (r.venderId || r.id);
			var nm = isCustomer ? r.name : (r.venderName || r.name);
			// A party with no name cannot be recognised on a statement later, and a blank row in a picker is
			// how a fixture once drew customer 4663. Skip rather than offer it.
			if(id && nm && String(nm).trim()){
				html += '<option value="' + escHtml(id) + '">' + escHtml(nm) + '</option>';
			}
		}
		$('#obParty').html(html);
	});
}

function postOpeningBalance(){
	var isCustomer = $('#obPartyType').val() === 'customer';
	var partyId = $('#obParty').val();
	var amount = $('#obAmount').val();

	if(!partyId){ obMsg(t('ui.js.obPickParty','Choose who owes the money.'), false); return; }
	if(!(Number(amount) > 0)){ obMsg(t('ui.js.obNeedAmount','Enter the amount owed.'), false); return; }

	var body = { amount: amount, reference: $('#obReference').val() || '' };
	body[isCustomer ? 'customerId' : 'venderId'] = partyId;

	$('#obPost').prop('disabled', true);
	$.post(serverContext + 'postOpeningBalance', body).done(function(resp){
		$('#obPost').prop('disabled', false);
		var ok = resp && (resp.status === 'SUCCESS');
		// The SERVER'S SENTENCE, verbatim. Its refusals name the cutover date, the lock or the part-paid
		// rule and say what to do about each; replacing them with "could not save" would throw away the only
		// part of the message the operator can act on.
		obMsg(ok ? t('ui.js.obRecorded','Opening balance recorded.') : (resp && resp.message), !!ok);
		if(ok){
			$('#obAmount').val('');
			$('#obReference').val('');
			obLoadState();   // the totals and the lock both change on the first posting
		}
	}).fail(function(){
		$('#obPost').prop('disabled', false);
		obMsg(t('ui.js.obFailed','The opening balance could not be recorded.'), false);
	});
}

function obMsg(text, ok){
	$('#openingBalanceMsg')
		.removeClass('alert-success alert-danger')
		.addClass(ok ? 'alert-success' : 'alert-danger')
		.text(text || '')
		.show();
}

// ===== Owner Configuration (generic per-tenant settings, shared common-settings backend) =====
// Self-renders from the business-service catalog (/getBusinessConfig â†’ ApiResponse{data:[...]}): each row is one
// configurable policy grouped by section. A toggle saves immediately (/saveBusinessConfig key=&value=). Adding a
// new setting is a catalog entry in the service (BusinessSettingsCatalog) — no change here.
function showBusinessConfig(){
	$('.formDiv').hide();
	$('#ConfigDiv').show();
	$('#businessConfigMsg').hide();
	loadBusinessConfig();
}

/**
 * ONB-1 — change the tenant's business type, with a confirmation that names what changes.
 *
 * Reverts the select on cancel or on failure: a dropdown left showing a value that was never saved is how an
 * owner comes to believe their shop is configured one way while the till behaves another.
 */
/**
 * ONB-3 — the DATA consequences of a business-type change, as sentences.
 *
 * Mirrors platform.js's impactLines for the tenant's own screen. Kept as a second copy deliberately:
 * business.js and platform.js load on different pages and never together, so sharing would mean a third
 * script tag on both to save nine lines. If a third caller ever appears, extract it then.
 */
function shapeImpactLines(impact){
	var out = [];
	if(!impact) return out;
	var serial = Number(impact.productsRequiringSerial || 0);
	var plans  = Number(impact.openInstallmentPlans || 0);
	var owed   = Number(impact.installmentsOutstanding || 0);
	if(serial > 0){
		out.push(t('ui.js.impactSerial','{0} products require a serial number and will stop selling.')
			.replace('{0}', serial));
	}
	if(impact.productsTrackingBatch !== undefined && Number(impact.productsTrackingBatch) === 0){
		out.push(t('ui.js.impactNoBatches',
			'No products have a batch recorded — expiry ordering will have nothing to sort on.'));
	}
	if(plans > 0){
		out.push(t('ui.js.impactPlans','{0} open installment plans ({1}) stay collectable but leave the dashboard.')
			.replace('{0}', plans).replace('{1}', owed.toLocaleString()));
	}
	return out;
}

function saveBusinessShape(el){
	// NL, rather than a \n escape: see platform.js. A \n in this
	// position reached the built copy as a real line break and broke the whole file in the browser.
	var NL = String.fromCharCode(10);
	var shape = el.value;
	var previous = el.getAttribute('data-previous') || '';

	$.get(serverContext + 'getBusinessShapePreview', { shape: shape })
		.done(function(res){
			if(!apiOk(res)){ uiAlert(apiMessage(res, t('ui.js.saveFailed','Save failed'))); revert(); return; }
			var p = apiData(res) || {};
			var on = p.turningOn || [], off = p.turningOff || [];

			var lines = [];
			if(off.length) lines.push(t('ui.js.turningOff','Turning OFF') + ': ' + off.join(' · '));
			if(on.length)  lines.push(t('ui.js.turningOn','Turning ON')  + ': ' + on.join(' · '));
			// ONB-3 — the owner sees the same consequences the operator does, and arguably needs them more:
			// same power over their own shop, less context about what the switches do.
			shapeImpactLines(p.impact).forEach(function(l){ lines.push(NL + '! ' + l); });
			// Says so plainly when nothing changes. A dialog that always warns is one nobody reads.
			if(!lines.length) lines.push(t('ui.js.noSwitchesChange','No switches change for this business.'));

			uiConfirm({
				title: t('ui.js.changeBusinessType','Change business type'),
				message: t('ui.js.shapeResetsSwitches',
					'This resets the switches under "What this business does" to the defaults for the new '
					+ 'business type.') + '\n\n' + lines.join('\n')
			}).then(function(ok){
				if(!ok){ revert(); return; }
				$.post(serverContext + 'saveBusinessShape', { shape: shape }, function(res2){
					var saved = res2 && res2.success;
					if (typeof markSettingSaved === 'function') markSettingSaved(el, saved);
					if(!saved){ uiAlert(apiMessage(res2, t('ui.js.saveFailed','Save failed'))); revert(); return; }
					el.setAttribute('data-previous', shape);
					// Re-read the screen AND the capability map: the switches below have just been reset and
					// the sections this page hides are decided by capabilities.js.
					loadBusinessConfig();
					if (typeof reloadCapabilities === 'function') reloadCapabilities();
				}).fail(function(xhr){ uiAlert(apiFailMessage(xhr, t('ui.js.saveFailed','Save failed'))); revert(); });
			});
		})
		.fail(function(xhr){ uiAlert(apiFailMessage(xhr, t('ui.js.saveFailed','Save failed'))); revert(); });

	function revert(){ if(previous) el.value = previous; }
}

function loadBusinessConfig(){
	// Rendering lives in /js/common/settings-form.js — one renderer for all four dashboards, so a new
	// setting TYPE is added once rather than four times (this file used to carry its own copy).
	renderSettingsForm({
		container:  '#businessConfigBody',
		loadUrl:    'getBusinessConfig',
		onChangeFn: 'saveBusinessConfigToggle',
		fieldPrefix:'bcfg'
	});
	/*
	 * E5 - what MaxTheService support has done to this business.
	 *
	 * Shared renderer in /js/common/support-access.js: a support session is opened over ANY tenant, so the
	 * other three dashboards add this same line rather than a copy of the screen. It draws nothing at all
	 * when the business has never been accessed, so the ordinary case costs nothing.
	 *
	 * Guarded so a dashboard served without the shared file still renders its Configuration screen: this
	 * card is supplementary, and taking the settings list down with it would be a poor trade.
	 */
	if (typeof renderPlatformAccess === 'function') { renderPlatformAccess('#platformAccessBox'); }
}

function saveBusinessConfigToggle(el){
	var key = el.getAttribute('data-key');

	/*
	 * ONB-1 — the business type is not an ordinary setting, so it does not take the ordinary path.
	 *
	 * Every other switch on this screen is one row: post it, done. Changing the business type RE-APPLIES that
	 * shape's defaults, which means clearing every capability override the owner has set — so it is confirmed
	 * first, and the dialog names the switches that will change rather than asking "are you sure?".
	 *
	 * ⚠ This deliberately reverses C4's "an explicit override always wins". The objection to re-applying was
	 * that it would happen SILENTLY; naming the changes removes it. What C4 produced instead was a shape
	 * change that appeared to do nothing — a pesticide dealer picked "Pharmacy" and went on seeing
	 * installments, which is the complaint that started this slice.
	 */
	if (key === 'org.shape') { saveBusinessShape(el); return; }
	// Read the control BY TYPE. This used to be `el.checked ? 'true':'false'` unconditionally, which saved
	// "false" for every SELECT/INT/TEXT/MONEY entry in the catalog — a non-checkbox has no .checked.
	var value = (el.type === 'checkbox') ? (el.checked ? 'true' : 'false') : el.value;
	$.post(serverContext + 'saveBusinessConfig', { key: key, value: value }, function(res){
		var ok = res && res.success;
		// Confirm ON THE ROW as well as in the banner: with ~40 policies the top-of-page banner renders
		// off-screen for anything below the fold, so a successful save looked like nothing happened.
		if (typeof markSettingSaved === 'function') markSettingSaved(el, ok);
		$('#businessConfigMsg').removeClass('alert-success alert-danger')
			.addClass(ok ? 'alert-success' : 'alert-danger')
			.text(ok ? 'Saved.' : (apiMessage(res, 'Save failed'))).show();
		if(!ok){ if(el.type === 'checkbox'){ el.checked = !el.checked; } }   // revert the toggle if the save failed
		else {
			// Re-read the WHOLE catalog and re-apply, instead of naming each key here.
			//
			// This used to be an `else if` chain that knew exactly three keys (pos.barcode.enabled,
			// pos.receipt.autoPrint, pharmacy.interaction.blockSevere). Every setting added since — the
			// ~35 sale-screen ones, and pos.keyboard.shortcuts.enabled in particular — saved to the server
			// correctly and then did NOTHING until the page was reloaded, because window.pos* is only
			// populated by loadPosFeatureFlags() at load. The screen said "Saved." and the till did not
			// change, which is the worst kind of wrong: believable.
			//
			// A hand-maintained list of keys is a list someone forgets to join. Re-reading costs one GET
			// on a config screen — never a hot path — and cannot fall out of step with the catalog.
			//
			// Clear the tender latch first: it exists so a routine refresh does not overwrite a tender the
			// cashier chose, but an owner who has just changed the DEFAULT is entitled to see it applied.
			$('#sellPayMethod').removeData('posDefaulted');
			if (typeof loadPosFeatureFlags === 'function') loadPosFeatureFlags();
		}
	}).fail(function(){
		if (typeof markSettingSaved === 'function') markSettingSaved(el, false);
		// Same type guard as the success path: `.checked` is meaningless on a SELECT/INT/TEXT/MONEY
		// control, and flipping it there does not restore the value the user actually changed.
		if(el.type === 'checkbox'){ el.checked = !el.checked; }
		$('#businessConfigMsg').removeClass('alert-success').addClass('alert-danger').text('Save failed').show();
	});
}
/* ===== OMS O3 — Order settings (marketplace-service catalog) ==============================================
 * Design: microservices/docs/slices/oms-O3-order-config.md
 *
 * Delivery fees, the free-delivery threshold and cash-on-delivery used to be literals shared by EVERY store
 * on the platform. They are now per-org settings owned by marketplace-service; this screen is the same
 * self-renderer the Business Configuration screen uses, pointed at /getOrderConfig instead. Adding an order
 * policy is a catalog entry in MarketplaceSettingsCatalog — nothing here changes.
 * ======================================================================================================= */
function showOrderConfig(){
	$('.formDiv').hide();
	$('#OrderConfigDiv').show();
	$('#orderConfigMsg').hide();
	loadOrderConfig();
}

function loadOrderConfig(){
	renderSettingsForm({
		container:  '#orderConfigBody',
		loadUrl:    'getOrderConfig',
		onChangeFn: 'saveOrderConfigField',
		fieldPrefix:'ocfg'
	});
}

function saveOrderConfigField(el){
	// The shared saver reads the control by type, so a MONEY fee saves "250.00" and the COD tick saves "false".
	saveSettingsField(el, 'saveOrderConfig', function(ok, res){
		// Confirm on the row, same as Business Configuration — the banner is at the top of the screen.
		if (typeof markSettingSaved === 'function') markSettingSaved(el, ok);
		$('#orderConfigMsg').removeClass('alert-success alert-danger')
			.addClass(ok ? 'alert-success' : 'alert-danger')
			.text(ok ? 'Saved.' : (apiMessage(res, 'Save failed'))).show();
		// A rejected value must not stay on screen pretending to be in force — re-read the effective values.
		if(!ok){ loadOrderConfig(); }
	});
}

function openPeriodClose(){ showFinance('periodClose'); }

/* â•â• B2B Phase 4b — sales quotes (quote â†’ approval â†’ order) â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
 * Design: microservices/docs/slices/b2b-P4b-sales-quote-to-order.md
 *
 * A quote is an OFFER, not a calculation: numbered, time-limited, internally approved when the
 * discount is large, accepted by the customer, then CONVERTED into an invoice through the same sale
 * path the till uses. Every rule lives server-side — this screen only shows state and sends intent,
 * and it relays the server's own refusal text (e.g. "this quote expired on …") verbatim, because
 * that wording IS the operator's answer.
 */
var quoteLines = [];

/*
 * ── Dashboard KPI cards drill through to their list ───────────────────────────────────────────────────────
 *
 * Asked for by the user: *"by clicking on kpi-card user should be redirected to the detail list — click on
 * dashVenders or Vendors it should land on vendor list"*.
 *
 * A KPI card states a number a person immediately wants to interrogate: 43 vendors — WHICH forty-three? Until
 * now the answer meant reading the number, then finding the same thing again in a menu. The card was a
 * dead end that looked like a link.
 *
 * <h3>Why the target lives in the MARKUP</h3>
 * Each card column carries `data-drill="<selectId>:<sectionValue>"`, and this is the only handler. The
 * alternative — a lookup table in JS keyed by widget name — puts the card and its destination in two files
 * that have to be kept in step, and the next person to add a tile updates one of them.
 *
 * <h3>Two selects, because there are two kinds of section</h3>
 * Most cards land on a top-level section (`#registrationType`). Sales and Revenue land on the Sale Detail
 * Report, which is a sale SUB-section on `#sellType`. Both are driven by `.val(...).trigger('change')` —
 * the same thing `snavGo()` does — so a card arrives exactly as the menu does, and any wiring that hangs off
 * the change event (focus, lazy loads, report defaults) fires for both.
 */
function dashDrill(spec) {
	if (!spec) return;
	var parts = String(spec).split(':');
	if (parts.length !== 2) return;

	/*
	 * TWO navigation kinds, because the app genuinely has two.
	 *
	 * Most sections are options on a select. Products and Installment plans are opened by a function
	 * (showProducts / showInstallments) and have no option at all — a drill that only knew about selects
	 * would have silently done nothing on exactly the card the user named first.
	 */
	if (parts[0] === 'fn') {
		var fn = window[parts[1]];
		if (typeof fn !== 'function') return;     // screen not loaded for this user — see the note below
		fn();
	} else {
		var $sel = $('#' + parts[0]);
		// A capability-gated or privilege-gated section may not be on the page at all for this user. Silently
		// do nothing rather than half-navigating: the card is a shortcut, never the only route.
		if (!$sel.length || !$sel.find('option[value="' + parts[1] + '"]').length) return;
		$sel.val(parts[1]).trigger('change');
	}
	// The sections live above the fold on a tall dashboard; without this the screen changes off-screen and
	// the click reads as having done nothing.
	$('html, body').animate({ scrollTop: 0 }, 200);
}

$(document).on('click', '[data-drill]', function () {
	dashDrill($(this).data('drill'));
});

/*
 * Keyboard parity. A control that only a mouse can reach is not a control — and this app already ships a
 * keyboard-first POS, so a tile that swallows Enter would be the odd one out. `role`/`tabindex` are set here
 * rather than in the template so the affordance cannot exist without the handler that honours it.
 */
$(function () {
	$('[data-drill]').attr('role', 'button').attr('tabindex', '0');
});

$(document).on('keydown', '[data-drill]', function (e) {
	if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
		e.preventDefault();                       // Space would otherwise scroll the page
		dashDrill($(this).data('drill'));
	}
});

function showQuotes() {
	$('.formDiv').hide();
	$('#QuoteDiv').show();
	cancelQuoteForm();
	loadQuotes();
}

function loadQuotes() {
	$.get(serverContext + 'getUserQuotes', function (resp) {
		// GenericResponse puts a LIST in `collection` — never `data`.
		var list = (resp && resp.collection) ? resp.collection : [];
		var $b = $('#quoteBody').empty();
		$('#quoteEmpty').toggle(list.length === 0);
		list.forEach(function (q) {
			var tr = $('<tr>');
			tr.append($('<td>').text(q.quoteNo || ''));
			tr.append($('<td>').text(q.customerName || ''));
			tr.append($('<td>').text(q.customerPoNumber || ''));
			tr.append($('<td>').text(q.validUntil || ''));
			tr.append($('<td>').text(q.grandTotal != null ? Number(q.grandTotal).toFixed(2) : ''));
			tr.append($('<td>').html(quoteStatusBadge(q)));
			tr.append($('<td>').html(quoteActions(q)));
			$b.append(tr);
		});
	}).fail(function () { showFormError(t('ui.js.qtCouldNotLoad')); });
}

/** EXPIRED is derived server-side from validUntil, so the badge shows effectiveStatus, not the stored value. */
function quoteStatusBadge(q) {
	var s = q.effectiveStatus || q.status || '';
	var cls = 'default';
	if (s === 'SENT') cls = 'info';
	else if (s === 'ACCEPTED') cls = 'primary';
	else if (s === 'CONVERTED') cls = 'success';
	else if (s === 'PENDING_APPROVAL') cls = 'warning';
	else if (s === 'REJECTED' || s === 'EXPIRED') cls = 'danger';
	return "<span class='label label-" + cls + "'>" + escHtml(s) + '</span>';
}

/** Only the moves that are legal from the CURRENT state are offered — the server refuses the rest anyway. */
function quoteActions(q) {
	var s = q.effectiveStatus || q.status;
	var id = q.id;
	var btn = function (fn, label, style) {
		return "<button type=button class='btn btn-xs btn-" + style + "' style='margin-right:4px' onclick=\""
			+ fn + '(' + id + ')">' + escHtml(label) + '</button>';
	};
	/*
	 * Task #28 — Print and Download are on EVERY row, whatever the state.
	 *
	 * Unlike the moves below, these are not transitions: they produce the document. A rep prints a DRAFT to
	 * check it, sends the SENT one, files the ACCEPTED one against the customer's PO, and reprints the
	 * CONVERTED one when the invoice is queried months later. Offering them only in one state would mean the
	 * document exists only at the moment nobody needs it.
	 *
	 * The sheet is MARKED with its status (header band + watermark) so a printed DRAFT can never be mistaken
	 * for a firm offer — that marking, not a missing button, is what makes printing at every stage safe.
	 */
	var always = btn('printQuote', t('ui.js.qtPrint'), 'default')
		+ btn('downloadQuote', t('ui.js.qtDownload'), 'default');

	if (s === 'DRAFT') return always + btn('sendQuote', t('ui.js.qtSend'), 'primary')
		+ btn('submitQuoteForApproval', t('ui.js.qtSubmitApproval'), 'warning');
	if (s === 'PENDING_APPROVAL') return always + btn('approveQuote', t('ui.js.qtApprove'), 'success')
		+ btn('rejectQuote', t('ui.js.qtReject'), 'danger');
	if (s === 'SENT') return always + btn('acceptQuote', t('ui.js.qtAccept'), 'success')
		+ btn('rejectQuote', t('ui.js.qtReject'), 'danger');
	if (s === 'ACCEPTED') return always + btn('convertQuote', t('ui.js.qtConvert'), 'primary');
	if (s === 'CONVERTED') return always
		+ '<span class="text-muted">' + escHtml(q.convertedInvoiceNo || '') + '</span>';
	// REJECTED / EXPIRED: no moves left, but the document still prints — a rejected offer is a record of what
	// was offered, and someone will ask what the price was.
	return always;
}

function quoteAction(url, id, okKey) {
	$.ajax({
		type: 'POST', url: serverContext + url, dataType: 'json', data: { id: id },
		success: function (resp) {
			if (resp && resp.status === 'SUCCESS') { showSaleSuccess(t(okKey)); loadQuotes(); }
			// CONFIRM_REQUIRED = the group credit limit is breached in warn mode (4a). Ask, then re-send with
			// the acknowledgement — the same "warn = take confirmation" rule the till uses.
			else showFormError((resp && resp.message) || t('ui.js.qtCouldNotUpdate'));
		},
		error: function () { showFormError(t('ui.js.qtCouldNotUpdate')); }
	});
}

function sendQuote(id) { quoteAction('sendQuote', id, 'ui.js.qtSent'); }
function submitQuoteForApproval(id) { quoteAction('submitQuoteForApproval', id, 'ui.js.qtSubmitted'); }
function approveQuote(id) { quoteAction('approveQuote', id, 'ui.js.qtApproved'); }
function acceptQuote(id) { quoteAction('acceptQuote', id, 'ui.js.qtAccepted'); }
function rejectQuote(id) { quoteAction('rejectQuote', id, 'ui.js.qtRejected'); }
function convertQuote(id) { quoteAction('convertQuote', id, 'ui.js.qtConverted'); }

// â”€â”€ the raise form â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

function newQuoteForm() {
	quoteLines = [];
	renderQuoteLines();
	$('#qtPo').val(''); $('#qtDiscount').val(0); $('#qtQty').val(1); $('#qtRate').val('');
	// Reuse the sell screen's loaders — same endpoints, same option shape, one implementation.
	loadSellCustomers('qtCustomerDD');
	loadUserItems('qt');            // fills #qtItemDD
	$('#QuoteFormWrap').show();
}

function cancelQuoteForm() {
	quoteLines = [];
	renderQuoteLines();
	$('#QuoteFormWrap').hide();
}

function addQuoteLine() {
	var productId = $('#qtItemDD').val();
	var qty = parseFloat($('#qtQty').val());
	var rate = parseFloat($('#qtRate').val());
	if (!productId) { showFormError(t('ui.js.qtPickProduct')); return; }
	if (!(qty > 0)) { showFormError(t('ui.js.qtQtyPositive')); return; }
	if (!(rate >= 0)) { showFormError(t('ui.js.qtRateRequired')); return; }
	quoteLines.push({
		productId: Number(productId),
		productName: $('#qtItemDD option:selected').text(),
		quantity: qty, unitPrice: rate
	});
	$('#qtQty').val(1); $('#qtRate').val('');
	renderQuoteLines();
}

function removeQuoteLine(i) { quoteLines.splice(i, 1); renderQuoteLines(); }

function renderQuoteLines() {
	var $b = $('#qtLines').empty();
	quoteLines.forEach(function (l, i) {
		var tr = $('<tr>');
		tr.append($('<td>').text(l.productName));
		tr.append($('<td>').text(l.quantity));
		tr.append($('<td>').text(Number(l.unitPrice).toFixed(2)));
		tr.append($('<td>').text((l.quantity * l.unitPrice).toFixed(2)));
		tr.append($('<td>').html("<button type=button class='btn btn-xs btn-danger' onclick='removeQuoteLine("
			+ i + ")'>&times;</button>"));
		$b.append(tr);
	});
}

/** Send lines + customer + PO. Deliberately NO total — the server prices the document (same rule as OMS-5). */
function saveQuote() {
	if (!quoteLines.length) { showFormError(t('ui.js.qtNeedsALine')); return; }
	var body = {
		customerId: $('#qtCustomerDD').val() ? Number($('#qtCustomerDD').val()) : null,
		customerPoNumber: $('#qtPo').val(),
		tradeDiscount: parseFloat($('#qtDiscount').val()) || 0,
		lines: quoteLines
	};
	$.ajax({
		type: 'POST', url: serverContext + 'addQuote', contentType: 'application/json',
		dataType: 'json', data: JSON.stringify(body),
		success: function (resp) {
			if (resp && resp.status === 'SUCCESS') {
				showSaleSuccess(resp.message || t('ui.js.qtSaved'));
				cancelQuoteForm();
				loadQuotes();
			} else { showFormError((resp && resp.message) || t('ui.js.qtCouldNotSave')); }
		},
		error: function () { showFormError(t('ui.js.qtCouldNotSave')); }
	});
}

/* â•â• B2B Phase 4a — account groups (company â†’ branch â†’ contact) â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
 * Design: microservices/docs/slices/b2b-P4a-account-hierarchy.md
 *
 * SHARED POOL: a company sets one credit limit and its branches all draw on it. The hierarchy itself
 * lives in party-service; business-service stamps which customer row's limit governs each account, so
 * the sell path never crosses a service boundary to answer "whose limit applies?".
 *
 * Loading hooks #registrationType change rather than the sidebar link, because snavGo() sets that
 * select and fires change — one hook covers both navigation paths (same reason as education.js).
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'CustomerDiv') loadAccountGroups();
});

function loadAccountGroups() {
	if (!$('#AccountGroupCard').length) return;   // not owner/admin — the panel isn't rendered
	// Both selects list the same customers; the parent select also offers "no parent" (detach).
	$.get(serverContext + 'getUserCustomers', function (optionsHtml) {
		var html = String(optionsHtml || '');
		$('#agChild').html(html);
		$('#agParent').html("<option value=''>" + escHtml(t('ui.js.agNoParent')) + "</option>" + html);
		refreshAccountGroup();
	});
	loadUnbridgedCustomers();
}

/** Show the group the selected customer draws on: members, their dues, and the pooled exposure. */
function refreshAccountGroup() {
	var id = $('#agChild').val();
	if (!id) { $('#agGroupSummary').hide(); return; }
	// GenericResponse has NO `data` field: a Map payload lands in `object`, a Collection in `collection`
	// (the constructor overload decides). Reading `data` here silently yielded undefined.
	$.get(serverContext + 'customerAccountGroup?customerId=' + encodeURIComponent(id), function (resp) {
		var g = (resp && resp.object) ? resp.object : null;
		if (!g) { $('#agGroupSummary').hide(); return; }

		var rows = '';
		(g.members || []).forEach(function (m) {
			// The head carries the limit the whole group is measured against — worth marking.
			rows += '<tr><td>' + escHtml(m.name)
			      + (m.isHead ? " <span class='label label-primary'>" + escHtml(t('ui.js.agAccountHead')) + '</span>' : '')
			      + '</td><td>' + Number(m.dueAmount || 0).toFixed(2) + '</td></tr>';
		});
		$('#agMembers').html(rows);
		$('#agPooled').text(Number(g.pooledDue || 0).toFixed(2));

		// A group with no limit is not "limit 0" — it is unlimited, and saying so prevents a costly misread.
		if (g.creditLimit == null) {
			$('#agLimitNote').text(t('ui.js.agNoLimit'));
		} else {
			var headroom = Number(g.creditLimit) - Number(g.pooledDue || 0);
			$('#agLimitNote').text(t('ui.js.agLimit') + ' ' + Number(g.creditLimit).toFixed(2)
				+ ' Â· ' + t('ui.js.agHeadroom') + ' ' + headroom.toFixed(2));
		}
		$('#agGroupSummary').show();
	});
}
$(document).on('change', '#agChild', refreshAccountGroup);

/** Attach the selected customer to a parent (or detach it when no parent is chosen). */
function saveAccountParent() {
	var id = $('#agChild').val();
	if (!id) { showFormError(t('ui.js.agPickAccount')); return; }
	var parent = $('#agParent').val();
	if (parent && parent === id) { showFormError(t('ui.js.agSelfParent')); return; }

	$.ajax({
		type: 'POST', url: serverContext + 'setCustomerAccountParent', dataType: 'json',
		data: { customerId: id, parentCustomerId: parent || '', accountLevel: $('#agLevel').val() },
		success: function (resp) {
			// A guard rejection (cycle, cross-tenant parent, unbridged customer) comes back as FAILED with the
			// server's own wording — show it verbatim rather than a generic failure, because the reason is the
			// whole value of the message.
			if (resp && resp.status === 'SUCCESS') {
				showSaleSuccess(t('ui.js.agSaved'));
				refreshAccountGroup();
				loadUnbridgedCustomers();
			} else {
				showFormError((resp && resp.message) || t('ui.js.agCouldNotSave'));
			}
		},
		error: function () { showFormError(t('ui.js.agCouldNotSave')); }
	});
}

/**
 * Customers with no party link. They cannot join a group, and the programme plan flags best-effort
 * party bridging as the risk that would otherwise make a group's exposure quietly incomplete — so they
 * are shown rather than omitted.
 */
function loadUnbridgedCustomers() {
	// A List payload matches GenericResponse's Collection overload, so it arrives in `collection` — NOT in
	// `object` like the account-group Map above. Same response class, two different fields by payload type.
	$.get(serverContext + 'unbridgedCustomers', function (resp) {
		var list = (resp && resp.collection) ? resp.collection : [];
		if (!list.length) { $('#agUnbridgedWrap').hide(); return; }
		$('#agUnbridged').text(list.map(function (c) { return c.name; }).join(', '));
		$('#agUnbridgedWrap').show();
	});
}
