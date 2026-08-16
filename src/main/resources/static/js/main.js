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
// TRUE only between "a section was opened" (loadDataTable) and that grid's FIRST successful load, which
// is when the section's associated dropdowns are preloaded. A datatable.ajax.reload() must not preload
// them again: P6 rapid entry reloads after every saved line with the form still open, so rebuilding the
// pickers there wipes what the operator is in the middle of using. Declared here beside the other
// cross-module grid state (tableV/getAll/datatable/reload) that main.js and business.js share.
var pickerPreloadPending = false;
var ONE = 1;
var ZERO = 0;
var HUNDRED = 100;
var edit = false;

/**
 * The FOUR values CustomerType actually has, with the labels the add-customer form uses.
 *
 * There is no fifth. A "RETAIL" once appeared in this picker and in the shared report filter — a value no
 * customer can ever hold, so a tier rule scoped to it would have silently never fired, and VIP was missing
 * although VIP exists precisely to grant a better price. Read the list off the enum, never off another list.
 */
var CUSTOMER_TYPE_LABELS = {
	WALK_IN:   'ui.js.custTypeWalkIn',
	RETAILER:  'ui.js.custTypeRetailer',
	WHOLESALE: 'ui.js.custTypeWholesale',
	VIP:       'ui.js.custTypeVip'
};

function customerTypeLabel(value){
	var key = CUSTOMER_TYPE_LABELS[value];
	return key ? t(key) : String(value || '').replace('_', ' ');
}

var s2n = function(v){
	if(isNaN(v))
		return 0;
	else
		return v*ONE;
}

/**
 * A focused <input type="number"> changes its VALUE when the wheel is scrolled over it.
 *
 * That is browser default behaviour and a well-known hazard in any billing UI: a cashier tabs into
 * Amount Received or a purchase rate, scrolls the page to see the rest of the invoice, and the figure
 * silently changes underneath them. Nothing warns, nothing validates — the sale simply posts a number
 * nobody typed. On a POS that is money, and it is invisible until the books disagree.
 *
 * BLUR rather than preventDefault: this listener is passive (preventDefault would be ignored, and a
 * non-passive wheel handler penalises scrolling on every page). Dropping focus stops the value change
 * AND lets the page scroll normally, which is what the operator actually wanted.
 *
 * Keyboard ↑/↓ is deliberately left alone — that is a deliberate act on a field you are looking at.
 *
 * Registered once, for every number input in the app: sell, purchase, fees, donations. A guard that
 * only covered the sell screen would leave the same bug on every other money form.
 */
document.addEventListener('wheel', function (e) {
    var el = document.activeElement;
    if (el && el.type === 'number' && el === e.target) { el.blur(); }
}, { passive: true });

function resetGlobalError(){
    $(".alert").html("").hide();
    $(".error-list").html("");
}

function showFormError(msg) {
    var el = document.getElementById('globalError');
    if (el) {
        el.textContent = msg;
        el.style.display = 'block';
    }
    // A CRUD form now lives inside a fixed .crud-overlay modal; the inline #globalError banner sits in the
    // page flow BEHIND that overlay, so the user never sees it (e.g. a duplicate-SKU 409). When a modal is
    // open, surface the message as a fixed toast that stacks above the modal. Otherwise keep the classic
    // inline banner + scroll-to (used by the auth pages and non-modal screens).
    if (document.querySelector('.crud-overlay.open')) {
        showErrorToast(msg);
    } else if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
}

/**
 * The single place a failed AJAX call is turned into user-visible behaviour.
 *
 * WHY THIS EXISTS: fourteen call sites across six module files each did
 *
 *     error: function (jqXHR, textStatus, errorThrown) {
 *         window.location.href = serverContext + "login?message=" + errorThrown;
 *     }
 *
 * — i.e. ANY failure logged the user out. A 404 on one widget, a 500 from one report, a dropped
 * connection: all of them threw the operator back to the login page with their unsaved work gone, and
 * left them believing their session had expired when it was perfectly valid.
 *
 * It also actively HID the real fault. Clicking Academic Year fires a phantom `/getUserAcademicYear`
 * (main.js's generic section handler assumes every screen follows the getUser<Name> convention; the
 * education Phase 1/2 screens have their own loaders and no such endpoint). That 404 is harmless in
 * itself — but routed through the old handler it presented as "clicking Academic Year logs me out",
 * which reads as an auth bug and sends you looking in entirely the wrong place.
 *
 * THE RULE: only a genuine loss of session sends the user to /login. Everything else is an error to
 * show, not a reason to end their session.
 *
 * @param jqXHR        the jQuery XHR
 * @param errorThrown  jQuery's errorThrown
 * @param what         optional short label for the failing operation, so the message names it
 */
