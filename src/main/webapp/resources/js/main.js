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

function resetForm(){
	// Reset error Form's error classes and values
	form = document.getElementsByClassName('form-horizontal')[tableV];
	if(form){
		$(".resetForm").click();
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
	    formFields = form.length-2;
		// Loop over them and prevent submission
		for(var i=0; i<formFields; i++){
			if(form[i].id && form[i].validity.valid){
				document.getElementById(form[i].id).style.borderColor = "";
			}else if(form[i].id){
				document.getElementById(form[i].id).style.borderColor = "red";
				document.getElementById(form[i].id).focus();
			}
		}
		formValidated = false;
    }
    formFields = form.length-2;
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

	$(".glyphicon-dashboard").click(function (){
		$('.formDiv').hide();
		$("#DashboardDiv").show();
		getDashboardData();
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

/*	$(".datetimepicker").datetimepicker({
		useCurrent: true,
		format : 'DD-MM-YYYY HH:mm:ss',
		showTodayButton: true,
		showClear:true,
		showClose:true
	});*/
    
	$(".datePicker").datetimepicker({
		useCurrent: true,
		format : 'DD-MM-YYYY',
		showTodayButton: true,
		showClear:true,
		showClose:true
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
    	var label = $(this).text();
    	var value = $(this).val();
    	if(tableV=="FV"){
//    		loadFVIBSDD(label,value); 
//    		loadBSDD("getUser"+lable.trim(),"fviDD");
    	}else if(tableV=="Purchase" || tableV =="Sell"){
    		loadStock(label,value);  
    		loadBSDD("getBatchesByItem?itemId="+value,tableV.toLowerCase()+'BatchDD');    		
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
			if(!$("#input"+buttonV).val())
				return alert("Please enter valid input. ");
			findBy("find" + buttonV,"input="+$("#input"+buttonV).val());
		});

		// All button get initialized when user switch form
		$("#add"+buttonV).off().click(function() {
		    // If all form's required fields are filled
			if(buttonV=="Sell"){
	    		document.getElementById("sellRec").style.borderColor = "";
				if(data && data.length>0 && $("#sellRec").val()*ONE>0){
					jsonPost("addSell",data);
//					$(this).callAjax("add" + buttonV,populateFormData());
			    }else{
			    	alert("Please make sure you have entered valid values");
			    	if($("#sellRec").val()*ONE<=0){
			    		document.getElementById("sellRec").style.borderColor = "red";
			    		document.getElementById("sellRec").focus();
			    	}
			    }
			}else{	
				validateForm();
			    if(formValidated){
					$(this).callAjax("add" + buttonV,populateFormData());
			    }else{
			    	alert("Please make sure you have entered valid values");
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
		    	alert("Please make sure you have entered valid values");
		    	return false;
		    }
		});

		$("#delete"+deleteV).off().click(function() {
			var ids = $("#table"+ tableV+ " input[type='checkbox']:checkbox:checked").map(function() {
				return this.value;
			}).get().join(",");
			
			if (ids == null || ids == "") {
				alert("Please select at least one record to delete");
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
		    	alert("Please make sure you have entered valid values");
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
					alert("Edit/Update is not allowed, You can delete and submit new one only");
				}
			}else{
				if(tableV!="Sell"){					
					var html = datatable.row(this).data();// .selector.rows.innerHTML;
					var doc = getDocument(html);
					editRecord(doc);
				}
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
					alert("Already exist");
					return false;
				}else if(data.status==="ERROR"){
					return alert(data.message);
				}
				if(method!=="sendAlerts"){
					datatable.clear().draw();
					datatable.ajax.reload();
					resetForm();
					$("#globalError").empty();
				}
				return false;
			}, fail: function(data, textStatus, errorThrown) {
				hideWait();
				alert("There is some problem in the request "+errorThrown);
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
	            		$("#globalError").show().append(item.defaultMessage+"<br/>");
	            		$('html, body').animate({ scrollTop: $('#globalError').offset().top }, 'slow');
	            	}
	            	else {
	            		$("#globalError").show().append(item.defaultMessage+"<br/>");
	            		$('html, body').animate({ scrollTop: $('#globalError').offset().top }, 'slow');
	            	}
	         	});
            }
		}).fail(function(data) {
			hideWait();
			alert("Please recheck inputs or contact with the system administrator.");
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
			// Handled bootstrap drop down
// if(form[i].className.indexOf("selectpicker")>-1){
// $( "#"+form[i].id+" :selected" ).text(text);
// $("#"+form[i].id).selectpicker('refresh');
// }
		}
	}
	
// var myForm = document.getElementById(tableV);
// var formData = new FormData(myForm),
// obj = {};
// for (var entry of formData.entries()){
// //obj[entry[0]] = $.trim(entry[1]);
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
				alert("Insertion error");
			}
/*			if($("#sellP")[0].checked){
				// pGarmtsInv(printData);
		    	var mylink = document.getElementById("MyLink");
		    	mylink.setAttribute("href", "../");
		        mylink.setAttribute("href", ".."+serverContext+"reports/createdocument.docx");
		        mylink.click();
			}
*/			loadDataTable();
			resetCart();
		}, fail: function(data, textStatus, errorThrown) {
			alert("There is some problem in the request "+errorThrown);
		}, error: function(data, textStatus, errorThrown) {
			resetGlobalError();
        	window.location.href = serverContext + "login?message=" + errorThrown;			
       	}
	}).fail(function(data) {
		alert("Please recheck inputs or contact with the system administrator.");
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

function editRecord(doc){
	edit = true;
	for(var i=0; i<(formFields); i++){
		if(doc.getElementById(form[i].id)){
			if(doc.getElementById(form[i].id).type == 'checkbox'){
				$("#"+form[i].id).val(doc.getElementById(form[i].id).value*ONE);
				continue;
			}
			var text = doc.getElementById(form[i].id).textContent;
			if(form[i].tagName=="SELECT"){
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
			if(form[i].className.indexOf("selectpicker")>-1){
				$( "#"+form[i].id+" :selected" ).text(text);
				$("#"+form[i].id).selectpicker('refresh');
			}
		}
	}
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
    stockDTO = {};
    for (var entry of formData.entries()){
    	var key = entry[0];
    	var val = entry[1];
    	if(key && key.indexOf(".")>0){
    		var mainKey = key.split('.')[0];
    		var keyVal = key.split('.')[1];
    		stockDTO[keyVal] = $.trim(val);
    	}else{
        	obj[key] = $.trim(val);
    	}
    }
    if(mainKey)
    	obj[mainKey] = stockDTO;

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
      alert("Invalid file selected, valid file is of " +
               validExts.toString() + " type");
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