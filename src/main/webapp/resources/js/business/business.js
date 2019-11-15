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
        	
        	obj.name = $( "#sellItemDD :selected" ).text();
        	data.push(obj);
			var arr = [
				obj.itemId,$( "#sellItemDD :selected" ).text(),obj.quantity,obj.stockDTO.bsellRate,obj.stockDTO.bsellDiscount,($("#sellrm").val()),"<button id='DII' onclick=UIT("+obj.itemId+")>Del</button>"
				];
			tablesi.row.add(arr).draw();
			resetForm();
			resetBSDD('sellItemDD');
        }else{
        	alert("Please make sure you have entered valid values");
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

function CIT(data){
	var q=ZERO,sr=ZERO,dis=ZERO,t=ZERO;
	data.forEach(function(d){
		q=d.quantity*ONE+q;
		sr=d.stockDTO.bsellRate*ONE+sr;
		dis=d.stockDTO.bsellDiscount*ONE+dis;
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
	//CIT(data)
}
function loadDataTable(){
	tableSellReport.clear().draw();
	//check if data table exist destroy it
	var offset = $( "select[name='tableSell_length']" ).val();
	if(!offset)
		offset = 5;
	
	var pl = offset;
	if(pl==-1)
		pl=100;
	if (datatable!=null){
		datatable.destroy();
		datatable = null;
	}
	datatable = $("#table" + tableV).DataTable({
		lengthMenu:[[5, 20, 50,100, -1 ],[ '5', '20', '50', '100', 'All' ]],
		"iDisplayLength": offset,
		"pageLength": pl,
		"order": [[ 0, "desc" ]],
		"autoWidth" : true,
		dom: 'Bfrtip',
        buttons: [
        	'pageLength',
/*            { extend: 'copyHtml5', footer: true },
            { extend: 'csvHtml5', footer: true },
*/            
        	{ extend: 'excelHtml5', footer: true },
            {extend:'print', footer: true },
        	{
                extend: 'pdfHtml5',
                orientation: 'landscape',
                pageSize: 'LEGAL',
                footer: true
            }
        ],
		"ajax" : {
			"url" : serverContext + "getUser" + getAll+"?q="+offset,
			"type" : "GET",
			"success" : function(data) {
				if(reload != tableV){
					//don't want to load ever DD for every row update on table
					var table = tableV.toLowerCase();
					loadUserCompanies(table);
					loadUserVenders(table);
					laodUserItemTypes(table);
					loadUserItemUnits(table);
					loadUserItems(table);
					reload=tableV;
				}
				var arr = [" No Data Found "];
				var collections = data.collection;
				if(!collections || collections.length<=0){
					datatable.columns( [0] ).visible( false );
					$(".dataTables_empty")[0].innerHTML = "No Data Found";
					return false;
				}
				
				userId = collections[0].userId;
				datatable.columns( [0] ).visible( false );
				console.log("getUser : "+getAll+" collections : "+collections);
				if (getAll === "Company") {
					$.each(collections, function(ind, obj) {
						arr = [
								"<div id=companyId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
								"<div id=companyName>"+obj.name+"</div>", "<div id=companyPhone>"+obj.phone+"</div>", 
								"<div id=companyEmail>"+obj.email+"</div>","<div id=companyAddress>"+obj.address+"</div>",obj.updatedStr
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Vender") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=venderId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=venderName>"+obj.name+"</div>", "<div id=venderCompanyDD>"+obj.companyName+"</div>",
							"<div id=venderPhone>"+obj.phone+"</div>", "<div id=venderMobile>"+obj.mobile+"</div>",
							"<div id=venderEmail>"+obj.email+"</div>","<div id=venderAddress>"+obj.address+"</div>",obj.datedStr
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "ItemType") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=itemTypeId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=itemTypeName>"+obj.name+"</div>","<div id=itemTypeDescription>"+obj.description+"</div>",obj.datedStr
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "ItemUnit") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=itemUnitId>"+obj.id+"</div>", "<input type='checkbox' value="+ obj.id+ ">",
							"<div id=itemUnitName>"+obj.name+"</div>", "<div id=itemUnitDescription>"+obj.description+"</div>",obj.datedStr
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Item") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=itemId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ ">",
							"<div id=itemCompanyDD>"+obj.companyName+"</div>", "<div id=itemVenderDD>"+obj.venderName+"</div>", "<div id=itemName>"+obj.iname+"</div>",
							"<div id=itemCode>"+obj.icode+"</div>", "<div id=itemDesc>"+obj.idesc+"</div>",
							 /*"<div id=itemVenderDD>"+obj.venderName+"</div>", */
							/*"<div id=itemPurchaseAmount>"+obj.purchaseAmount+"</div>","<div id=itemSellAmount>"+obj.sellAmount+"</div>",*/
							/*"<div id=discountTypeDD>"+obj.discountType+"</div>","<div id=itemDiscount>"+obj.discount+"</div>", "<div id=itemNet>"+obj.net+"</div>",
							"<div id=itemExpDate>"+obj.expDateStr+"</div>","<div id=batchStock>"+obj.stock+"</div>"
							,"<div id=itemBN>"+obj.bn+"</div>",*/obj.updated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Purchase") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=purchaseId>"+obj.purchaseId+"</div>", "<input type='checkbox' value="+ obj.purchaseId+ ">",
							"<div id=purchaseItemDD>"+obj.icode+"</div>","<div id=purchaseItemName>"+obj.iname+"</div>",
							"<div id=purchaseQuantity>"+obj.quantity+"</div>",/* "<div id=purchaseStock>"+obj.stockDTO.stock+"</div>",*/
							"<div id=purchaseBatchNo>"+obj.stockDTO.batchNo+"</div>","<div id=purchaseExpiry>"+obj.stockDTO.bexpDate+"</div>", 
							"<div id=purchasePurchaseRate>"+obj.stockDTO.bpurchaseRate+"</div>","<div id=purchaseSellRate>"+obj.stockDTO.bsellRate+"</div>", 
							"<div id=discountTypeDD>"+obj.stockDTO.bpdiscountType+"</div>", 
							"<div id=purchaseDiscount>"+obj.stockDTO.bpdiscount+"</div>","<div id=purchaseTotalAmount>"+obj.totalAmount+"</div>",
							"<div id=purchaseNetAmount>"+obj.netAmount+"</div>","<div id=purchaseDate>"+obj.updated+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Sell") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=sellId>"+obj.sellId+"</div>", "<input type='checkbox' value="+ obj.sellId+ ">",
							"<div id=sellItemDD>"+obj.itemCode+"</div>","<div id=sellItemName>"+obj.itemName+"</div>",
							"<div id=sellItems>"+obj.quantity+"</div>",
							"<div id=sellItemBatchNo>"+obj.stockDTO.batchNo+"</div>","<div id=sellItemExpiry>"+obj.stockDTO.bexpDate+"</div>", 
							"<div id=sellPurchaseRate>"+obj.stockDTO.bsurchaseRate+"</div>","<div id=sellSellRate>"+obj.stockDTO.bsellRate+"</div>",
							"<div id=sellDiscountTypeDD>"+obj.stockDTO.bsellDiscountType+"</div>","<div id=sellDiscount>"+obj.stockDTO.bsellDiscount+"</div>",
							"<div id=sellTotalAmount>"+obj.totalAmount+"</div>","<div id=sellNetAmount>"+obj.netAmount+"</div>",
							"<div id=sellCC>"+obj.cc+"</div>","<div id=sellCN>"+obj.cn+"</div>",
							
							/*"<div id=sellsrp>"+obj.srp+"</div>","<div id=sellRe>"+obj.re+"</div>",*/
							obj.updated
							];
						datatable.row.add(arr).draw();
//						datatable.columns( [10,11] ).visible( false );
					});
				}
			},
			 error: function(jqXHR, textStatus, errorThrown) {
//				 	window.location.href = serverContext + "login?message=" + data.responseJSON.message;
//	                alert('An error occurred... Look at the console (F12 or Ctrl+Shift+I, Console tab) for more information!');
	                console.log('jqXHR:');
	                console.log(jqXHR);
	                console.log('textStatus:');
	                console.log(textStatus);
	                console.log('errorThrown:');
	                console.log(errorThrown);
				 	window.location.href = serverContext + "login?message=" + errorThrown;
	            }
		}
	});
//	$(".dataTables_length").hide();
	//register and call when show entries drop down changes
	$("select[name='tableSell_length']").change(function(){
		loadDataTable();
	});
	 
 /*   $('a.toggle-vis').on( 'click', function (e) {
        e.preventDefault();
 
        // Get the column API object
        var column = datatable.column($(this).attr('data-column') );
 
        // Toggle the visibility
        column.visible( ! column.visible() );
    } );*/
}

function loadUserCompanies(table) {	
	$("#"+table.toLowerCase()+"CompanyDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserCompanies",function(data){
   		$("#"+table.toLowerCase()+"CompanyDD").append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"Company").empty().append("<option value = ''> System error  </option>");
	});
}

