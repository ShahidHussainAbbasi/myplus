var userId = -1;
var month = new Array();
month[0] = "Jan";
month[1] = "Feb";
month[2] = "Mar";
month[3] = "Apr";
month[4] = "May";
month[5] = "Jun";
month[6] = "Jul";
month[7] = "Aug";
month[8] = "Sep";
month[9] = "Oct";
month[10] = "Nov";
month[11] = "Dec";

var buttonV = "Company";
var deleteV = "Company";
var tableV = "Company";
var getAll = "Company";
var datatable=null;
var formValidated = true;
var form=null;
var formFields = 0;
var reload="";
var ONE = 1;
var ZERO = 0;
var HUNDRED = 100;
var edit = false;

var s2n = function(v){
	if(isNaN(v))
		return 0;
	else
		return v*ONE;
}

function resetGlobalError(){
    $(".alert").html("").hide();
    $(".error-list").html("");
}

function showFormError(msg) {
    var el = document.getElementById('globalError');
    if (el) {
        el.textContent = msg;
        el.style.display = 'block';
        el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
}

function clearFormError() {
    var el = document.getElementById('globalError');
    if (el) {
        el.textContent = '';
        el.style.display = 'none';
    }
}

// slice 22: transient confirmation showing the system-generated invoice number after a sale.
function showSaleSuccess(msg) {
    var el = document.getElementById('saleSuccess');
    if (!el) {
        el = document.createElement('div');
        el.id = 'saleSuccess';
        el.style.cssText = 'position:fixed;top:16px;right:16px;z-index:9999;background:#0a7d33;color:#fff;'
            + 'padding:12px 18px;border-radius:8px;font-size:14px;font-weight:600;box-shadow:0 6px 24px rgba(0,0,0,.25)';
        document.body.appendChild(el);
    }
    el.textContent = msg;
    el.style.display = 'block';
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.style.display = 'none'; }, 6000);
}

function resetForm(){
	// Reset error Form's error classes and values
	form = document.getElementsByClassName('form-horizontal')[tableV];
	if(form){
		$(".resetForm").click();
		// updateReadOnly(false);
		return;
		
/*		formFields = form.length-2;// -2 mean we don't need to loop over
									// buttons (Add & Delete)
		for(var i=0; i<formFields; i++){
			// $("#"+form[i].id).removeClass("alert-danger");
		}
*///		$(".form-control").val("");
	}
}

function validateForm(){
    formValidated = true;
    var form = document.getElementsByClassName('form-horizontal')[tableV];
    if(form && !form.checkValidity()){
        var missing = [];
        formFields = form.length - 2;
        for(var i = 0; i < formFields; i++){
            if(!form[i].id) continue;
            var el = document.getElementById(form[i].id);
            // bootstrap-select inserts the wrapper as the NEXT sibling of the hidden <select>
            var visualEl = $(el).hasClass('selectpicker')
                ? ($(el).next('.bootstrap-select')[0] || el)
                : el;
            if(form[i].validity.valid){
                visualEl.style.removeProperty('border-color');
            } else {
                visualEl.style.setProperty('border-color', 'red', 'important');
                var label = $('label[for="' + form[i].id + '"]').text().replace(/\s*\*\s*$/, '').replace('req','').trim()
                    || el.placeholder || el.name || form[i].id;
                missing.push(label);
            }
        }
        formValidated = false;
        if(missing.length > 0){
            showFormError('Please fill in the required fields: ' + missing.join(', '));
        }
    } else {
        clearFormError();
    }
    if(form) formFields = form.length - 2;
}

var initDates = function(){
	var dateTimeInputs = $('.datetimepicker');
	for(var i=0; i<dateTimeInputs.length;i++){
		dateTimeInputs[i].value= moment().format('DD-MM-YYYY HH:mm:ss');
	}
	var dateInputs = $('.datePicker');
	for(var i=0; i<dateInputs.length;i++){
		dateInputs[i].value= moment().format('DD-MM-YYYY');
	}	
}