function handleAjaxFailure(jqXHR, errorThrown, what) {
    var status = jqXHR ? jqXHR.status : 0;
    var body = (jqXHR && typeof jqXHR.responseText === 'string') ? jqXHR.responseText : '';

    // Session genuinely gone, in the three shapes this app produces:
    //  401                                  — unauthenticated
    //  the login PAGE itself                — Spring redirected 302 -> /login and jQuery followed it
    //  "This session has been expired…"     — ConcurrentSessionFilter (plain text, not JSON)
    // A 403 is deliberately NOT here: that is "logged in but not allowed", and bouncing a user to the
    // login screen for a privilege they lack is both wrong and confusing — they log back in and hit it again.
    var sessionLost = status === 401
        || /name="username"|id="loginSubmit"/.test(body)
        || /This session has been expired/i.test(body);

    if (sessionLost) {
        window.location.href = serverContext + "login?message=" + (errorThrown || "");
        return;
    }

    if (typeof console !== 'undefined' && console.error) {
        console.error('AJAX failure' + (what ? ' [' + what + ']' : ''), status, errorThrown, body.slice(0, 300));
    }
    showFormError((what ? what + ': ' : '') + (errorThrown || 'Request failed')
        + (status ? ' (' + status + ')' : ''));
}

// Fixed, dismissable error toast — always visible, stacks above the CRUD modal overlay (z-index 1050).
function showErrorToast(msg) {
    var el = document.getElementById('formErrorToast');
    if (!el) {
        el = document.createElement('div');
        el.id = 'formErrorToast';
        el.setAttribute('role', 'alert');
        el.style.cssText = 'position:fixed;top:16px;left:50%;transform:translateX(-50%);z-index:10001;'
            + 'background:#c62828;color:#fff;padding:12px 42px 12px 18px;border-radius:8px;font-size:14px;'
            + 'font-weight:600;box-shadow:0 8px 28px rgba(0,0,0,.35);max-width:92vw;cursor:pointer;'
            + 'white-space:normal;text-align:center';
        el.title = 'Dismiss';
        el.addEventListener('click', function () { el.style.display = 'none'; });
        document.body.appendChild(el);
    }
    el.textContent = msg;
    el.style.display = 'block';
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.style.display = 'none'; }, 8000);
}
window.showErrorToast = showErrorToast;

function clearFormError() {
    var el = document.getElementById('globalError');
    if (el) {
        el.textContent = '';
        el.style.display = 'none';
    }
    var toast = document.getElementById('formErrorToast');
    if (toast) toast.style.display = 'none';
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
        var missing = [], invalid = [], firstBad = null;
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
                if(!firstBad) firstBad = el;                 // remember it: we'll take the user straight there
                var label = $('label[for="' + form[i].id + '"]').text().replace(/\s*\*\s*$/, '').replace('req','').trim()
                    || el.placeholder || el.name || form[i].id;
                // A blank field and an out-of-range value are different problems. Reporting a min/step violation
                // as "please fill in the required fields" sends the user hunting for an empty box that isn't there.
                if(form[i].validity.valueMissing){
                    missing.push(label);
                } else if(form[i].validity.rangeUnderflow){
                    invalid.push(label + ' (must be at least ' + form[i].min + ')');
                } else if(form[i].validity.rangeOverflow){
                    invalid.push(label + ' (must be at most ' + form[i].max + ')');
                } else {
                    invalid.push(label);
                }
            }
        }
        formValidated = false;
        var msgs = [];
        if(missing.length > 0) msgs.push('Please fill in the required fields: ' + missing.join(', '));
        if(invalid.length > 0) msgs.push('Please correct: ' + invalid.join(', '));
        if(msgs.length > 0) showFormError(msgs.join('. '));
        // Naming the bad field isn't much help on a form taller than the screen — go to it.
        if(firstBad && typeof focusInvalid === 'function') focusInvalid(firstBad);
    } else {
        clearFormError();
    }
    if(form) formFields = form.length - 2;
}

/**
 * Default empty date boxes to now. Called on every .onChangeSelect change, so it runs whenever a picker
 * is touched — including the purchase item picker, once per line.
 *
 * It used to assign UNCONDITIONALLY, which meant selecting an item silently overwrote a date the
 * operator had typed. A back-dated purchase (yesterday's delivery entered this morning, which is the
 * normal case) could not be saved at all: choose the item, and the date snapped back to today with no
 * indication it had changed. On a multi-line bill it re-armed on every line.
 *
 * Now it FILLS rather than OVERWRITES. A blank box still gets a sensible default; a value a person put
 * there is theirs. "Default" and "overwrite" are not the same operation and this only ever wanted the
 * first one.
 */