function loadUserVenders(table) {	
	$("#"+table.toLowerCase()+"VenderDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserVenders",function(data){
    	$("#"+table.toLowerCase()+"VenderDD").append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"VenderDD").empty().append("<option value = ''> System error  </option>");
	});
}
function laodUserItemTypes(table) {	
	$("#"+table.toLowerCase()+"TypeDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserItemTypes",function(data){
    	$("#"+table.toLowerCase()+"TypeDD").append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"TypeDD").empty().append("<option value = ''> System error  </option>");
	});
}
function loadUserItemUnits(table) {	
	$("#"+table.toLowerCase()+"UnitDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserItemUnits",function(data){
    	$("#"+table.toLowerCase()+"UnitDD").append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"UnitDD").empty().append("<option value = ''> System error  </option>");
	});
}

function loadUserItems(table) {	
    $.get(serverContext+ "getUserItems",function(data){
    	$("#"+table.toLowerCase()+"ItemDD").empty().append(data).selectpicker('refresh');
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"ItemDD").empty().append("<option value = ''> System error  </option>");
	});
}

function loadUserItem(table) {	
	$("#"+table.toLowerCase()+"UnitDD").empty().append("<option value = ''> Please Select </option>");
    $.get(serverContext+ "getUserItemUnits",function(data){
    	$("#"+table.toLowerCase()+"UnitDD").append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"UnitDD").empty().append("<option value = ''> System error  </option>");
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
function laodStock(label,value){
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
		    	$("#pdt").html(discountType+" Discount");
		    	calculateNetPurchase();
    		}else if(value && tableV=="Sell"){
        		$("#sellDiscountTypeDD").val(discountType);
	    		if(batchStock <= 0){
	    			$("#sellItems").addClass("alert-danger");
	    			alert("No more items are available, Please purchase or select some other item to sell.");
	    			$(".form-control").val("");
	    			return false;
	    		}
	    		$("#sellStock").val(batchStock);
	    		$("#sellItemDesc").val(data.desc);
	    		$("#sellPurchaseRate").val(data.bpurchaseRate);
		    	$("#sellSellRate").val(data.bsellRate)
		    	$("#sellDiscount").val(discountValue);
		    	if($("#sellItems").val()*1<=0){
		    		$("#sellItems").val(1);
		    	}
		    	calculateNetSell();
    		}
    	}
    })
	.fail(function(data) {
		console.log(data);
	});
}