$(document).ready(function() {

	// Clear red border on any required field as soon as the user interacts with it
	$(document).on('input change', '[required]', function() {
		var visualEl = $(this).hasClass('selectpicker')
			? ($(this).next('.bootstrap-select')[0] || this)
			: this;
		visualEl.style.removeProperty('border-color');
	});

	// On form reset (button type="reset" or programmatic): clear validation state
	$(document).on('reset', 'form.form-horizontal', function() {
		var $form = $(this);
		// Remove red borders from all fields, including selectpicker wrappers
		$form.find('[required], .selectpicker').each(function() {
			var visualEl = $(this).hasClass('selectpicker')
				? ($(this).next('.bootstrap-select')[0] || this)
				: this;
			visualEl.style.removeProperty('border-color');
		});
		// Refresh selectpicker display after native reset clears its value
		$form.find('.selectpicker').selectpicker('refresh');
		clearFormError();
		// updateReadOnly(false);
	});

//
//	$(".onChangeSelect").hover(function(){
//		var dropdownMenu = $(this).children(".dropdown-menu");
//		if(dropdownMenu.is(":visible")){
//			dropdownMenu.parent().toggleClass("open");
//		}
//	});
	
/*	$('select').hover(function() {
		  $(this).attr('size',  $(this).children('option').length+1);
		}, function() {
		  $(this).attr('size', 1);
		});
*/	

	$(".glyphicon-dashboard").closest('a').on('click', function(e) {
		if ($("#DashboardDiv").length > 0) {
			e.preventDefault();
			$('.formDiv').hide();
			$("#DashboardDiv").show();
			if (typeof getDashboardData === 'function') {
				getDashboardData();
			} 
		}
	});
	
	$("#paBtn").click(function (event) {

	    // stop submit the form, we will post it manually.
	    event.preventDefault();

	    // Get form
	    var form = $('#PAForm')[0];

		// Create an FormData object
	    var data = new FormData(form);

		// If you want to add an extra field for the FormData
// data.append("CustomField", "This is some extra data, testing");

		// disabled the submit button
	    $("#paBtn").prop("disabled", true);

	    $.ajax({
	        type: "POST",
	        enctype: 'multipart/form-data',
	        url : serverContext + "importCSV",
	        data: data,
	        processData: false,
	        contentType: false,
	        cache: false,
// timeout: 600000,
	        success: function (data) {
	            $("#paBtn").prop("disabled", false);
	            loadDataTable();
	        },
	        error: function (e) {
	            $("#paBtn").prop("disabled", false);
	        }
	    });
	});	

	$(".datePickerWithMonthName").datetimepicker({
		useCurrent: true,
		format : 'DD-MMM-YYYY',
		showTodayButton: true,
		showClear:true,
		showClose:true
	});

	$(".monthYearDatePicker").datetimepicker({
		useCurrent: true,
		format : 'MM-YYYY',
//		showTodayButton: true,
		showClear:true,
		showClose:true
	});

	$(".purchaseDate").datetimepicker({
		useCurrent: true,
		format : 'DD-MM-YYYY HH:mm:ss',
		showTodayButton: true,
		showClear:true,
		showClose:true
	});
    
	$(".datePicker").datetimepicker({
		useCurrent: true,
		format : 'DD-MM-YYYY',
		showTodayButton: true,
		showClear:true,
		showClose:true
	});


	$('#dueDateTemp').datepicker({
		format: 'dd/mm/yyyy',
		autoclose: true
	}).on('changeDate', function(e) {
		// Write yyyy-MM-dd into the hidden field for form submission
		var d = e.date;
		var formatted = d.getFullYear() + '-'
			+ String(d.getMonth() + 1).padStart(2, '0') + '-'
			+ String(d.getDate()).padStart(2, '0');
		$('#dueDate').val(formatted);
	});	

	$('#purchaseDate').datepicker({
		format: 'dd-mm-yyyy',
		autoclose: true
	}).on('purchaseDate', function(e) {
		// Write yyyy-MM-dd into the hidden field for form submission
		var d = e.date;
		var formatted = d.getFullYear() + '-'
			+ String(d.getMonth() + 1).padStart(2, '0') + '-'
			+ String(d.getDate()).padStart(2, '0');
		$('#purchaseDate').val(formatted);
	});	

	$('#purchaseExpiry').datepicker({
		format: 'dd-mm-yyyy',
		autoclose: true
	}).on('purchaseExpiry', function(e) {
		// Write yyyy-MM-dd into the hidden field for form submission
		var d = e.date;
		var formatted = d.getFullYear() + '-'
			+ String(d.getMonth() + 1).padStart(2, '0') + '-'
			+ String(d.getDate()).padStart(2, '0');
		$('#purchaseExpiry').val(formatted);
	});	

	$('.datetimepicker').datetimepicker({
	   format: 'DD-MM-YYYY HH:mm:ss',
	   useCurrent: false,
		showTodayButton: true,
		showClear:true,
		showClose:true,
		toolbarPlacement: 'top'
	   }).on('dp.show', function() {
	   if($(this).data("DateTimePicker").date() === null)
	     $(this).data("DateTimePicker").date(moment());
	 });
	
    $('input.timepicker').timepicker({ 
    	timeFormat: 'HH:mm',
        defaultTime: '8',
        dynamic: false,
        dropdown: true,
        scrollbar: true
    });
	
    $(".onChangeSelect").change(function(){
    	initDates();
		clearFormError();
    	var label = $(this).text();
    	var value = $(this).val();
    	if(tableV=="FV"){
//    		loadFVIBSDD(label,value); 
//    		loadBSDD("getUser"+lable.trim(),"fviDD");
    	}else if(tableV=="Purchase" || tableV =="Sell"){
			if (tableV=="Purchase") {
				$(purchaseId).val(null); // reset form on item change for purchase form
			}
    		loadStock(label,value);  
    		// loadBSDD("getBatchesByItem?itemId="+value,tableV.toLowerCase()+'BatchDD');    		
    	}
    });
    
	$switchInputs =function(val) {
	    
		buttonV = val;
		deleteV = val;
		tableV = val;
		getAll = val;	
		
		resetForm();

	    $("#dateRangeDD"+tableV).change(function(){
    		$("#dateRange"+tableV).hide();
    		$("#custom"+tableV).hide();
    		if($(this).val()=="1"){
	    		$("#dateRange"+tableV).show();
	    	}else if($(this).val()=="2"){
	    		$("#custom"+tableV).show();
	    		
	    	}
	    });    
				
		// All button get initialized when user switch form
		$("#find"+buttonV).off().click(function() {
				if(!$("#input"+buttonV).val()){ showFormError('Please enter a search value.'); return false; }

			findBy("find" + buttonV,"input="+$("#input"+buttonV).val());
		});

		// All button get initialized when user switch form
		$("#add"+buttonV).off().click(function() {
		    // If all form's required fields are filled
			if(buttonV=="Sell"){
	    		document.getElementById("sellRec").style.borderColor = "";
				var error = false;

				// Customer is mandatory regardless of payment mode
				var isSelectMode = $('#btnModeSelect').hasClass('active');
				if (isSelectMode) {
					if (!$("#sellCustomerDD").val()) {
						document.getElementById("sellCustomerDD").style.setProperty('border-color', 'red', 'important');
						return;
					}
					document.getElementById("sellCustomerDD").style.removeProperty('border-color');
				} else {
					if ($("#sellCN").val().trim() == "") {
						document.getElementById("sellCN").style.setProperty('border-color', 'red', 'important');
						return;
					}
					document.getElementById("sellCN").style.removeProperty('border-color');
				}

				if(data && data.length>0 && $("#sellRec").val()*ONE>0 || $("#sellCh").val()*ONE < 0){
					// A NEW (manually entered) customer who owes a balance must give a mobile so the due
					// can be followed up. An existing customer chosen from sellCustomerDD is already on
					// file (their contact may legitimately be blank) — don't force a mobile in that case.
					if (!isSelectMode && $("#sellCh").val()*ONE < 0) {
						if($("#sellCC").val().trim() == ""){
							document.getElementById("sellCC").style.setProperty('border-color', 'red', 'important');
							error = true;
						}
						if (error) {
							showFormError('Customer has a due amount — please enter their mobile number.');
							return;
						}
					}


					var customer = {"name":$("#sellCN").val(), "contact":$("#sellCC").val(), "paidAmount":$("#sellRec").val(),"dueAmount":$("#sellCh").val(), "dueDate":$('#dueDate').val()};
					var customerHistory = {"customer":customer, "sales":data};
					// G5 (slice 37): record how the sale is paid. One tender from the chosen method + amount received;
					// CREDIT = on account (not counted as paid). Backend settles paid/due against the grand total.
					var payMethod = $("#sellPayMethod").val() || 'CASH';
					var received = $("#sellRec").val()*ONE || 0;
					customerHistory.tenders = [];
					if (received > 0 || payMethod === 'CREDIT') {
						customerHistory.tenders.push({ "method": payMethod, "amount": received, "reference": "" });
					}
					// P12 (slice 59): an insurer-covered portion becomes a second INSURANCE tender (co-pay split).
					var insured = $("#sellInsured").val()*ONE || 0;
					if (insured > 0) {
						customerHistory.tenders.push({ "method": "INSURANCE", "amount": insured, "reference": "" });
					}
					// Editing an existing invoice -> update it in place (same invoice #, stock & dues
					// adjusted by the deltas); otherwise create a new sale.
					if (window.editingInvoice && window.editingInvoice.chId) {
						customerHistory.customer_history_id = window.editingInvoice.chId;
						// customer is locked in edit mode — send its id so updateSell updates THAT customer
						// in place (saveUpdateCustomer keys on customerId) rather than creating a duplicate.
						if (window.editingInvoice.customerId) customer.customerId = window.editingInvoice.customerId;
						jsonPost("updateSell", customerHistory);
					} else {
						jsonPost("addSell", customerHistory);
					}
			    }else{
					    	document.getElementById("sellRec").style.setProperty('border-color', 'red', 'important');
					    	showFormError('Please add items to the cart and enter a valid payment amount.');
					    }
			}else{
				// Purchase: block client-side when no item is selected or quantity <= 0
				// (a "0" passes the generic required-field check, which only tests for non-empty).
				if(buttonV=="Purchase"){
					var pItem = $("#purchaseItemDD").val();
					var pQty = $("#purchaseQuantity").val()*1;
					if(!pItem || !(pQty > 0)){
						$("#purchaseQuantity").css('border-color','red');
						showFormError('Select an item and enter a quantity greater than 0.');
						return false;
					}
					$("#purchaseQuantity").css('border-color','');
				}
				validateForm();
			    if(formValidated){
					var fd = populateFormData();
					// M4c (slice 92): submit the purchase productId-native (from the picker's data-product) so the
					// server uses it directly; itemId stays as a back-compat fallback.
					if(buttonV=="Purchase"){
						var ppid = $("#purchaseItemDD :selected").data('product');
						if(ppid != null && ppid !== '') fd.productId = ppid;
					}
					$(this).callAjax("add" + buttonV, fd);
			    }else{
				    	return false;
			    }
			}
		});

		// All button get initialized when user switch form
		$("#revert"+buttonV).off().click(function() {
		    // If all form's required fields are filled
			validateForm();
		    if(formValidated){
// var formArr = $('form'). serializeArray();
// jQuery.each(formArr , function(i, field) {
// formArr[i].value = $.trim(field.value);
// });
// var serializedForm = $.param(formArr);
// formData = serializedForm.replace(/[^&]+=\.?(?:&|$)/g, '');
				$(this).callAjax("revert" + buttonV,populateFormData());
				loadDataTable();
		    }else{
				return false;
		    	return false;
		    }
		});

		$("#delete"+deleteV).off().click(function() {
			var ids = $("#table"+ tableV+ " input[type='checkbox']:checkbox:checked").map(function() {
				return this.value;
			}).get().join(",");
			
			if (ids == null || ids == "") {
				showFormError('Please select at least one record to delete.');
				return false;
			}
			var r = confirm("Are you sure you want to delete?");
			if (r != true)
				return false;

			$(this).callAjax("delete" + deleteV, {
				checked : ids
			});
		});

		// All button get initialized when user switch form
		$("#send"+buttonV).off().click(function() {
		    // If all form's required fields are filled
			showWait();
			validateForm();
		    if(formValidated){
				$(this).callAjax("send" + buttonV,populateFormData());
		    }else{
				return false;
		    	return false;
		    }
		});

	};

	$(function() {
	  $('.dropdown').change(function(){
	    $('.formDiv').hide();
	    $('#' + $(this).val()).show();
	    var tab = ($(this).val()).replace("Div","");
	  	if(tab){
			$switchInputs(capitalize(tab));
			// Activated data table
			loadDataTable();
	  	}
	  	
	  	$("select").each(function() {
	  		if(this.value == tab+"Div")
	  			this.value = tab+"Div"
	  		else	
	  			this.selectedIndex = 0 
	  	});
	  	
	  	// having below block on every switch to get it work
		// Edit table click on row
		$("#table" + tableV).on( 'click', 'tr', function () {
/*
 * var r = confirm("Any selection if you have will be discarded, Are you sure do
 * you want to edit?"); if (r != true) return false;
 * 
 */			
			resetForm();
			if(tableV==="Fc"){
				var ids = $("#table"+ tableV+ " input[type='checkbox']:checkbox:checked").map(function() {
					return this.value;
				}).get().join(",");
				if(!ids && ids.lenght>0){
					removeTableBody();
				showFormError('Edit is not allowed. Please delete and submit a new record.');
				}
			}else{
				if (tableV=="Sell"){ 
					// Sell: a sale is a multi-line invoice — load the WHOLE invoice (all its lines +
					// customer) into the cart (iDiv) so the user can review/update and save in place.
					var sdoc = getDocument(datatable.row(this).data());
					var sidEl = sdoc.getElementById('sellId');
					if (sidEl && sidEl.textContent.trim() && typeof loadSellForEdit === 'function') {
						loadSellForEdit(sidEl.textContent.trim());
					}
				} else {
					var html = datatable.row(this).data();// .selector.rows.innerHTML;
					var doc = getDocument(html);
					editRecord(doc);
				}
						
				// updateReadOnly(false);
			}
		} );
	  });
	});

	$.fn.callAjax = function(method, data) {
		$.ajax({
			type : "POST",
			url : serverContext + method,
			dataType : "json",
// timeout : 100000,
			data : data,

			success : function(data) {
				hideWait();
				if(data.status==="FOUND"){
					showFormError(data.message || 'This record already exists.');
					return false;
				}else if(data.status==="ERROR"){
					showFormError(data.message || 'An error occurred. Please try again.');
					return false;
				}else if(data.status==="FAILED"){
					showFormError(data.message || 'Failed to save. Please try again.');
					return false;
				}
				if(method!=="sendAlerts"){
					datatable.clear().draw();
					datatable.ajax.reload();
					resetForm();
					clearFormError();
				}
				return false;
			}, fail: function(data, textStatus, errorThrown) {
				hideWait();
			showFormError('Network error. Please check your connection and try again.');
			}, error: function(data, textStatus, errorThrown) {
				hideWait();
				resetGlobalError();
                if(textStatus==="parsererror"){
                	window.location.href = serverContext + "login?message=" + errorThrown;
		        }
		        else if(data.responseJSON.error.indexOf("InternalError") > -1){
		            window.location.href = serverContext + "login?message=" + data.responseJSON.message;
		        }

				var errors = $.parseJSON(data.responseJSON.message);
	         	$.each( errors, function( index,item ){
	            	if (item.field){
	            		$("[name="+item.field+"]").addClass("alert-danger");
	            		$("#globalError").show().append(escHtml(item.defaultMessage)+"<br/>");
	            		$('html, body').animate({ scrollTop: $('#globalError').offset().top }, 'slow');
	            	}
	            	else {
	            		$("#globalError").show().append(escHtml(item.defaultMessage)+"<br/>");
	            		$('html, body').animate({ scrollTop: $('#globalError').offset().top }, 'slow');
	            	}
	         	});
            }
		}).fail(function(data) {
			hideWait();
			showFormError('Request failed. Please recheck inputs or contact the system administrator.');
		});
		if(tableV=="Purchase"){	
			resetPurchaseForm();
		}
		edit = false;// when add/update & delete done
	}	
});

