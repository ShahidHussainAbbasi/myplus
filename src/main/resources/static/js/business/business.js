var data=[]; // use a global for the submit and return data rendering in the examples
var tablesi;
var removed = false;
var tableSellReport;


$(document).ready(function() {
    // Sale Detail Report table. Columns (0-based): 0 Date, 1 Invoice#, 2 Product, 3 Qty, 4 List price,
    // 5 Unit price, 6 Line total, 7 Tax, 8 Net, 9 Customer, 10 Contact, 11 Payment, 12 Invoice due.
    tableSellReport = $('#tableSellReport').DataTable( {
        dom: 'Bfrtip',
        order: [[ 0, 'desc' ]],
        lengthMenu: [
            [ 10, 25, 50, -1 ],
            [ '10 rows', '25 rows', '50 rows', 'Show all' ]
        ],
        columnDefs: [ { targets: [3,4,5,6,7,8,12], className: 'num' } ],
        buttons: [
        	'pageLength',
            { extend: 'copyHtml5', footer: true, title: 'Sale Detail Report' },
            { extend: 'csvHtml5', footer: true, title: 'Sale Detail Report' },
            { extend: 'excelHtml5', footer: true, title: 'Sale Detail Report' },
            { extend: 'print', footer: true, title: 'Sale Detail Report' },
        	{ extend: 'pdfHtml5',
              orientation: 'landscape',
              pageSize: 'LEGAL',
              footer: true,
              title: 'Sale Detail Report'
            }
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

    $("#addInviceItem").off().click(function() {
//    	window.open(window.location.hostname + ':' + window.location.port+""+serverContext+"reports/createdocument.docx");
    //	window.print(window.location.hostname + ':' + window.location.port+""+serverContext+"reports/createdocument.docx");
    	//window.print("resources/file/2.docx");
//    	isLoaded();
//    	return false;
        //If all form's required fields are filled
    	validateForm();
        if(formValidated){
        	var obj  = formToJSON("Sell");
//        	obj = populateFormData();
        	obj.itemName = $( "#sellItemDD :selected" ).text();
			// M4e.1b (slice 98): the picker value IS the catalog productId now — the cart line keys by productId.
			var pickVal = $("#sellItemDD").val();
			obj.productId = pickVal;            // cart key + productId-native submission
			obj.itemId = pickVal;               // back-compat field (ignored once productId present; removed in M4e.5)
			obj.stock.itemId = obj.itemId;
			obj.stock.itemName = obj.itemName;
			// The rate this line SOLD at = the cashier's #sellSellRate (bound to stock.bsellRate). Surface it as
			// line.sellRate so the /addSell submission carries it → SagaSellService records the actual sold rate
			// (and snapshots the catalog price separately). Without this the sold rate was dropped (saga fell back
			// to the catalog price).
			obj.sellRate = (obj.stock && obj.stock.bsellRate != null) ? obj.stock.bsellRate : null;
			// var item = {"id":$("#sellItemDD").val(), "name":$( "#sellItemDD :selected" ).text()};
			// obj.item = item;

        	// (cart insert handled below: append, or replace-in-place when editing)
			var arr = [
				obj.productId,obj.itemName,obj.quantity,obj.stock.bsellRate,obj.stock.bsellDiscount,($("#sellrm").val()),"<button id='DII' onclick=UIT("+obj.productId+")>Del</button>"
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
			resetForm();
			resetBSDD('sellItemDD');
			// Cart changed (item added / qty updated) → recompute Change & Due from the live cart total
			// (#sellTotal). Standard POS: Due = bill − Received for THIS invoice.
			calculateChange();
        }else{
        	showFormError('Please select an item and enter a valid quantity.');
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

// ─── Edit an existing sale (invoice) ──────────────────────────────────────────
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
		// $("#sellRec").val(inv.paidAmount != null ? inv.paidAmount : '');
		$("#sellRec").val('');
		$("#sellCh,#sellDueThis").val('');         // cleared until the cashier re-enters Received
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
	// the list (e.g. deleted → orphaned sale), inject a one-off option so the sale stays editable.
	$.get(serverContext + "catalogProducts?size=2000", function(resp){
		var list = (resp && resp.data && resp.data.content) ? resp.data.content
		         : (Array.isArray(resp && resp.data) ? resp.data : []);
		var html = "<option value=''>Nothing Selected</option>";
		list.forEach(function(p){
			if (p.isActive === false) return;   // hide DEACTIVATED products from the picker — not sellable/purchasable
			html += "<option value='" + p.id + "' data-product='" + p.id + "' data-price='" + (p.sellingPrice != null ? p.sellingPrice : '') + "'>" + escHtml(p.name || ('Product #' + p.id)) + "</option>";
		});
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
		$('#sellItems').val(line.quantity);                   // keep the line's qty (loadStock won't override >0)
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

// ─── Team / Users (owner-only) ────────────────────────────────────────────────
// The company SUPER owner manages team members. Uses a custom show (not the generic .dropdown path)
// so it doesn't trigger loadDataTable for a non-existent "Team" entity.
function showTeam(){
	$('.formDiv').hide();
	$('#TeamDiv').show();
	$('#teamMsg').hide();
	loadTeamUsers();
}

// G3 (slice 35): org tax policy. Same direct-show pattern as showTeam (no DataTable entity).
function showTaxSettings(){
	$('.formDiv').hide();
	$('#TaxSettingDiv').show();
	loadTaxSetting();
}

function loadTaxSetting(){
	$.get(serverContext + "getTaxSetting", function(resp){
		var s = (resp && resp.object) ? resp.object : {};
		$('#taxEnabled').prop('checked', s.enabled === true);
		$('#taxMode').val(s.taxMode === 'INCLUSIVE' ? 'INCLUSIVE' : 'EXCLUSIVE');
		$('#taxDefaultRate').val(s.defaultRate != null ? s.defaultRate : '');
		$('#taxLabel').val(s.taxLabel != null ? s.taxLabel : 'Tax');
		$('#taxRegNo').val(s.taxRegNo != null ? s.taxRegNo : '');
	}).fail(function(){
		showFormError('Could not load tax settings.');
	});
}

function saveTaxSetting(){
	$.ajax({
		type: 'POST',
		url: serverContext + "saveTaxSetting",
		dataType: 'json',
		data: {
			'enabled': $('#taxEnabled').is(':checked'),
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
		error: function(){ showFormError('Could not save tax settings.'); }
	});
}

function loadTeamUsers(){
	$.get(serverContext + "team/users", function(resp){
		var users = (resp && resp.data) ? resp.data : [];
		var $tb = $('#tableTeam tbody').empty();
		if(!users.length){ $tb.append('<tr><td colspan="4" class="text-center">No team members yet.</td></tr>'); return; }
		users.forEach(function(u){
			$tb.append('<tr><td>' + escHtml(u.name || '') + '</td><td>' + escHtml(u.email || '')
				+ '</td><td>' + escHtml(u.role || '') + '</td><td>' + (u.enabled ? 'Active' : 'Pending') + '</td></tr>');
		});
	}).fail(function(){
		$('#tableTeam tbody').html('<tr><td colspan="4" class="text-center">Could not load the team.</td></tr>');
	});
}

function addTeamUser(){
	var body = {
		firstName: ($('#teamFirstName').val() || '').trim(),
		lastName:  ($('#teamLastName').val()  || '').trim(),
		email:     ($('#teamEmail').val()     || '').trim(),
		role:      $('#teamRole').val()
	};
	if(!body.email){ teamMsg('Please enter an email.', true); return; }
	$.ajax({
		type: 'POST', url: serverContext + 'team/users', contentType: 'application/json',
		data: JSON.stringify(body), dataType: 'json',
		success: function(resp){
			if(resp && resp.data && resp.data.userId){
				teamMsg('Team member added — a set-password email was sent to ' + body.email + '.', false);
				$('#teamFirstName,#teamLastName,#teamEmail').val('');
				loadTeamUsers();
			} else {
				teamMsg((resp && resp.message) || 'Could not add the team member.', true);
			}
		},
		error: function(xhr){
			var m = (xhr && xhr.responseJSON && xhr.responseJSON.message) || 'Could not add the team member. Please try again.';
			teamMsg(m, true);
		}
	});
}

function teamMsg(msg, isErr){
	$('#teamMsg').removeClass('alert-success alert-danger')
		.addClass(isErr ? 'alert-danger' : 'alert-success').html(escHtml(msg)).show();
}

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
	datatable = $("#table" + tableV).DataTable({
		lengthMenu: [[5, 20, 50, 100, -1], ['5', '20', '50', '100', 'All']],
		"iDisplayLength": offset,
		"pageLength": (offset == -1 ? 100 : Number(offset)),
		"order": [[0, "desc"]],
		"autoWidth": true,
		dom: 'Bfrtip',
		buttons: [
			'pageLength',
			{extend: 'excelHtml5', footer: true},
			{extend: 'print', footer: true},
			{
				extend: 'pdfHtml5',
				orientation: 'landscape',
				pageSize: 'LEGAL',
				footer: true
			}
		],
		"ajax": {
			// Load ALL records so DataTables handles Next/Back pagination and search locally.
			// Search: DataTables filters the loaded set first; re-open the section to refresh from DB.
			"url": serverContext + "getUser" + getAll + "?q=-1" + ((getAll === "Product" && window.productShowInactive) ? "&includeInactive=true" : ""),
			"type": "GET",
			"success": function(data) {
				if(reload != tableV) reload = tableV;

				// Preload associated dropdowns once per entity type
				if (getAll == "Vender") {
					loadUserCompanies(table);
				} else if (getAll == "Item") {
					loadUserCompanies(table);
					loadUserVenders(table);
				} else if (getAll == "Purchase") {
					loadUserItems(table);
				} else if (getAll == "Sell") {
					loadUserItems(table);
					loadSellCustomers();
				}

				var collections = data.collection;
				if(!collections || collections.length <= 0){
					datatable.columns([0]).visible(false);
					$(".dataTables_empty")[0].innerHTML = "No Data Found";
					return false;
				}

				userId = collections[0].userId;
				datatable.columns([0]).visible(false);

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
							"<div id=venderCompanyDD>"+escHtml(obj.companyName)+"</div>",
							"<div id=venderPhone>"+escHtml(obj.phone)+"</div>","<div id=venderMobile>"+escHtml(obj.mobile)+"</div>",
							"<div id=venderEmail>"+escHtml(obj.email)+"</div>","<div id=venderAddress>"+escHtml(obj.address)+"</div>",obj.datedStr
						]);
					});
					$("#venderName").prop("readonly", false);
				} else if (getAll === "Customer") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=customerId>"+obj.customerId+"</div>","<input type='checkbox' value="+ obj.customerId+ ">",
							"<div id=customerName>"+escHtml(obj.name)+"</div>","<div id=contact>"+escHtml(obj.contact)+"</div>",
							"<div id=email>"+escHtml(obj.email)+"</div>","<div id=address>"+escHtml(obj.address)+"</div>",
							"<div id=dueAmount>"+(obj.dueAmount!=null?obj.dueAmount:0)+"</div>",obj.updated,
							"<button type=button class='btn btn-xs btn-primary rcv-pay-btn' data-cid='"+obj.customerId+"' data-name=\""+escHtml(obj.name||'')+"\" data-due='"+(obj.dueAmount!=null?obj.dueAmount:0)+"' title='Receive a payment against this customer'><span class='glyphicon glyphicon-usd'></span> Receive</button>"
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
							"<div id=purchaseQuantity>"+obj.quantity+"</div>",
							"<div id=purchasePurchaseRate>"+obj.stock.bpurchaseRate+"</div>","<div id=purchaseSellRate>"+obj.stock.bsellRate+"</div>",
							"<div id=purchaseDiscountTypeDD>"+obj.stock.bpurchaseDiscountType+"</div>",
							"<div id=purchaseDiscount>"+obj.stock.bpurchaseDiscount+"</div>",
							// "<div id=purchaseTotalAmount>"+obj.totalAmount+"</div>",
							// "<div id=purchaseNetAmount>"+obj.netAmount+"</div>",
							"<div id=purchaseExpiry>"+obj.stock.bexpDate+"</div>","<div id=purchaseDate>"+obj.updated+"</div>"
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
						allRows.push([
							"<div id=sellId>"+obj.sellId+"</div>",
							"<div id=sellInvoiceNo>"+escHtml(ch ? (ch.invoiceNo || '') : '')+"</div>",
							"<div id=sellCustomerName>"+escHtml(custName)+"</div>",
							"<div id=sellItemName>"+escHtml(obj.itemName||'')+"</div>",
							"<div id=sellItems>"+obj.quantity+"</div>",
							"<div id=sellItemExpiry>"+(obj.stock&&obj.stock.bexpDate!=null?obj.stock.bexpDate:'')+"</div>",
							"<div id=sellPurchaseRate>"+(obj.stock&&obj.stock.bpurchaseRate!=null?obj.stock.bpurchaseRate:'')+"</div>","<div id=sellSellRate>"+(obj.stock&&obj.stock.bsellRate!=null?obj.stock.bsellRate:(obj.sellRate!=null?obj.sellRate:''))+"</div>","<div id=sellCatalogPrice>"+(obj.catalogPrice!=null?obj.catalogPrice:'')+"</div>",
							"<div id=sellDiscountTypeDD>"+(obj.stock&&obj.stock.bsellDiscountType!=null?obj.stock.bsellDiscountType:'')+"</div>","<div id=sellDiscount>"+(obj.stock&&obj.stock.bsellDiscount!=null?obj.stock.bsellDiscount:(obj.discount!=null?obj.discount:''))+"</div>",
							"<div id=sellTotalAmount>"+obj.totalAmount+"</div>",
							"<div id=sellDueAmount>"+owed.toFixed(2)+"</div>",
							"<div id=sellNetAmount>"+obj.netAmount+"</div>",
							obj.updated,
							"<div id=sellTaxAmount>"+(obj.taxAmount!=null?obj.taxAmount:'')+"</div>",
							"<div id=sellPaymentMode>"+escHtml(ch&&ch.paymentMode?ch.paymentMode:'')+"</div>",
							// Actions: G6 (slice 38) Print receipt + G2 (slice 34) Sale Return. Print uses the
							// invoice number; Return passes its row data via data-* for the partial-qty dialog.
							((ch && ch.invoiceNo)
								? "<button type='button' class='btn btn-xs btn-default' title='Print receipt' onclick=\"printReceipt('"+escHtml(ch.invoiceNo)+"')\"><span class='glyphicon glyphicon-print'></span></button> "
								: "")
							+ "<button type='button' class='btn btn-xs btn-warning' onclick='openSaleReturn(this)'"
								+ " data-sellid='"+obj.sellId+"'"
								+ " data-stockid='"+(obj.stock&&obj.stock.stockId!=null?obj.stock.stockId:'')+"'"
								+ " data-qty='"+(obj.quantity!=null?obj.quantity:'')+"'"
								+ " data-invoice='"+escHtml(ch?(ch.invoiceNo||''):'')+"'"
								+ " data-item='"+escHtml(obj.itemName||'')+"'>"
								+ "<span class='glyphicon glyphicon-share-alt'></span> Return</button>"
						]);
					});
				} else if (getAll === "Product") {
					// Product master row, rendered through the shared DataTable (same path as Customer). Columns:
					// [id(hidden), checkbox, name, sku, unit, price, tax%, category, on-hand(lazy), add-stock control].
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=productId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=name>"+escHtml(obj.name || '')+"</div>","<div id=sku>"+escHtml(obj.sku || '')+"</div>",
							"<div id=unit>"+escHtml(obj.unit || '')+"</div>",
							"<div id=sellingPrice>"+(obj.sellingPrice != null ? Number(obj.sellingPrice).toFixed(2) : '')+"</div>",
							"<div id=taxRate>"+(obj.taxRate != null ? Number(obj.taxRate).toFixed(2) : '')+"</div>",
							"<div id=categoryName>"+escHtml(obj.categoryName || '')+"</div>",
							"<div id=stk_"+obj.id+" class=prod-onhand>…</div>",
							"<input type=number min=0 step=any id=addstk_"+obj.id+" class='form-control input-sm prod-addstk' style='width:80px;display:inline-block'>"
								+ "<button type=button id=addstkbtn_"+obj.id+" class='btn btn-xs btn-success' style='margin-left:4px' title='Add to on-hand' onclick='addProductStock("+obj.id+")'><span class='glyphicon glyphicon-plus'></span></button>"
								+ "<button type=button id=lessstkbtn_"+obj.id+" class='btn btn-xs btn-warning' style='margin-left:4px' title='Correct / reduce on-hand' onclick='adjustProductStock("+obj.id+")'><span class='glyphicon glyphicon-minus'></span></button>",
							(obj.isActive === false
								? "<span class='label label-default'>Inactive</span> <button type=button class='btn btn-xs btn-info' style='margin-left:6px' onclick='reactivateProduct("+obj.id+")' title='Reactivate this product'><span class='glyphicon glyphicon-refresh'></span> Reactivate</button>"
								: "<span class='label label-success'>Active</span>")
						]);
					});
				}
				// Single draw — much faster than calling draw() on every row.add()
				datatable.rows.add(allRows).draw();

				// Product on-hand is inventory (not catalog). Fill EVERY row's on-hand in ONE batch call
				// (/productStockLevels → inventory /stock/levels/detail) instead of a per-row /productStock request.
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
							var html = "<span title='" + onHand + " physical on-hand'>" + sellable + "</span>";
							if (expired > 0) {
								html += " <span class='label label-danger' style='margin-left:4px' title='" + expired
									+ " unit(s) in expired batches — physically present but not sellable'>" + expired + " expired</span>";
							}
							el.html(html);   // numbers only (no user data) → XSS-safe
						});
					}).fail(function(){
						$.each(collections, function(ind, obj){ $('#stk_' + obj.id).text('—'); });
					});
				}
			},
			error: function(jqXHR, textStatus, errorThrown) {
				console.log(jqXHR, textStatus, errorThrown);
				window.location.href = serverContext + "login?message=" + errorThrown;
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

function loadSellCustomers() {
	var dd = $("#sellCustomerDD");
	dd.empty().append('<option value=""> Select Customer </option>');
	$.get(serverContext + "getUserCustomer", function(res) {
		if (res && res.collection) {
			$.each(res.collection, function(i, c) {
				dd.append('<option value="' + c.customerId + '" data-contact="' + escHtml(c.contact || '') + '" data-due="' + (c.dueAmount != null ? c.dueAmount : 0) + '">' + escHtml(c.name) + '</option>');
			});
		}
	}).fail(function() {
		console.log("Error loading customers for sell dropdown");
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
	} else {
		$("#sellCN").val('');
		$("#sellCC").val('');
		window.selectedCustomerDue = null;                   // no account context (nothing picked)
	}
	refreshAccountDuePreview();
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
    $.getJSON(serverContext + 'getBusinessDashboardStats', function(res) {
        if (res.status === 'SUCCESS' && res.object) {
            var s = res.object;
            $('#dashCompanies').text(s.companies);
            $('#dashVenders').text(s.venders);
            $('#dashCustomers').text(s.customers);
            $('#dashItems').text(s.items);
            $('#dashMonthlySales').text(s.monthlySales);
            $('#dashMonthlyRevenue').text(s.monthlyRevenue);
        }
    }).fail(function() {
        console.log('Error loading dashboard stats');
    });
    loadDashboardCharts();
}

var _chartTrend = null, _chartDaily = null, _chartTopItems = null, _chartCustSales = null;

function loadDashboardCharts() {
    $.getJSON(serverContext + 'getDashboardChartData', function(res) {
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

function loadUserVenders(table) {	
    $.get(serverContext+ "getUserVenders",function(data){
    	$("#"+table.toLowerCase()+"VenderDD").empty().append(data).selectpicker('refresh');
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
	$.get(serverContext + "catalogProducts?size=2000", function(resp){
		var list = (resp && resp.data && resp.data.content) ? resp.data.content
		         : (Array.isArray(resp && resp.data) ? resp.data : []);
		var html = "<option value=''>Nothing Selected</option>";
		list.forEach(function(p){
			if (p.isActive === false) return;   // hide DEACTIVATED products from the picker — not sellable/purchasable
			html += "<option value='" + p.id + "' data-product='" + p.id + "' data-price='" + (p.sellingPrice != null ? p.sellingPrice : '') + "'>" + escHtml(p.name || ('Product #' + p.id)) + "</option>";
		});
		$("#"+table+"ItemDD").empty().append(html);
		if($("#"+table+"ItemDD").data('selectpicker')) $("#"+table+"ItemDD").selectpicker('refresh');
	})
	.fail(function() {
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
		var r = confirm("Please reivew your Sell and Purchase unit prices");
		if (r != true){
			$("#itemSellAmount").val(0.0);
			$("#itemPurchaseAmount").val(0.0);
		}
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
	$("#sellSellableInfo").hide().empty();   // sellable/expired badge, refreshed on each item pick
	$("pdt").html("      ");
	
    // M4e.1b (slice 98): the picker value is a productId now → pre-fill from productStock (on-hand + price + FEFO
    // batches + description, by productId) instead of getStock(itemId).
    var catalogSellPrice = $("#"+(tableV?tableV.toLowerCase():'')+"ItemDD :selected").attr('data-price');
		// Fill the sell rate IMMEDIATELY from the catalog price (data-price on the option), independent of the async
		// on-hand/batch fetch below — so it shows on select even if /productStock is slow or unavailable.
		if (catalogSellPrice != null && catalogSellPrice !== '') {
			if (tableV=="Purchase") $("#purchaseSellRate").val(catalogSellPrice);
			else if (tableV=="Sell") $("#sellSellRate").val(catalogSellPrice);
		}
	    $.get(serverContext+ "productStock?productId="+value,function(data){
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
        		$("#sellDiscountTypeDD").val(discountType);
	    		if(batchStock <= 0){
	    			$("#sellItems").addClass("alert-danger");
 	    			showFormError('No stock available. Please purchase this item first.');
	    			resetBSDD('sellItemDD');
	    			return false;
	    		}else{
		    		$("#sellStock").val(batchStock);
		    		$("#sellItemDesc").val(data.desc);
			    	$("#bexpDate").val(data.bexpDate);
		    		$("#sellPurchaseRate").val(data.bpurchaseRate);
			    	// $("#sellSellRate").val((data.bsellRate!=null && data.bsellRate!=='') ? data.bsellRate : (catalogSellPrice||''))
					$("#sellSellRate").val((catalogSellPrice!=null && catalogSellPrice!=='') ? catalogSellPrice : (data.bsellRate||''))
			    	$("#sellDiscount").val(discountValue);
			    	if($("#sellItems").val()*1<=0){
			    		$("#sellItems").val(1);
			    	}
			    	$("#sellItemDesc").val(data.idesc);
			    	renderSellBatches(data.batches);   // P10: show the FEFO batch/expiry being dispensed
			    	calculateNetSell();
			    	// Sellable guard: re-key the qty guard to SELLABLE stock (non-expired, non-held) — what a sale can
			    	// actually reserve — so the cashier can't over-sell into expired/held stock the server would reject.
			    	// Also surfaces "Sellable: N (+ expired)" on the form.
			    	$.get(serverContext+"productSellable?productId="+value, function(sd){
			    		var sellable = (sd && sd.success && sd.sellable!=null) ? Number(sd.sellable) : Number(batchStock||0);
			    		var expired  = (sd && sd.success && sd.expired!=null)  ? Number(sd.expired)  : 0;
			    		batchStock = sellable;
			    		$("#sellStock").val(sellable);
			    		var badge = 'Sellable: <b>'+sellable+'</b>';
			    		if(expired>0) badge += ' <span class="label label-danger" title="expired stock is not sellable">'+expired+' expired</span>';
			    		$("#sellSellableInfo").html(badge).show();
			    		if(sellable <= 0){
			    			$("#sellItems").addClass("alert-danger");
			    			showFormError(expired>0 ? 'All stock for this item is expired — not sellable. Add a fresh batch to sell.' : 'No sellable stock. Please purchase this item first.');
			    			resetBSDD('sellItemDD');
			    			$("#sellSellableInfo").hide();
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
		// M4e.1b (slice 98): the picker value is a productId now → ask the server by productId (inventory batches +
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
	        		$("#sellDiscountTypeDD").val(discountType);
		    		if(batchStock <= 0){
		    			$("#sellItems").addClass("alert-danger");
 		    			showFormError('No stock available. Please purchase this item first.');
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
	$.get(serverContext + "productStock?productId=" + encodeURIComponent(pid), function(data){
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
// changing 9→5 on a 140 on-hand previews 136. ADD: just mirrors the current live on-hand.
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

function calculateNetPurchase(){
	var p = $("#purchasePurchaseRate").val()*ONE;
	var s= $("#purchaseSellRate").val()*ONE;
	var qty= $("#purchaseQuantity").val()*ONE;
	discountType = $("#discountTypeDD :selected").val();
	var purchaseDiscount = $("#purchaseDiscount").val()*1>0?$("#purchaseDiscount").val()*ONE:0;
	var purchaseTotalAmount = $($("#purchaseTotalAmount").val(parseFloat(qty * p).toFixed(2))).val();
	updatePurchaseProjectedOnHand();   // "Stock In Hand" = live on-hand (add) / projected after-save on-hand (edit)
	if(discountType == "%"){
		//Discount  =  List Price Ã— Discount Rate 
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

function calculateNetSell(){
	var p = $("#sellPurchaseRate").val()*ONE;
	var s= $("#sellSellRate").val()*ONE;
	$("#sellItems").removeClass("alert-danger");
	var qty= $("#sellItems").val()*1>0?$("#sellItems").val()*ONE:1;
	discountType = $("#sellDiscountTypeDD :selected").val();
	// editing an existing sale → trust the displayed stock; key on editingInvoice, not the shared `edit`
	// global (which resetBSDD flips off after every cart add).
	if(window.editingInvoice){
		batchStock = $("#sellStock").val()*ONE;
	}
	$("#sellStock").val(batchStock);
	if(batchStock < qty){
		$("#sellItems").addClass("alert-danger");
 		showFormError('Quantity exceeds available stock. Please reduce the quantity or purchase more stock.');
		return false;
	}
	var sellDiscount= $("#sellDiscount").val()*1>0?$("#sellDiscount").val()*ONE:0;
	sellTotalAmount = parseFloat(qty * s).toFixed(2);
	if(discountType*ONE == 1){
		//Discount  =  List Price Ã— Discount Rate 
		sellDiscount =  sellTotalAmount * (sellDiscount*1 / 100);
	}else{
		// sellDiscount = sellTotalAmount - sellDiscount;
		//$("#sellDiscount").val(sellDiscount);
	}
	var profit = parseFloat(sellTotalAmount- (p*qty) - sellDiscount).toFixed(2);
	$("#sellNetAmount").val(profit);
	if(profit<=0)
		$("#sellNetAmount").addClass("alert-danger");
	else
		$("#sellNetAmount").removeClass("alert-danger");
	
	$("#sellTotalAmount").val(sellTotalAmount);
	$("#sellrm").val($("#sellTotalAmount").val()-sellDiscount);
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
// 		//Discount  =  List Price Ã— Discount Rate 
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
 		showFormError('Please select a valid sold item record to return.');
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

	$("#dueDateTemp").hide();
	$('#displayDateWrapper').hide();

	var recAm = ($("#sellRec").val() * ONE) || 0;
    // P12 (slice 59): insurance covers part of the bill; the patient only owes the remainder (the co-pay).
    var insured = ($("#sellInsured") && $("#sellInsured").val() ? $("#sellInsured").val() * ONE : 0) || 0;
    var sellTotal = ($("#sellTotal")[0] ? $("#sellTotal")[0].innerHTML * ONE : 0) || 0;
    var change = recAm + insured - sellTotal;

    // sellCh keeps the SIGNED change/due (received − bill) — addSell submits this as customer.dueAmount.
    // Do not change its meaning; the display fields below are derived from it.
    $("#sellCh").val(change);

    // Due (this sale) = positive amount still owed on the current cart (0 when fully paid/overpaid).
    var dueThis = change < 0 ? -change : 0;
    $("#sellDueThis").val(dueThis.toFixed(2));

    // Account preview (existing customer only): previous balance + this sale = new total outstanding.
    refreshAccountDuePreview(dueThis);

    if (change < 0) {
        // Customer owes money — show due date field
        $("#dueDateTemp").show();
        $('#displayDateWrapper').show();
    } else {
        // Fully paid — hide due date field
        $("#dueDateTemp").hide();
        $('#displayDateWrapper').hide();
    }
}

// Show the running-balance impact for a known (dropdown-selected) customer. window.selectedCustomerDue
// holds their current outstanding balance; null for a walk-in/manual customer or while editing, in which
// case the account row stays hidden. Re-derives this sale's due if not passed (e.g. on customer select).
function refreshAccountDuePreview(dueThis) {
	if (dueThis == null) {
		var recAm = ($("#sellRec").val() * ONE) || 0;
		var sellTotal = ($("#sellTotal")[0] ? $("#sellTotal")[0].innerHTML * ONE : 0) || 0;
		var ch = recAm - sellTotal;
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

// ─── Sale Detail Report ───────────────────────────────────────────────────────
// Number/money/escape helpers (function declarations → hoisted, so the DataTable footerCallback
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

// Show/hide the custom date-range pickers based on the Period select (0 = current month, 4 = custom).
// The eonasdan datetimepicker mis-initialises when bound to a display:none field (its global
// document-ready init runs while these wrappers are hidden), so the calendar never opens. Re-create
// the pickers the moment they become visible. Format stays dd-MM-yyyy HH:mm:ss — the backend's
// getDateTime() parses sd/ed strictly with that pattern.
function initSRDatePickers(){
	['#srsd', '#sred'].forEach(function(sel){
		var $el = $(sel);
		if (!$el.length) return;
		try { if ($el.data('DateTimePicker')) $el.data('DateTimePicker').destroy(); } catch (e) {}
		$el.datetimepicker({
			format: 'DD-MM-YYYY HH:mm:ss',
			useCurrent: false,
			showTodayButton: true,
			showClear: true,
			showClose: true,
			toolbarPlacement: 'top'
		}).on('dp.show', function(){
			var dtp = $(this).data('DateTimePicker');
			if (dtp && dtp.date() === null) dtp.date(moment());
		});
	});
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

function loadSR(){
	tableSellReport.clear().draw();
	$('#srKpis').hide();
	clearFormError();
	// Self-contained params (the report bypasses the shared form-scan machinery): rp = period,
	// sd/ed = custom range. Backend contract unchanged.
	var rp = $('#dateRangeDDSR').val();
	var sd = $('#srsd').val();
	var ed = $('#sred').val();
	if (rp === '4' && !sd && !ed){
		showFormError('Please pick a start and/or end date for the custom range.');
		return;
	}
	$.ajax({
		type : "POST",
		url : serverContext + "loadSR",
		dataType : "json",
		data : { rp: rp, sd: sd, ed: ed },
		success : function(data) {
			if(data.status!=="SUCCESS"){
				showFormError((data.status || '') + (data.message ? ': ' + data.message : ''));
				return;
			}
			var rows = (data && data.collection) ? data.collection : [];
			if(!rows.length){
				showFormError('No sales found for the selected period.');
				return;
			}
			clearFormError();
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
					srNum(o.quantity),
					srMoneyCell(o.catalogPrice),
					srMoneyCell(o.sellRate),
					srMoneyCell(o.totalAmount),
					srMoneyCell(o.taxAmount),
					srMoneyCell(o.netAmount),
					escSR(o.cn || ''),
					escSR(o.cc || ''),
					escSR(o.paymentMode || '—'),
					dueCell
				]);
			});
			tableSellReport.draw();
			renderSRKpis(rows);
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
        	window.location.href = serverContext + "login?message=" + errorThrown;
        }
	});
}

function resetPurchaseForm(){
	resetBSDD('purchaseItemDD');
}

// Toolbar "+ New Purchase" → open the form modal fresh (mirrors newProduct/newEntity, but also
// visually resets the bootstrap-select item picker which the generic resetForm() leaves stale).
function newPurchase(){
	resetForm();
	resetPurchaseForm();
	$('#purchaseId').val('');
	$('#PurchaseModalTitle').text('New Purchase');
	openModal('PurchaseModal');
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
		+ "<h4 style='margin:0 0 14px;font-weight:700'>Sale Return</h4>"
		+ "<div style='font-size:13px;color:#444;margin-bottom:12px'>"
		+ "Invoice <b id='srInvoice'></b> &middot; <span id='srItem'></span><br>"
		+ "Sold quantity: <b id='srSold'></b></div>"
		+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Return quantity</label>"
		+ "<input type='number' id='srQty' class='form-control' step='any' min='1' style='margin-bottom:12px'>"
		+ "<label style='display:block;font-size:13px;font-weight:600;margin-bottom:4px'>Reason (optional)</label>"
		+ "<input type='text' id='srReason' class='form-control' maxlength='200' placeholder='e.g. damaged, expired, customer change' style='margin-bottom:8px'>"
		// P11 (slice 55): quarantine returned stock (not restocked) — defaulted on for pharmacy.
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
	if (!qty || qty <= 0) { err.textContent = 'Enter a quantity greater than 0.'; return false; }
	if (qty > sold)       { err.textContent = 'Cannot return more than the sold quantity (' + sold + ').'; return false; }

	var btn = document.getElementById('srSubmit');
	btn.disabled = true;
	$.ajax({
		type: 'POST',
		url: serverContext + "saleReturn",
		dataType: "json",
		data: { 'sellId': sellId, 'sellSId': stockId, 'quantity': qty, 'reason': document.getElementById('srReason').value,
			'quarantine': document.getElementById('srQuarantine').checked },
		success: function(data){
			btn.disabled = false;
			if (data && (data.status === 'SUCCESS' || data.message)) {
				closeSaleReturn();
				showSaleSuccess((data.message) || 'Sale returned successfully.');
				datatable.clear().draw();
				datatable.ajax.reload();
			} else {
				err.textContent = (data && data.status ? data.status : 'Return failed') + (data && data.message ? ': ' + data.message : '.');
			}
		},
		error: function (e) {
			btn.disabled = false;
			err.textContent = 'An error occurred. Please try again.';
		}
	});
}


// ─── Receive Payment (AR subledger) ───────────────────────────────────────────
// A "Receive" action on each customer row opens the modal; submit FIFO-allocates the receipt to the customer's
// open invoices (business-service), recomputes their due, and records it in the shared finance ledger.
$(document).on('click', '.rcv-pay-btn', function (e) {
	e.stopPropagation();   // don't let the row-click also open the edit modal
	openReceivePayment($(this).data('cid'), $(this).data('name'), $(this).data('due'));
});

function openReceivePayment(customerId, name, due) {
	$("#rcvCustomerId").val(customerId);
	$("#rcvCustomerName").text(name || ('Customer #' + customerId));
	var d = Number(due || 0);
	$("#rcvDue").text(d);
	$("#rcvAmount").val(d > 0 ? d : '');
	$("#rcvMethod").val('CASH');
	$("#rcvReference").val('');
	$("#rcvDate").val(new Date().toISOString().slice(0, 10));
	openModal('ReceivePaymentModal');
}

function submitReceivePayment() {
	var customerId = $("#rcvCustomerId").val();
	var amount = $("#rcvAmount").val() * 1;
	if (!customerId || !(amount > 0)) { showFormError('Enter a positive amount to receive.'); return; }
	$.post(serverContext + "receivePayment", {
		customerId: customerId,
		amount: amount,
		method: $("#rcvMethod").val(),
		paidOn: $("#rcvDate").val(),
		reference: $("#rcvReference").val()
	}, function (resp) {
		if (resp && resp.status === "SUCCESS") {
			var o = resp.object || {};
			var msg = 'Payment received.' + (o.receiptNo ? ' Receipt ' + o.receiptNo : '');
			if (typeof showSaleSuccess === 'function') showSaleSuccess(msg); else clearFormError();
			closeModal('ReceivePaymentModal');
			loadDataTable();   // refresh the customer list — due is updated
		} else {
			showFormError((resp && resp.message) || 'Could not record the payment.');
		}
	}, 'json').fail(function () { showFormError('Could not record the payment.'); });
}
