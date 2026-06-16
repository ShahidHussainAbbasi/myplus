var data=[]; // use a global for the submit and return data rendering in the examples
var tablesi;
var removed = false;
var tableSellReport;


$(document).ready(function() {
    tableSellReport = $('#tableSellReport').DataTable( {
        dom: 'Bfrtip',
        lengthMenu: [
            [ 10, 25, 50, -1 ],
            [ '10 rows', '25 rows', '50 rows', 'Show all' ]
        ],        
        buttons: [
        	'pageLength',
            { extend: 'copyHtml5', footer: true },
            { extend: 'csvHtml5', footer: true },
            { extend: 'excelHtml5', footer: true },
            { extend: 'print', footer: true },
        	{ extend: 'pdfHtml5',
              orientation: 'landscape',
              pageSize: 'LEGAL',
              footer: true
            }
        ],
	    
	    "footerCallback": function ( row, data, start, end, display ) {
	        var api = this.api(), data;
	
	        // Remove the formatting to get integer data for summation
	        var intVal = function ( i ) {
	            return typeof i === 'string' ?
	                i.replace(/[\$,]/g, '')*1 :
	                typeof i === 'number' ?
	                    i : 0;
	        };
	
	        // Total over all pages
	        feeTotal = api
	            .column( 1 )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        feePageTotal = api
	            .column( 1, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column( 1 ).footer() ).html(
	            feePageTotal +'/'+ feeTotal
	        );

	        // Total over all pages
	        otherTotal = api
	            .column( 4 )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        otherPageTotal = api
	            .column( 4, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column( 4 ).footer() ).html(
	            otherPageTotal +'/'+ otherTotal
	        );
	    

	        // Total over all pages
	        disTotal = api
	            .column(5)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        disPageTotal = api
	            .column(5, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(5).footer() ).html(
	        		disPageTotal +'/'+ disTotal
	        );

	        // Total over all pages
	        dueTotal = api
	            .column(7)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        duePageTotal = api
	            .column(7, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(7).footer() ).html(
	        		duePageTotal +'/'+ dueTotal
	        );
	    
	        // Total over all pages
	        paidTotal = api
	            .column(8)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        paidPageTotal = api
	            .column(8, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(8).footer() ).html(
	        		paidPageTotal +'/'+ paidTotal
	        );
	        
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
			obj.itemId = $("#sellItemDD").val();
			obj.stock.itemId = obj.itemId;
			obj.stock.itemName = obj.itemName;
			// var item = {"id":$("#sellItemDD").val(), "name":$( "#sellItemDD :selected" ).text()};
			// obj.item = item;

        	data.push(obj);
			var arr = [
				obj.itemId,escHtml(obj.itemName),obj.quantity,obj.stock.bsellRate,obj.stock.bsellDiscount,($("#sellrm").val()),"<button id='DII' onclick=UIT("+obj.itemId+")>Del</button>"
				];
			tablesi.row.add(arr).draw();
			resetForm();
			resetBSDD('sellItemDD');
        }else{
        	showFormError('Please select an item and enter a valid quantity.');
        	return false;
        }
    });
} );