function populateFormData(){
    obj = {};
// var myForm = document.getElementById(tableV);
	for(var i=0; i<(formFields); i++){
		if(document.getElementById(form[i].id)){
			if(form[i].tagName=="SELECT"){
				var list = [];
				for(var option of document.getElementById(form[i].id).selectedOptions){
					list.push(option.value)
				}
		    	obj[form[i].name] = $.trim(list);
			}else{
		    	obj[form[i].name] = $.trim(document.getElementById(form[i].id).value);
			}
		}
	}
	// if(buttonV=="Purchase"){
	// 	var purchaseItemDD = document.getElementById("purchaseItemDD");
	// 	var itemId = purchaseItemDD.options[purchaseItemDD.selectedIndex].value;		
	// 	var item = {};
	// 	item = {"id":itemId};
	// 	obj['item'] = item
	// }
	
	return $.param(obj);
}


function jsonPost(method,data) {
	var r = true;// confirm("Are you sure you want to Sell?");
	if (r != true)
		return false;
	
	var printData = data;
	$.ajax({
	      type : "POST",
	      contentType : "application/json",
	      url : serverContext + method,
	      data : JSON.stringify(data),//populateFormData()
	      dataType : 'json',			
	      success : function(data) {
			if(data.status!="SUCCESS"){
				showFormError(data.message || 'Sale could not be completed. Please check all fields and try again.');
				return;
			}
			clearFormError();
			// slice 22: show the system-generated per-org invoice number returned by addSell
			if (data.object) {
				showSaleSuccess('Sale recorded — Invoice ' + data.object);
				// G6 (slice 38): auto-print the receipt for a new sale (hidden iframe — no popup block).
				if (method === 'addSell' && typeof printReceipt === 'function') { printReceipt(data.object); }
				// P6 (slice 43): if this sale is dispensing a prescription, record the dispense against it.
				if (method === 'addSell' && window.dispensingPrescriptionId && typeof dispensePrescription === 'function') {
					dispensePrescription(data.object);
				}
				// E1 (slice 46): a Store (MARKETPLACE) sale becomes an order with a fulfilment lifecycle.
				if (method === 'addSell' && (window.MODULE || '').toUpperCase() === 'MARKETPLACE' && typeof recordOrder === 'function') {
					recordOrder(data.object);
				}
			}
/*
		    	var mylink = document.getElementById("MyLink");
		    	mylink.setAttribute("href", "../");
		        mylink.setAttribute("href", ".."+serverContext+"reports/createdocument.docx");
		        mylink.click();
			}
*/
			loadDataTable();
			resetCart();
		}, fail: function(data, textStatus, errorThrown) {
			showFormError('Network error. Please check your connection and try again.');
		}, error: function(data, textStatus, errorThrown) {
			resetGlobalError();
        	window.location.href = serverContext + "login?message=" + errorThrown;			
       	}
	}).fail(function(data) {
			showFormError('Request failed. Please recheck inputs or contact the system administrator.');
	});
	edit = false;// when add/update & delete done
}

