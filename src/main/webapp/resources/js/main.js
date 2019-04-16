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
	//Reset error Form's error classes and values
	form = document.getElementsByClassName('form-horizontal')[tableV];
	if(form){
		formFields = form.length-2;//-2 mean we don't need to loop over buttons (Add & Delete)
		for(var i=0; i<formFields; i++){
			//$("#"+form[i].id).removeClass("alert-danger");
		}
		$(".form-control").val("");
	}
}

function validateForm(){
    formValidated = true;
    var form = document.getElementsByClassName('form-horizontal')[tableV];
    if(!form)
    	return alert("Not valid form!");
    formFields = form.length-2;
    if (form.checkValidity() === false) {
      event.preventDefault();
      event.stopPropagation();
      // Loop over them and prevent submission
      for(var i=0; i<formFields; i++){
    	  if(form[i].validity.valid)
    		  document.getElementById(form[i].id).style.borderColor = "";
    		 // console.log(form[i].id+' valid')
    		  //$("#"+form[i].id).removeClass("alert-danger");
    	  else
    		  document.getElementById(form[i].id).style.borderColor = "red";
//    		  console.log(form[i].id+' invalid')
    		  //$("#"+form[i].id).addClass("alert-danger");
      }
      formValidated = false;
    }
}

$(document).ready(function() {
		
	$(".datePicker").datetimepicker({
		useCurrent: false,
		format : 'DD-MM-YYYY',
		showTodayButton: true,
		showClear:true,
		showClose:true
	});
	
    $(".datetimepicker").datetimepicker({
		format : 'DD-MM-YYYY HH:mm:ss'
	});
    
    $('input.timepicker').timepicker({ 
    	timeFormat: 'HH:mm',
        defaultTime: '8',
        dynamic: false,
        dropdown: true,
        scrollbar: true
    });
	
    $(".onChangeSelect").change(function(){
    	var label = $(this).text();
    	var value = $(this).val();
    	
   		populateData(label,value);
    });
    
	$switchInputs =function(val) {
	    
		buttonV = val;
		deleteV = val;
		tableV = val;
		getAll = val;	
		
		resetForm();

		//All button get initialized when user switch form
		$("#find"+buttonV).off().click(function() {
			if(!$("#input"+buttonV).val())
				return alert("Please enter valid input. ");
			findBy("find" + buttonV,"input="+$("#input"+buttonV).val());
		});

		//All button get initialized when user switch form
		$("#add"+buttonV).off().click(function() {
		    //If all form's required fields are filled
			if(buttonV=="Sell"){
				if(data && data.length>0){
					jsonPost("addSelling",data);
			    }else{
			    	alert("Please make sure you have entered valid values");
			    	return false;
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

		//All button get initialized when user switch form
		$("#revert"+buttonV).off().click(function() {
		    //If all form's required fields are filled
			validateForm();
		    if(formValidated){
//				var formArr = $('form'). serializeArray();
//				jQuery.each(formArr , function(i, field) {
//				  formArr[i].value = $.trim(field.value);
//				});
//				var serializedForm = $.param(formArr);
//				formData = serializedForm.replace(/[^&]+=\.?(?:&|$)/g, '');
				$(this).callAjax("revert" + buttonV,populateFormData());
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

		//All button get initialized when user switch form
		$("#send"+buttonV).off().click(function() {
		    //If all form's required fields are filled
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
	  	
	  	//having below block on every switch to get it work
		//Edit table click on row
		$("#table" + tableV).on( 'click', 'tr', function () {
			var html = datatable.row(this).data();//.selector.rows.innerHTML;
			var doc = new DOMParser().parseFromString(html, "text/html");
			
			resetForm();
			if(tableV==="Fc"){
				var ids = $("#table"+ tableV+ " input[type='checkbox']:checkbox:checked").map(function() {
					return this.value;
				}).get().join(",");
				if(!ids){
					removeTableBody();
					alert("Edit/Update is not allowed, You can delete and submit new one only");
				}
			}else{
				editRecord(doc);
			}
		} );
	  });
	});

	$.fn.callAjax = function(method, data) {
		$.ajax({
			type : "POST",
			url : serverContext + method,
			dataType : "json",
//			timeout : 100000,
			data : data,

			success : function(data) {
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
				alert("There is some problem in the request "+errorThrown);
			}, error: function(data, textStatus, errorThrown) {
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
			alert("Please recheck inputs or contact with the system administrator.");
		});
		edit = false;//when add/update & delete done
	}	
});

function populateFormData(){
	var myForm = document.getElementById(tableV);
    var formData = new FormData(myForm),
    obj = {};
    for (var entry of formData.entries()){
    	obj[entry[0]] = $.trim(entry[1]);
    }
	return $.param(obj);
}

function jsonPost(method,data) {	
	$.ajax({
	      type : "POST",
	      contentType : "application/json",
	      url : serverContext + method,
	      data : JSON.stringify(data),
	      dataType : 'json',			
	      success : function(data) {
			if(data.status!="SUCCESS"){
				alert("Insertion error");
				return false;
			}
			loadDataTable();
		}, fail: function(data, textStatus, errorThrown) {
			alert("There is some problem in the request "+errorThrown);
		}, error: function(data, textStatus, errorThrown) {
			resetGlobalError();
        	window.location.href = serverContext + "login?message=" + errorThrown;			
       	}
	}).fail(function(data) {
		alert("Please recheck inputs or contact with the system administrator.");
	});
	edit = false;//when add/update & delete done
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
			var text = doc.getElementById(form[i].id).textContent;
			if(form[i].tagName=="SELECT"){
				var labels = text.split(",");
				labels.forEach(function(entry) {
					$("#"+form[i].id+" option").each(function(i) {
						if(text.indexOf($(this).text()) > -1) {
							$(this).prop('selected', true);
						}else{
							$(this).prop('selected', false);
						}                      
					});
				});
			}else{
				$("#"+form[i].id).val(text);
			}
			//Handled bootstrap drop down 
			if(form[i].className.indexOf("selectpicker")>-1){
				$( "#"+form[i].id+" :selected" ).text(text);
				$("#"+form[i].id).selectpicker('refresh');
			}
		}
	}
}

function resetBSDD(id){
	edit = false;//when reset boot strap drill down
	$("#"+id).val('default').selectpicker("refresh");
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

function getMonth(){
	var d = new Date();
	return month[d.getMonth()];
}

function getMonthYear(d){
	return month[d.getMonth()]+""+d.getFullYear();
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
    for (var entry of formData.entries()){
    	obj[entry[0]] = $.trim(entry[1]);
    }
    return obj;
}