function calculateNetPurchase(){
	console.log(1)
	var p = $("#purchasePurchaseRate").val()*ONE;
	var s= $("#purchaseSellRate").val()*ONE;
	var qty= $("#purchaseQuantity").val()*ONE;
	var purchaseDiscount = $("#purchaseDiscount").val()*1>0?$("#purchaseDiscount").val()*ONE:0;
	var purchaseTotalAmount = $($("#purchaseTotalAmount").val(parseFloat(qty * p).toFixed(2))).val();
	$("#purchaseStock").val(batchStock);
	if(discountType == "%"){
		//Discount  =  List Price Ã— Discount Rate 
		purchaseDiscount = purchaseTotalAmount * (purchaseDiscount*1 / 100);
	}else{
		purchaseDiscount = purchaseDiscount * qty;
		$("#purchaseDiscount").val(purchaseDiscount);
	}
	if(s>0){
		$("#purchaseNetAmount").val(parseFloat((qty * s - purchaseTotalAmount) + purchaseDiscount).toFixed(2));
	}else{
		$("#purchaseNetAmount").val(0);
	}
//	$("#purchaseTotalAmount").val(parseFloat(purchaseTotalAmount).toFixed(2));
/*	$("#purchaseNetAmount").val($("#netAmount").val());*/
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
		alert("You can not select more item than availabe in stock, Please purchase or select some other item to sell.")
		$(".form-control").val("");
		return false;
	}
	var sellDiscount= $("#sellDiscount").val()*1>0?$("#sellDiscount").val()*ONE:0;
	sellTotalAmount = parseFloat(qty * s).toFixed(2);
	if(discountType == "%"){
		//Discount  =  List Price Ã— Discount Rate 
		sellDiscount =  sellTotalAmount * (sellDiscount*1 / 100);
	}else{
		//sellDiscount = sellDiscount * qty;
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

function calculateSRP(){
	console.log(2)
	var s= $("#sellSellRate").val()*ONE;
	if(!s || s<=0){
		alert("Please select valid item's record to return sold");
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

function calculateChange(){
	var recAm = $("#sellRec").val()*ONE;
	var sellTotal = $("#sellTotal")[0].innerHTML*ONE;
	$("#sellCh").val(recAm - sellTotal);
}

function loadSR(){
	console.log(11)
	tableSellReport.clear().draw();
	$.ajax({
		type : "POST",
		url : serverContext + "loadSR",
		dataType : "json",
		data : populateFormData(),
		success : function(data) {
			if(data.status!=="SUCCESS"){
				return alert(data.status+" : "+data.message);
			}else{
				if(!data || !data.collection)
					return alert("Data not found.");

				$.each(data.collection, function(ind, o) {
					var row = [o.itemName, o.stock,o.purchaseRate,o.sellRate,o.quantity,o.discount,o.dt,o.totalAmount,o.netAmount,o.cn,o.cc,o.srp,o.re,o.datedStr]

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