const capitalize = (s) => {
  if (typeof s !== 'string') return ''
  return s.charAt(0).toUpperCase() + s.slice(1)
}

const nonCapitalize = (s) => {
	if (typeof s !== 'string') return ''
  		return s.charAt(0).toLowerCase() + s.slice(1)
}

// This is a helper function to extract text from the datatable row's HTML and populate the form for editing
function editRecord(doc){
	edit = true;
	resetGlobalError();
	clearFormError();
    var form = document.getElementsByClassName('form-horizontal')[tableV];
	if (!form || form.length<=2) {
		return false;
	}
    formFields = form.length-2;
	for(var i=0; i<(formFields); i++) {
		if(form[i].id) {
			const element = doc.getElementById(form[i].id);
			if (!element) {
				continue;
			}
			var text = element.textContent;
			if(form[i].tagName=="SELECT") {
				var labels = text.split(",");
				labels.forEach(function(entry) {
					$("#"+form[i].id+" option").each(function() {
						if(text === (($(this).text()).split(" ~ ")[0])) {
							text = $(this).text();//update text if it is with siplitter
							$(this).prop('selected', true);
						}else{
							$(this).prop('selected', false);
						}  
					});
				});
			}else{
				text = (text == "null"?"":text);
				$("#"+form[i].id).val(text);
			}
			// Handled bootstrap drop down
			if(form[i].className.indexOf("selectpicker")>-1) {
				$( "#"+form[i].id+" :selected" ).text(text);
				$("#"+form[i].id).selectpicker('refresh');
			}
		}

		// make readonly the key fields when user edit the records
		updateReadOnly(true);
	}
	// stock should be now quantity for purchase form, so update it
	if (tableV=="Purchase") {
		this.updatePurchaseForm($("#purchaseStock").val());
	}
}