function UIT(id){
	data.forEach(function(d,i){
		if(id==d.itemId){
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
			tablesi.row.add([
				item.itemId, escHtml(item.itemName || ''), item.quantity,
				stk.bsellRate, stk.bsellDiscount, item.totalAmount,
				"<button id='DII' onclick=UIT(" + item.itemId + ")>Del</button>"
			]);
		});
		if(tablesi){ tablesi.draw(); }
		// 3) fill the customer form (manual mode shows the name/contact fields)
		if(typeof onCustomerModeChange === 'function') onCustomerModeChange('manual');
		$("#sellCN").val(inv.customer ? (inv.customer.name || '') : '');
		$("#sellCC").val(inv.customer ? (inv.customer.contact || '') : '');
		$("#sellRec").val(inv.paidAmount != null ? inv.paidAmount : '');
		// 4) enter edit state + show the banner
		window.editingInvoice = { chId: inv.customer_history_id, invoiceNo: inv.invoiceNo };
		showSellEditBanner(inv.invoiceNo);
		// 5) bring the form into view
		try { $('html, body').animate({ scrollTop: $('#sellDiv').offset().top }, 300); } catch(e){}
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

function cancelSellEdit(){
	window.editingInvoice = null;
	data.length = 0;
	if(tablesi){ tablesi.clear().draw(); }
	$('#sellEditBanner').remove();
	$("#sellCN,#sellCC,#sellRec").val('');
	if(typeof resetForm === 'function') resetForm();
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
	onCustomerModeChange('select');
	$('input[name="customerInputMode"][value="select"]').prop('checked', true);
}
function loadDataTable(){
	tableSellReport.clear().draw();

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
			"url": serverContext + "getUser" + getAll + "?q=-1",
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
							"<div id=email>"+escHtml(obj.email)+"</div>","<div id=address>"+escHtml(obj.address)+"</div>",obj.updated
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
							"<div id=purchaseInvoiceNo>"+escHtml(obj.purchaseInvoiceNo)+"</div>","<div id=purchaseItemDD>"+escHtml(obj.iname)+"</div>",
							"<div id=purchaseQuantity>"+obj.quantity+"</div>","<div id=purchaseStock>"+obj.stock.stock+"</div>",
							"<div id=purchasePurchaseRate>"+obj.stock.bpurchaseRate+"</div>","<div id=purchaseSellRate>"+obj.stock.bsellRate+"</div>",
							"<div id=purchaseDiscountTypeDD>"+obj.stock.bpurchaseDiscountType+"</div>",
							"<div id=purchaseDiscount>"+obj.stock.bpurchaseDiscount+"</div>",
							"<div id=purchaseTotalAmount>"+obj.totalAmount+"</div>",
							"<div id=purchaseNetAmount>"+obj.netAmount+"</div>",
							"<div id=purchaseExpiry>"+obj.stock.bexpDate+"</div>","<div id=purchaseDate>"+obj.updated+"</div>"
						]);
					});
				} else if (getAll === "Sell") {
					$.each(collections, function(ind, obj) {
						allRows.push([
							"<div id=sellId>"+obj.sellId+"</div>",
							"<div id=sellInvoiceNo>"+escHtml(obj.customerHistory ? (obj.customerHistory.invoiceNo || '') : '')+"</div>",
							"<div id=sellItemName>"+escHtml(obj.itemName)+"</div>",
							"<div id=sellItems>"+obj.quantity+"</div>",
							"<div id=sellItemExpiry>"+obj.stock.bexpDate+"</div>",
							"<div id=sellPurchaseRate>"+obj.stock.bpurchaseRate+"</div>","<div id=sellSellRate>"+obj.stock.bsellRate+"</div>",
							"<div id=sellDiscountTypeDD>"+obj.stock.bsellDiscountType+"</div>","<div id=sellDiscount>"+obj.stock.bsellDiscount+"</div>",
							"<div id=sellTotalAmount>"+obj.totalAmount+"</div>","<div id=sellNetAmount>"+obj.netAmount+"</div>",
							obj.updated
						]);
					});
				}
				// Single draw — much faster than calling draw() on every row.add()
				datatable.rows.add(allRows).draw();
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
				dd.append('<option value="' + c.customerId + '" data-contact="' + escHtml(c.contact || '') + '">' + escHtml(c.name) + '</option>');
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
	} else {
		$("#sellCN").val('');
		$("#sellCC").val('');
	}
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
    $.get(serverContext+ "getUserItems",function(data){
    	$("#"+table+"ItemDD").empty().append(data).selectpicker('refresh');
    })
	.fail(function(data) {
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
	edit = false;
	$("#purchasePurchaseRate").val("");
	$("#purchaseSellRate").val("")
	$("#sellPurchaseRate").val("");
	$("#sellSellRate").val("")
	$("#sellItems").removeClass("alert-danger");
	$("pdt").html("      ");
	
    $.get(serverContext+ "getStock?itemId="+value,function(data){
    	if(data){
	    	discountValue = data.bsellDiscount;
	    	discountType = data.bsellDiscountType;
	    	batchStock = data.stock;
    		if(value && tableV=="Purchase"){
        		$("#discountTypeDD").val(discountType);    			
    			$("#purchaseDiscount").val(discountValue);//*1>0?$("#bpurchaseDiscount").val():0;
		    	$("#purchasePurchaseRate").val(data.bpurchaseRate);
		    	$("#purchaseSellRate").val(data.bsellRate)
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
			    	$("#sellSellRate").val(data.bsellRate)
			    	$("#sellDiscount").val(discountValue);
			    	if($("#sellItems").val()*1<=0){
			    		$("#sellItems").val(1);
			    	}
			    	$("#sellItemDesc").val(data.idesc);
			    	calculateNetSell();
	    		}
    		}
    	}
    })
	.fail(function(data) {
		console.log(data);
	});
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
		$.get(serverContext+ "getStockByBatch?batchNo="+batchNo,function(data){
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

function calculateNetPurchase(){
	var p = $("#purchasePurchaseRate").val()*ONE;
	var s= $("#purchaseSellRate").val()*ONE;
	var qty= $("#purchaseQuantity").val()*ONE;
	discountType = $("#discountTypeDD :selected").val();
	var purchaseDiscount = $("#purchaseDiscount").val()*1>0?$("#purchaseDiscount").val()*ONE:0;
	var purchaseTotalAmount = $($("#purchaseTotalAmount").val(parseFloat(qty * p).toFixed(2))).val();
	if (!edit){
		 $("#purchaseStock").val(batchStock);
	}
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
	if(edit){
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

	var recAm = $("#sellRec").val() * ONE;
    var sellTotal = $("#sellTotal")[0].innerHTML * ONE;
    var change = recAm - sellTotal;
    
    $("#sellCh").val(change);

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

function loadSR(){
	tableSellReport.clear().draw();
	validateForm();
	$.ajax({
		type : "POST",
		url : serverContext + "loadSR",
		dataType : "json",
		data : populateFormData(),
		success : function(data) {
			if(data.status!=="SUCCESS"){
 					showFormError((data.status || '') + (data.message ? ': ' + data.message : ''));
			}else{
				if(!data || !data.collection)
 					showFormError('Data not found.');

				$.each(data.collection, function(ind, o) {
					var row = [o.itemCode +" - "+o.itemName, o.stock,o.purchaseRate,o.sellRate,o.quantity,o.discount,o.dt,o.totalAmount,o.netAmount,o.cn,o.cc,o.srp,o.re,o.datedStr]

					tableSellReport.row.add(row).draw();
				});
			}
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

function saleReturn(sellId,stockId,qty){
	var r = confirm("Are you sure, Do you want to revert this sale?");
	if (r != true)
		return false;
	
	$.ajax({
        type:'POST',
        url:serverContext+ "saleReturn",
        dataType : "json",
        data:{'sellId':sellId,'sellSId':stockId,'quantity':qty},
            success:function(data){
        		datatable.clear().draw();
        		datatable.ajax.reload();		
	        },
		    error: function (e) {
 		        showFormError('An error occurred: ' + e);
		    }
        });
    
//	$.get(serverContext+ "saleReturn?itemId="+itemId+"&stockId="+stockId+"&qty="+qty,function(data){
//		datatable.clear().draw();
//		datatable.ajax.reload();		
//    })
//	.fail(function(data) {
//		alert(data);
//	});
//	resetForm();
//	$("#globalError").empty();
}

