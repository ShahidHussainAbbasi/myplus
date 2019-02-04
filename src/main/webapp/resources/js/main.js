var buttonV = "Company";
var deleteV = "Company";
var tableV = "Company";
var getAll = "Company";
var datatable=null;
var formValidated = true;
var form=null;
var formFields = 0;
var reload="";

function resetGlobalError(){
    $(".alert").html("").hide();
    $(".error-list").html("");	
}

function resetForm(){
	//Reset error Form's error classes and values
	form = document.getElementsByClassName('form-horizontal')[tableV];
	formFields = form.length-2;//-2 mean we don't need to loop over buttons (Add & Delete)
	for(var i=0; i<formFields; i++){
		$("#"+form[i].id).removeClass("alert-danger");
	}
	$(".form-control").val("");
}

function validateForm(){
    formValidated = true;
    var form = document.getElementsByClassName('form-horizontal')[tableV];
    formFields = form.length-2;
    if (form.checkValidity() === false) {
      event.preventDefault();
      event.stopPropagation();
    //  var length = form.length;
      // Loop over them and prevent submission
      for(var i=0; i<formFields; i++){
    	  if(form[i].validity.valid)
    		  $("#"+form[i].id).removeClass("alert-danger");
    	  else
    		  $("#"+form[i].id).addClass("alert-danger");
      }
      formValidated = false;
    }
}

$(document).ready(function() {
	
	$switchInputs =function(val) {
	    
		buttonV = val;
		deleteV = val;
		tableV = val;
		getAll = val;	
		
		resetForm();

		//All button get initialized when user switch form
		$("#add"+buttonV).off().click(function() {
		    //If all form's required fields are filled
			validateForm();
		    if(formValidated){
				var formData = $('form').serialize();
					formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');
					console.log(" formData for "+val +" is =  "+formData);
					$(this).callAjax("add" + buttonV,formData);
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
	};

	$(function() {
	  $('.dropdown').change(function(){
	    $('.formDiv').hide();
	    $('#' + $(this).val()).show();
	    var tab = ($(this).val()).replace("Div","");
	    console.log(444);
	  	if(tab){
			$switchInputs(capitalize(tab));
			// Activated data table
			loadDataTable();
	  	}
	  });
	});

	//Edit table click on row
	$("#table" + tableV).on( 'click', 'tbody tr', function () {
		console.log(datatable.row( this ));
		var html = datatable.row(this).selector.rows.innerHTML;
		var doc = new DOMParser().parseFromString(html, "text/html");
		
		resetForm();
		editRecord(doc);
	} );
/*	
	// It will show hide
	$(function() {
		var options = $("#registrationType > option").length;
		$("#registrationType").change(function() {
			var option = this.value;
			if(!option)
				return false;
			
			for (var i = 0; i < options; i++ ) {
				$("#" + i + "Div").hide();
			}
			$("[name="+ option + "Div").show();
			
			$switchInputs(option);
			// Activated data table
			loadDataTable();
			//Edit table click on row
			$("#table" + option).on( 'click', 'tbody tr', function () {
				console.log(datatable.row( this ));
				var html = datatable.row(this).selector.rows.innerHTML;
				var doc = new DOMParser().parseFromString(html, "text/html");
				
				resetForm();
				editRecord(doc);
			} );
		});
	});
*/
	$("#datePicker").datetimepicker({
		format : 'DD/MM/YYYY'
	});

	$.fn.callAjax = function(method, data) {
		$.ajax({
			type : "POST",
			url : serverContext + method,
			dataType : "json",
			timeout : 100000,
			data : data,

			success : function(data) {
				if(data.status==="FOUND"){
					alert(data.message);
					return false;
				}
				datatable.clear().draw();
				datatable.ajax.reload();
				resetForm();
				return false;
			}, fail: function(data, textStatus, errorThrown) {
				console.log("Fail block");
				alert("Fail block ");
			}, error: function(data, textStatus, errorThrown) {
/*		        if(data.responseJSON.error.indexOf("MailError") > -1)
		        {
		            window.location.href = serverContext + "emailError.html";
		        }
		        else if(data.responseJSON.error == "UserAlreadyExist"){
		            $("#emailError").show().html(data.responseJSON.message);
		        }
		        else if(data.responseJSON.error.indexOf("InternalError") > -1){
		            window.location.href = serverContext + "login?message=" + data.responseJSON.message;
		        }
		        else
		        {
*/		        
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
	            		$("#"+tableV.toLowerCase()+capitalize(item.field)).addClass("alert-danger");
/*	            	if (item.field){
	            		$("#"+item.field).addClass("alert-danger");
*/	            		//$("#"+item.field+"Error").show().append(item.defaultMessage+"<br/>");
	            	}
	            	else {
	            		$("#globalError").show().append(item.defaultMessage+"<br/>");
	            	}
	         	});
            }
		}).fail(function(data) {
			console.log("Fail block 2");
			alert("Fail block 2");
		});
	}

});

const capitalize = (s) => {
  if (typeof s !== 'string') return ''
  return s.charAt(0).toUpperCase() + s.slice(1)
}

function editRecord(doc){
	for(var i=0; i<(formFields); i++){
		if(doc.getElementById(form[i].id)){
			console.log("form id");
			var text = doc.getElementById(form[i].id).textContent;
			if(form[i].tagName=="SELECT"){
				var labels = text.split(",");
				labels.forEach(function(entry) {
					$("#"+form[i].id+" option").each(function(i) {
						if(text.indexOf($(this).text()) > -1) {
							$(this).prop('selected', true);
							//document.getElementById(form[i].id).selectedIndex = i;
						}else{
							$(this).prop('selected', false);
						}                      
					});
				});
			}else{
				$("#"+form[i].id).val(text);
			}
		}
	}
}