$('#reset').on('click', function() {
	updateReadOnly(false);
    // Reset all form fields
    // $('#yourFormId')[0].reset();
    
    // // Clear any validation errors
    // $('.error-message').hide();
    // $('.has-error').removeClass('has-error');
    
    // // Clear any success/error alerts
    // $('.alert').hide();
    
    // // Reset select dropdowns if using custom selects
    // $('select').val('');
    
    // // Clear any dynamic content
    // $('#someResultDiv').empty();
});

function updateReadOnly(flag) {
	if (tableV) {
		$("#"+tableV.toLowerCase()+"Name").prop("readonly", flag);
	}
	if (tableV == "Purchase") {
		$("#purchaseInvoiceNo").prop("readonly", flag);
		$('#purchaseItemDD').prop('disabled', flag);
	} else if (tableV == "Sell") {
		// NOTE: do NOT clear window.editingInvoice or the edit banner here. updateReadOnly only toggles
		// field read-only/disabled state and is called repeatedly (e.g. resetBSDD after every cart add) —
		// wiping the edit flag here made every invoice edit fall through to addSell (new invoice + dup row).
		// Edit state is owned by loadSellForEdit (set) and exitSellEditMode (clear, on save/cancel).
		setSellItemBtnMode(flag);
		$('#sellItemDD').prop('disabled', flag);
		if($('#sellItemDD').data('selectpicker')) $('#sellItemDD').selectpicker('refresh');
		// Sell edit mode is keyed on window.editingInvoice (the single source of truth that persists
		// through cart edits), NOT the shared `edit` global which resetBSDD/other cycles flip off.
		if (window.editingInvoice) {
			$('#sellCustomerDD').prop('disabled', true);   // lock the customer while editing an invoice
			$('#sellCN').prop('disabled', true);
			$('#sellCC').prop('disabled', true);
		} else {
			$('#sellCustomerDD').prop('disabled', flag);
			$('#sellCN').prop('disabled', flag);
			$('#sellCC').prop('disabled', flag);
		}
	}
	// } else if (tableV == "Customer") {
	// 	$("#name").prop("readonly", flag);
	// } else if (tableV == "Item") {
	// 	$("#itemName").prop("readonly", flag);
	// }
}
function updatePurchaseForm(batchStock){
	($("#purchaseQuantity").val(batchStock));
}