var initDates = function(){
	var dateTimeInputs = $('.datetimepicker');
	for(var i=0; i<dateTimeInputs.length;i++){
		if(!dateTimeInputs[i].value) dateTimeInputs[i].value= moment().format('DD-MM-YYYY HH:mm:ss');
	}
	var dateInputs = $('.datePicker');
	for(var i=0; i<dateInputs.length;i++){
		if(!dateInputs[i].value) dateInputs[i].value= moment().format('DD-MM-YYYY');
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

		// make readonly false on reset, so user can edit the form again
		// updateReadOnly(false);
		// if($form.length === 0){
		// 	return;
		// }
									
		// for(var i=0; i<$form.length; i++){
		// 	 $("#"+form[i].id).removeClass("alert-danger");
		// }
		// $(".form-control").val("");		
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

	// EVERY date/datetime field in the app is bound in ONE place — /js/common/date-picker.js. Binding a second
	// plugin to the same input is what caused the "date clears when you tab out" bug: each re-parses the value
	// with its own format on blur and the loser writes back an empty string. Do not re-add picker bindings here.
	// #dueDateTemp used to be bound here with bootstrap-datepicker; it now opts in via
	// data-dp="date" data-dp-iso="#dueDate", so the shared calendar writes both the visible value and the hidden
	// ISO one it feeds.

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

				// Whether a customer must be named is now the TENANT's policy (pos.customer.required,
				// default ON = the long-standing behaviour). A wholesaler invoices accounts and wants it
				// enforced; a retail counter ringing up cash does not, and typing a name per customer is
				// the single biggest queue cost at a shop that runs no accounts.
				//
				// CREDIT is the exception that is not configurable: money owed has to be owed BY someone.
				// A tenant who switches the requirement off still cannot sell on account anonymously.
				var isSelectMode = $('#btnModeSelect').hasClass('active');
				var payMethod = $("#sellPayMethod").val();
				var owesBalance = ($("#sellCh").val()*ONE < 0) || payMethod === 'CREDIT';
				// D-24 (2026-08-10): the customer is required ONLY when the sale leaves a balance.
				// A fully-paid sale — cash, card, exact — completes with nobody named, which is what a
				// walk-in counter needs and what the mobile/due-date changes already assumed.
				//
				// The balance case is NOT configurable and never will be: a receivable against nobody
				// cannot be chased, aged or collected. Every other field made optional here is an
				// inconvenience when missing; this one makes the money unrecoverable.
				var customerRequired = owesBalance;
				if (customerRequired) {
					if (isSelectMode) {
						if (!$("#sellCustomerDD").val()) {
							document.getElementById("sellCustomerDD").style.setProperty('border-color', 'red', 'important');
							showFormError('This sale leaves a balance — choose the customer who owes it.');
							return;
						}
						document.getElementById("sellCustomerDD").style.removeProperty('border-color');
					} else {
						if ($("#sellCN").val().trim() == "") {
							document.getElementById("sellCN").style.setProperty('border-color', 'red', 'important');
							showFormError('This sale leaves a balance — name the customer who owes it.');
							return;
						}
						document.getElementById("sellCN").style.removeProperty('border-color');
					}
				} else if (!isSelectMode && $("#sellCN").val().trim() === "") {
					// Anonymous walk-in: stamp the configured name so the invoice still has a payee.
					// An invoice with a blank customer is not "faster", it is unattributable.
					$("#sellCN").val(window.posWalkInName || 'Walk-in Customer');
				}

				// Proceed when the cart has items AND (payment received OR still owing OR we're editing an existing
				// invoice — a fully-paid edit has no new payment and zero due but must still be submittable). SF-8:
				// grouped explicitly (was `A && B && C || D`, which let a no-item owing state through).
				if(data && data.length>0 && ($("#sellRec").val()*ONE>0 || $("#sellCh").val()*ONE < 0 || ($("#sellStoreCredit").val()*ONE>0) || window.editingInvoice)){
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


					// A sale that leaves a balance (Credit, or a partial payment) needs a due date so the
					// receivable can be followed up — the field is required, not optional, whenever there's a due.
					// if ($("#sellCh").val()*ONE < 0 && !$('#dueDate').val()) {
					// 	document.getElementById("dueDateTemp").style.setProperty('border-color', 'red', 'important');
					// 	showFormError('Please set a due date for the outstanding amount.');
					// 	return;
					// }

					var customer = {"name":$("#sellCN").val(), "contact":$("#sellCC").val(), "paidAmount":$("#sellRec").val(),"dueAmount":$("#sellCh").val(), "dueDate":$('#dueDate').val()};
					// SF-5 Model B: redeeming store credit needs an identified (existing) customer — send the selected id.
					if (isSelectMode && $("#sellCustomerDD").val()) customer.customerId = Number($("#sellCustomerDD").val());
					var customerHistory = {"customer":customer, "sales":data};
					// B2B-P3g: the invoice-level TRADE DISCOUNT, distinct from the per-line discounts already
					// carried on each cart line. A distribution invoice settles a whole-order concession at the
					// foot of the document. Sent only when actually entered, so a B2C sale is unchanged.
					// NOTE: the monolith proxy binds a TYPED CustomerHistoryDTO and re-serialises it, so this
					// also needs its twin field there or it is silently dropped on the way to business-service.
					var tradeDisc = $("#sellTradeDiscount").val();
					if (tradeDisc != null && tradeDisc !== '' && Number(tradeDisc) > 0) {
						customerHistory.tradeDiscount = Number(tradeDisc);
					}
					// B1 (pharmacy): declare the prescription this sale dispenses. Its presence is what lets a
					// prescription-only medicine through the server-side sell guard; the post-sale dispense call
					// then reconciles what was actually sold against what was prescribed.
					if (window.dispensingPrescriptionId) customerHistory.prescriptionId = window.dispensingPrescriptionId;
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
					// SF-5 Model B: applied store credit → a STORE_CREDIT tender (server caps it at the balance).
					var scApply = $("#sellStoreCredit").val()*ONE || 0;
					if (scApply > 0 && customer.customerId) {
						customerHistory.tenders.push({ "method": "STORE_CREDIT", "amount": scApply, "reference": "" });
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
						// SF-3: one idempotency key per checkout attempt — a double-click / retry reuses it so the
						// server records ONE invoice. Reset only after a successful sale (see jsonPost).
						customerHistory.idempotencyKey = getSaleIdempotencyKey();
						jsonPost("addSell", customerHistory);
					}
			    }else{
					    	document.getElementById("sellRec").style.setProperty('border-color', 'red', 'important');
					    	showFormError('Please add items to the cart and enter a valid payment amount.');
					    }
			}else{
				// Purchase (add AND edit — this handler serves both): block client-side when no item is selected,
				// or quantity <= 0, or the unit purchase price <= 0. A "0" passes the generic required-field check,
				// which only tests for non-empty — and a zero-cost bill is not a harmless typo: it silently wrecks
				// margin on every sale of that batch and posts a zero COGS/inventory value to the ledger.
				if(buttonV=="Purchase"){
					var pItem = $("#purchaseItemDD").val();
					var pQty = $("#purchaseQuantity").val()*1;
					if(!pItem || !(pQty > 0)){
						$("#purchaseQuantity").css('border-color','red');
						showFormError('Select an item and enter a quantity greater than 0.');
						if (typeof focusInvalid === 'function') focusInvalid(document.getElementById(pItem ? 'purchaseQuantity' : 'purchaseItemDD'));
						return false;
					}
					$("#purchaseQuantity").css('border-color','');

					var pRate = $("#purchasePurchaseRate").val()*1;   // '' -> 0, non-numeric -> NaN; both rejected
					if(!(pRate > 0)){
						$("#purchasePurchaseRate").css('border-color','red');
						showFormError('Enter a purchase price (P/U Price) greater than 0.');
						if (typeof focusInvalid === 'function') focusInvalid(document.getElementById('purchasePurchaseRate'));
						return false;
					}
					$("#purchasePurchaseRate").css('border-color','');
				}
				validateForm();
			    if(formValidated){
					var fd = populateFormData();
					// M4c (slice 92) / fix: submit the purchase productId-native from the picker's data-product.
					// populateFormData() returns a URL-encoded STRING ($.param), so productId must be APPENDED to it —
					// `fd.productId = ppid` was a silent no-op on a string, so productId was never sent and the purchase
					// saved with productId=null (skipped by getUserPurchase + never stocked into inventory).
					var action = "add" + buttonV;
					if(buttonV=="Purchase"){
						var ppid = $("#purchaseItemDD :selected").data('product');
						if(ppid != null && ppid !== '') fd += "&productId=" + encodeURIComponent(ppid);
						// Edit mode → update path: reconciles inventory by the qty DELTA instead of re-importing the
						// full quantity (which double-counted stock). Keyed on the readonly #purchaseId.
						var pIdVal = $("#purchaseId").val();
						if(pIdVal && pIdVal*1 > 0) action = "updatePurchase";
					}
					$(this).callAjax(action, fd);
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
			var count = ids.split(",").length;
			uiConfirm({
				title: count === 1 ? 'Delete this record?' : 'Delete ' + count + ' records?',
				message: 'This removes ' + (count === 1 ? 'it' : 'them') + ' from your records and cannot be undone.',
				confirmText: count === 1 ? 'Delete' : 'Delete ' + count,
				tone: 'danger'
			}).then(function (ok) {
				if (ok) performBulkDelete(deleteV, ids);
			});
			return false;
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
	    var $shown = $('#' + $(this).val()).show();
	    // These dashboards are taller than the viewport: scroll the section the user just picked under the
	    // sticky header and put the cursor in its first field, instead of leaving them to hunt and scroll.
	    if (typeof revealSection === 'function') revealSection($shown[0]);
	    var tab = ($(this).val()).replace("Div","");
	  	// Convention over configuration: a registration screen is <Name>Div + #table<Name> fed by
	  	// GET /getUser<Name>, so this one handler serves them all. Nine legacy registers (Owner, School,
	  	// Grade, Staff, Guardian, Student, Subject, Vehicle, Discount) still depend on exactly this.
	  	//
	  	// The education Phase 1/2 screens are a different SHAPE — a year with nested terms, a marks roster
	  	// keyed by exam+paper, a timetable grid — so each ships its own loader and correctly has no
	  	// /getUser<Name> endpoint. Running the generic loader for them fired a request at an endpoint that
	  	// has never existed: 15 screens, a guaranteed 404 each. Until slice 107 that 404 was routed into a
	  	// redirect to /login, which is why it presented as "clicking Academic Year logs me out".
	  	//
	  	// Screens opt OUT declaratively, on the div itself (data-self-load="true"), so a new screen states
	  	// its own contract where it is defined. A registry in this file would be one more distant list to
	  	// forget — which is exactly how the convention silently became an assumption.
	  	if(tab && !$shown.data('selfLoad')){
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
		$("#table" + tableV).on( 'click', 'tr', function (e) {
			if (tableV == "Sell") {
				return; // SF-8: a sale is a multi-line invoice — edit only via the per-row Edit button, not a stray click anywhere in the row.
			} 

			// Register-screen modal layer (opt-in: only when #<tableV>Modal exists). A checkbox click is a
			// bulk-SELECT (update the action bar), NOT an edit — so multi-select works without opening a form.
			var crudHasModal = $('#' + tableV + 'Modal').length > 0;
			if (crudHasModal && $(e.target).is("input[type='checkbox']")) {
				if (typeof refreshBulkBar === 'function') refreshBulkBar(tableV);
				return;
			}
			// Explicit-edit UX: on modal (register) screens the row opens ONLY via its per-row "Edit" button
			// (injected by ensureRowEditButtons), never on a stray click anywhere in the row.
			if (crudHasModal && !$(e.target).closest('.js-edit-row').length) {
				return;
			}
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
				// A voided sale/purchase is read-only — the server rejects an edit. Block opening the form and
				// show a clear message instead of loading a record that can't be saved.
				if (isVoidedRow(getDocument(datatable.row(this).data()))) {
					var voidNoun = (tableV === 'Sell') ? 'invoice' : (tableV === 'Purchase' ? 'bill' : 'record');
					var voidMsg = 'This ' + voidNoun + ' is voided and cannot be edited.';
					if (typeof uiAlert === 'function') uiAlert({ title: 'Voided ' + voidNoun, message: voidMsg, tone: 'danger' });
					else showFormError(voidMsg);
					return;
				}
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
					// Make the form/formFields globals current for whichever editRecord() is in effect
					// (welfare overrides editRecord to read these globals). Guarantees the Edit button
					// reliably populates the modal form on EVERY dashboard, end-to-end.
					form = document.getElementsByClassName('form-horizontal')[tableV];
					if (form) { formFields = form.length - 2; }
					editRecord(doc);
					// Modal screens: pop the populated form in its modal (edit mode).
					if (crudHasModal && typeof openCrudModal === 'function') openCrudModal(tableV);
				}

				// updateReadOnly(false);
			}
		} );
	  });
	});

	// ── Explicit per-row Edit button (register/modal screens) ───────────────────────────────────
	// Replaces the old "click anywhere on the row to edit" with a discoverable Edit button. After every
	// DataTable draw, inject one Edit button per row into the select (checkbox) cell — but only for tables
	// whose entity has a modal form (#<Entity>Modal). Sell/Fc and report tables have no modal → unaffected.
	function ensureRowEditButtons(table) {
		if (!table) return;
		var id = table.id || '';                       // "table<Entity>"
		if (id.indexOf('table') !== 0) return;
		var entity = id.slice(5);
		if (!entity || !document.getElementById(entity + 'Modal')) return;   // modal screens only
		$(table).find('tbody tr').each(function () {
			var $cb = $(this).find("input[type='checkbox']").first();
			if (!$cb.length || $(this).find('.js-edit-row').length) return;  // needs a row id; add once per row
			// Wrap the checkbox and the button in one flex row. Appending them as loose siblings left them on
			// different baselines and wrapping apart on narrow screens; the wrapper keeps them aligned and
			// together as a single "row actions" control. Styling lives in /css/crud-modal.css.
			var $td = $cb.closest('td');
			var $btn = $('<button type="button" class="js-edit-row btn btn-xs btn-default" title="Edit record" aria-label="Edit record">'
				+ '<span class="glyphicon glyphicon-pencil"></span>'
				+ '<span class="row-actions__label">Edit</span></button>');
			var $wrap = $('<div class="row-actions">');
			// Move the checkbox into the wrapper (the SAME element — selectors elsewhere read :checked off it)
			// and put the wrapper where the checkbox was. Deliberately not $td.empty(): a cell may carry other
			// content on tables I haven't seen, and wiping it would be a silent regression.
			$td.addClass('row-actions-cell');   // tagged so the CSS needs no :has() (not safe on our browser floor)
			$wrap.insertBefore($cb);
			$wrap.append($cb).append($btn);
		});
	}
	window.ensureRowEditButtons = ensureRowEditButtons;
	// Register the injector as a GLOBAL DataTables default drawCallback: it becomes part of EVERY table's
	// config, so it runs after every draw (init, ajax load, sort, search, paging) of every table on every
	// dashboard — regardless of which module's loadDataTable created it, and it survives destroy()/recreate.
	if ($.fn && $.fn.dataTable) {
		$.extend(true, $.fn.dataTable.defaults, {
			drawCallback: function (settings) { ensureRowEditButtons(settings.nTable); }
		});
	}

	/**
	 * The ONE bulk-delete implementation (DRY). Two entry points confirm in their own way and then land here:
	 * the toolbar's #delete<Entity> button (generic uiConfirm) and crud-modal.js's #confirmDeleteModal (which
	 * lists the selected record names). Keeping the delete here is what stops the bulk path from asking twice —
	 * it used to click #delete<Entity>, whose handler then raised a second, native confirm().
	 */
	window.performBulkDelete = function (entity, ids) {
		// Most entities post to /delete<Entity>. Some do not: a catalog Product is DEACTIVATED, never
		// deleted, because a removed product still owns its SKU and is referenced by past sales. The
		// generic path used to post /deleteProduct regardless — an endpoint that does not exist — so bulk
		// delete on the Product screen 404'd. A module registers its own handler as window.bulkDelete<Entity>
		// and that wins; everything else keeps the convention.
		var override = window["bulkDelete" + entity];
		if (typeof override === "function") { override(ids); return; }
		$(document).callAjax("delete" + entity, { checked : ids });
	};

	$.fn.callAjax = function(method, data) {
		var dataSent = data;   // captured for the credit-limit re-submit below
		$.ajax({
			type : "POST",
			url : serverContext + method,
			dataType : "json",
// timeout : 100000,
			data : data,

			success : function(data) {
				hideWait();
				// B2B-P1 (#9): the server is holding this for a credit-limit decision (supplier side, on a
				// purchase). Nothing written, no stock in — so cancelling is free, and confirming re-submits
				// the same form data with the acknowledgement appended.
				if(data.status==="CONFIRM"){
					uiConfirm({
						title: t('ui.js.creditLimitTitle'),
						message: data.message,
						okText: t('ui.js.continueAnyway'),
						tone: 'danger'
					}).then(function (ok) {
						if (!ok) { return; }
						// `data` here is a URL-encoded string (populateFormData/$.param), so APPEND — assigning
						// a property to a string is a silent no-op, the same trap that once dropped productId.
						$(document).callAjax(method, dataSent + "&creditAcknowledged=true");
					});
					return false;
				}
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
					// P6: a module may own its post-save UI. This mirrors the window.bulkDelete<Entity>
					// convention above — the module registers an override and the generic path defers to it.
					// Returning TRUE means "I handled the reset, the modal and the grid"; anything else
					// (including a module that has no opinion today) keeps the register behaviour below.
					//
					// That behaviour — wipe the form, close the modal — is right for a REGISTER, where you
					// create one record and you are done. It is wrong for repetitive line entry against a
					// shared header (a purchase: one delivery, one vendor, one invoice, many items), which
					// is what this hook exists to let a screen opt out of.
					var afterSave = window["afterSave" + tableV];
					if (typeof afterSave === "function" && afterSave() === true) {
						if (typeof refreshBulkBar === 'function') refreshBulkBar(tableV);
						return false;
					}
					datatable.clear().draw();
					datatable.ajax.reload();
					resetForm();
					clearFormError();
					// Register-screen modal layer: close the form modal + clear the bulk-action bar after a save/delete.
					if ($('#' + tableV + 'Modal').length && typeof closeModal === 'function') closeModal(tableV + 'Modal');
					if (typeof refreshBulkBar === 'function') refreshBulkBar(tableV);
				}
				return false;
			}, fail: function(data, textStatus, errorThrown) {
				hideWait();
			showFormError('Network error. Please check your connection and try again.');
			}, error: function(data, textStatus, errorThrown) {
				hideWait();
				resetGlobalError();

				// An error handler must never throw. This one assumed every failure carried
				// {error, message} JSON with `message` holding a JSON-encoded validation array — so a plain
				// 404/500 (whose body is Spring's HTML error page, or JSON with no `message`) crashed on
				// `responseJSON.error.indexOf` and the REAL failure never reached the user. Read defensively
				// and fall back to reporting the HTTP status, which is always available.
				var body = data ? data.responseJSON : null;

				// A parsererror is USUALLY the login page arriving where JSON was expected — but a 500
				// serving Spring's HTML error page fails to parse too, and that is not a logout. Let the
				// shared helper inspect the body and decide; it still redirects on a real session loss.
				if (textStatus === "parsererror") {
					handleAjaxFailure(data, errorThrown, "form submit");
					return;
				}
				// An "InternalError" is a SERVER FAULT (500) — the one thing that is definitely NOT the
				// user's session being gone. Redirecting here meant every backend exception presented as
				// "you have been logged out", which is both wrong and the reason such faults went unreported.
				if (body && typeof body.error === "string" && body.error.indexOf("InternalError") > -1) {
					handleAjaxFailure(data, body.message || errorThrown, "server error");
					return;   // was missing — the handler used to redirect and then keep parsing
				}

				// The validation-array shape: message is a JSON string of {field, defaultMessage} entries.
				var errors = null;
				if (body && typeof body.message === "string") {
					try { errors = JSON.parse(body.message); } catch (e) { errors = null; }
				}

				if (Array.isArray(errors) && errors.length) {
					$.each(errors, function (index, item) {
						if (item && item.field) {
							$("[name=" + item.field + "]").addClass("alert-danger");
						}
						$("#globalError").show().append(escHtml((item && item.defaultMessage) || "") + "<br/>");
					});
					$('html, body').animate({ scrollTop: $('#globalError').offset().top }, 'slow');
					return;
				}

				// Anything else — 404, 500, an HTML error page, a proxy failure. Say what actually happened
				// instead of dying silently in the handler.
				var status = data && data.status ? data.status : 0;
				var detail = (body && (body.message || body.error))
					|| (status === 404 ? 'That action is not available on the server.'
					  : status === 403 ? 'You do not have permission to do that.'
					  : errorThrown || 'Unexpected error');
				showFormError('Request failed (' + (status || 'network') + '): ' + detail);
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

// bound once, but fires for ANY current/future reset button
$(document).on('click', '[id^="reset"]', function () {
    updateReadOnly(false);
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


// SF-3: one idempotency key per checkout attempt. Generated lazily, kept across retries (so a double-click /
// network retry sends the SAME key and the server dedups), and reset only after a successful sale.
function getSaleIdempotencyKey(){
	if(!window.saleIdempotencyKey){
		window.saleIdempotencyKey = (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
			: ('sale-' + Date.now() + '-' + Math.random().toString(36).slice(2));
	}
	return window.saleIdempotencyKey;
}

/**
 * B2B-P1 (#9): re-submit a sale the operator has just confirmed past a credit limit.
 *
 * Re-uses the SAME payload and the SAME idempotency key: the CONFIRM path wrote nothing, so there is no
 * first invoice for this to duplicate — and keeping the key is what still protects against a double-click
 * on the confirmation dialog itself.
 */
function resubmitAcknowledged(method, payload) {
	payload.creditAcknowledged = true;
	jsonPost(method, payload);
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
	      // SF-3: lock the submit button while in flight so a double-click can't fire a second sale.
	      beforeSend : function(){ $('#addSell').prop('disabled', true); },
	      complete   : function(){ $('#addSell').prop('disabled', false); },
	      success : function(data) {
			// B2B-P1 (#9): the server is holding the sale for a credit-limit decision. NOTHING has been
			// written and NO stock is reserved — so cancelling costs nothing, and confirming re-submits the
			// SAME payload (same idempotency key, which stays safe precisely because nothing was recorded).
			// The server, not this page, decides whether a confirmation is needed: our copy of the customer's
			// balance is as old as the dropdown, and another till may have sold to them since.
			if (data.status === "CONFIRM") {
				uiConfirm({
					title: t('ui.js.creditLimitTitle'),
					message: data.message,
					okText: t('ui.js.continueAnyway'),
					tone: 'danger'
				}).then(function (ok) {
					if (!ok) { return; }          // nothing happened, nothing to undo
					resubmitAcknowledged(method, printData);
				});
				return;
			}
			if(data.status!="SUCCESS"){
				showFormError(data.message || 'Sale could not be completed. Please check all fields and try again.');
				return;   // keep the idempotency key so a retry dedups
			}
			clearFormError();
			// SF-3: sale committed — retire this checkout's key so the NEXT sale gets a fresh one.
			if (method === 'addSell') { window.saleIdempotencyKey = null; }
			// slice 22: show the system-generated per-org invoice number returned by addSell
			if (data.object) {
				showSaleSuccess('Sale recorded — Invoice ' + data.object);
				// G6 (slice 38): auto-print the receipt for a new sale (hidden iframe — no popup block). Owner-
				// configurable (pos.receipt.autoPrint, default ON); when off, the cashier reprints from the sale's
				// Print button instead.
				if (method === 'addSell' && window.posAutoPrintReceipt !== false && typeof printReceipt === 'function') { printReceipt(data.object); }
				// P6 (slice 43): if this sale is dispensing a prescription, record the dispense against it.
				if (method === 'addSell' && window.dispensingPrescriptionId && typeof dispensePrescription === 'function') {
					dispensePrescription(data.object);
				}
				// E1 (slice 46) used to post /recordOrder from here, so a Store sale became an order only if the
				// browser was still around to say so — close the tab or lose the network and the sale survived
				// while its order silently did not. OMS O5e step 3 moved that server-side: SellController.addSell
				// creates the order from the invoice it just wrote (PosOrderRecorder), so there is nothing to do
				// here. Removed last, per §2.3 — until the server-side path was gated green, both writers ran.
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
        	handleAjaxFailure(data, errorThrown, "jsonPost");   // was: unconditional redirect to /login
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
						// Match on the option VALUE first, then fall back to its text.
						//
						// The text match is what the older selects rely on (their rows render a display
						// label, sometimes "Label ~ extra"). But a select whose row renders a CODE — an
						// enum like customerType, whose cell holds WALK_IN while the option reads
						// "Walk-in (retail)" — could never match by text, so editing silently reset it to
						// the first option. Checking the value first fixes that without changing what the
						// text-matched selects do.
						if($(this).val() === text || text === (($(this).text()).split(" ~ ")[0])) {
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

	}
	if (tableV=="Purchase") {
		// Quantity comes from the row's OWN quantity (it used to be sourced from the overloaded 'stock' field,
		// which is why "Stock In Hand" always equalled QTY). "Stock In Hand" is now the product's live on-hand.
		var q = doc.getElementById('purchaseQuantity');
		if (q) { $("#purchaseQuantity").val(q.textContent); }
		if (typeof refreshPurchaseOnHand === 'function') { refreshPurchaseOnHand(); }
	}

	// make readonly the key fields when user edit the records
	updateReadOnly(true);
}


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
	} else if (tableV == "Product") {
		$('#prodName').prop('disabled', flag);
	}

	$('#companyName').prop('disabled', flag);

}

function resetBSDD(id){
	
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

/* The duplicate handleKey that used to live here (keyCode 105/73) silently OVERRODE the one
 * above, so the only caller — <body onkeypress="handleKey(event,'click','sellItemDD')"> — never
 * fired on Enter. Removed: the definition above is the one every caller expects. */

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

// A voided sale/purchase renders a "VOID" badge in its row instead of edit actions. The server rejects an edit/void
// of a voided record; detect the badge so the UI can block opening the form and show a clear message instead.
function isVoidedRow(doc){
	if(!doc || !doc.querySelectorAll) return false;
	var labels = doc.querySelectorAll('.label');
	for(var i=0;i<labels.length;i++){ if((labels[i].textContent||'').trim().toUpperCase()==='VOID') return true; }
	return false;
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