function resetBSDD(id){
	updateReadOnly(false);
	edit = false;// when reset boot strap drill down
	$("#"+id).val('default').selectpicker("refresh");
}

function parseDate(dateStr, format) {
  const regex = format.toLocaleLowerCase()
    .replace(/\bd+\b/, '(?<day>\\d+)')
    .replace(/\bm+\b/, '(?<month>\\d+)')
    .replace(/\by+\b/, '(?<year>\\d+)')
  
  const parts = new RegExp(regex).exec(dateStr) || {};
  const { year, month, day } = parts.groups || {};
  return parts.length === 4 ? new Date(year, month-1, day) : undefined;
}

function dateToYMD(date) {
    var d = date.getDate();
    var m = date.getMonth() + 1;
    var y = date.getFullYear();
    return '' + y + '-' + (m<=9 ? '0' + m : m) + '-' + (d <= 9 ? '0' + d : d);
}

function dateToDMY(date) {
    var d = date.getDate();
    var m = date.getMonth() + 1;
    var y = date.getFullYear();
    return (d <= 9 ? '0' + d : d)+ '-' + (m<=9 ? '0' + m : m) + '-' + '' + y ;
}

function dateToD3MY(date) {
    var d = date.getDate()+9;
    var m = date.getMonth();
    var y = date.getFullYear();
    return (d <= 9 ? '0' + d : d)+ '-' + (m<=9 ? '0' + m : m) + '-' + '' + y ;
}

function getMonth(){
	var d = new Date();
	return month[d.getMonth()];
}

function getCurrentMonth(d){
	return month[d.getMonth()];
}

function getMonthYear(d){
	return month[d.getMonth()]+" "+d.getFullYear();
}

function getNextMonthYear(d){
	return month[d.getMonth()+1]+" "+d.getFullYear();
}

function getN_NextMonthYear(d,n){
	return month[d.getMonth()+n]+" "+d.getFullYear();
}

function currentdateByDay(d) {
	var date = new Date();
    var m = date.getMonth() + 1;
    var y = date.getFullYear();
    return (d <= 9 ? '0' + d : d)+ '-' + (m<=9 ? '0' + m : m) + '-' + '' + y ;
}

function currentFormattedDate() {
	var date = new Date();
	var d = date.getDate();
    var m = date.getMonth() + 1;
    var y = date.getFullYear();
    return (d <= 9 ? '0' + d : d)+ '-' + (m<=9 ? '0' + m : m) + '-' + '' + y ;
}

function currentFormattedDateTime() {
	var date = new Date();
	var d = date.getDate();
    var m = date.getMonth() + 1;
    var y = date.getFullYear();
    return (d <= 9 ? '0' + d : d)+ '-' + (m<=9 ? '0' + m : m) + '-' + '' + y+" "+date.getHours()+":"+date.getMinutes() ;
}

function currentFormattedNextYearDate() {
	var date = new Date();
	var d = date.getDate();
    var m = date.getMonth() + 1;
    var y = date.getFullYear()+1;
    return (d <= 9 ? '0' + d : d)+ '-' + (m<=9 ? '0' + m : m) + '-' + '' + y ;
}

function formToJSON(formId){
	var myForm = document.getElementById(formId);
    var formData = new FormData(myForm),
    obj = {};
    stock = {};
    for (var entry of formData.entries()){
    	var key = entry[0];
    	var val = entry[1];
    	if(key && key.indexOf(".")>0){
    		var mainKey = key.split('.')[0];
    		var keyVal = key.split('.')[1];
    		stock[keyVal] = $.trim(val);
    	}else{
        	obj[key] = $.trim(val);
    	}
    }
    if(mainKey)
    	obj[mainKey] = stock;

    return obj;
}

function toDataURL(url, callback) {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		var reader = new FileReader();
	    reader.onloadend = function() {
	      callback(reader.result);
	    }
	    reader.readAsDataURL(xhr.response);
	};
	xhr.open("GET", url);
	xhr.responseType = "blob";
	xhr.send();
}

function checkfile(file) {
    var validExts = new Array(".csv");
    var fileExt = $("#csvFile").val();
    fileExt = fileExt.substring(fileExt.lastIndexOf('.'));
    if (validExts.indexOf(fileExt) < 0) {
      showFormError("Invalid file selected. Allowed types: " + validExts.toString());
      return false;
    }
    else return true;
}

function getImgFromUrl(logo_url, callback) {
    var img = new Image();
   // var logo_url = serverContext+"resources/a.jpg";
    img.src = logo_url;
    img.onload = function () {
        callback(img);
    };
} 

function handleKey(event,action,elementId)
{
	if (event.keyCode === 13 &&  action == "click") {
        $("#"+elementId).click();
	}else if (event.keyCode === 9 &&  action == "table") {
            $("#"+elementId).focus();
	}else if (event.keyCode === 9 &&  action == "table") {
        $("#"+elementId).focus();
    }
}

function handleEnterKey(event,action,elementId)
{
	if (event.keyCode === 13 &&  action == "enter") {
        $("#"+elementId).click();
    }
}

function handleTabKey(event,action,elementId)
{
	if (event.keyCode === 9 &&  action == "tab") {
            $("#"+elementId).focus();
    }
}

function handleKey(event,action,elementId)
{
	if (event.keyCode === 105 &&  action == "click") {
        $("#"+elementId+".div.button").click();
	}else if (event.keyCode === 73 &&  action == "focus") {
		$("#"+elementId).click();
    }
}

/**
 * Displays overlay with "Please wait" text. Based on bootstrap modal. Contains
 * animated progress bar.
 */
function showWait() {
    var modalLoading = '<div class="modal" id="pleaseWaitDialog" data-backdrop="static" data-keyboard="false" role="dialog">\
        <div class="modal-dialog">\
            <div class="modal-content">\
                <div class="modal-header">\
                    <h4 class="modal-title">Please wait...</h4>\
                </div>\
                <div class="modal-body">\
                    <div class="progress">\
                      <div class="progress-bar progress-bar-success progress-bar-striped active" role="progressbar"\
                      aria-valuenow="100" aria-valuemin="0" aria-valuemax="100" style="width:100%; height: 40px">\
                      </div>\
                    </div>\
                </div>\
            </div>\
        </div>\
    </div>';
    $(document.body).append(modalLoading);
    $("#pleaseWaitDialog").modal("show");
}

/**
 * Hides "Please wait" overlay. See function showPleaseWait().
 */
function hideWait() {
    $("#pleaseWaitDialog").modal("hide");
}
//It is being used to populate any HTML DD 
function loadDD(remoteMethod,DDID) {	
	$("#"+DDID).empty();
    $.get(serverContext+ remoteMethod,function(data){
   		$("#"+DDID).append(data);
    })
	.fail(function(data) {
		$("#"+DDID).empty().append("<option value = ''> System error  </option>");
	});
}

//It is being used to populate any BS DD
function loadBSDD(remoteMethod,DDID) {
	$("#"+DDID).empty();
    $.get(serverContext+ remoteMethod,function(data){
    	$("#"+DDID).empty().append(data).selectpicker('refresh');
    })
	.fail(function(data) {
		$("#"+DDID).empty().append("<option value = ''> System error  </option>");
	});
}

function getDocument(html){
	return new DOMParser().parseFromString(html, "text/html");
}

var DateDiff = {
    inDays: function(d1, d2) {
        var t2 = d2.getTime();
        var t1 = d1.getTime();

        return parseInt((t2-t1)/(24*3600*1000));
    },

    inWeeks: function(d1, d2) {
        var t2 = d2.getTime();
        var t1 = d1.getTime();

        return parseInt((t2-t1)/(24*3600*1000*7));
    },

    inMonths: function(d1, d2) {
        var d1Y = d1.getFullYear();
        var d2Y = d2.getFullYear();
        var d1M = d1.getMonth();
        var d2M = d2.getMonth();

        return (d2M+12*d2Y)-(d1M+12*d1Y);
    },

    inYears: function(d1, d2) {
        return d2.getFullYear()-d1.getFullYear();
    }
}
