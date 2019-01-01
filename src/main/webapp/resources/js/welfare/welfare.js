var buttonV = "Donation";
//var searchV = "Donation";
var deleteV = "Donation";
var tableV = "Donation";
var getAll = "Donation";
var datatable=null;
var formValidated = true;
var form=null;
var formFields = 0;

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
    formFields = form.length-2;//2 is the number of button (add & delete)
    if (form.checkValidity() === false) {
      event.preventDefault();
      event.stopPropagation();
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
		    //If all form is filled correctly
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


	$("#pickerDateBirth").datetimepicker({
		format : 'DD/MM/YYYY'
	});

	$(window).load(function() {

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
			},
			 error: function(data, textStatus, errorThrown) {
		        
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
	            	}
	            	else {
	            		$("#globalError").show().append(item.defaultMessage+"<br/>");
	            	}
	         	});
            }
		});
	}

});

const capitalize = (s) => {
	if (typeof s !== 'string') return ''
  		return s.charAt(0).toUpperCase() + s.slice(1)
}

function editRecord(doc){
	for(var i=0; i<(formFields); i++){
		if(doc.getElementById(form[i].id)!=null)
			$("#"+form[i].id).val(doc.getElementById(form[i].id).textContent);
	}
}

function loadDataTable(){
	//check if data table exist destroy it
	if (datatable!=null){
		datatable.destroy();
		datatable = null;
	}
	datatable = $("#table" + tableV).DataTable({
		"autoWidth" : true,
		"columnDefs" : [ {
			"targets" : [ 0 ],
			"visible" : true,
			"searchable" : true,
			"deferRender": true
		} ],
		"ajax" : {
			"url" : serverContext + "getAll" + getAll,
			"type" : "GET",
			"success" : function(data) {
				var collections = data.collection;
				console.log("getAll : "+getAll+" collections : "+collections);
				var arr = [" No Data Found "];
				if (getAll === "Donation") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<input type='checkbox' value='"+ obj.id+ "' id='abc'>","<div id=donations>"+obj.donatorId+"</div>", 
							"<div id=donationAmount>"+obj.amount+"</div>", "<div id=donationReceivedBy>"+obj.receivedBy+"</div>",
							 obj.dated
							];
						datatable.row.add(arr).draw();
					});
					getAllDonators();
				} else if (getAll === "Donator") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=donatorId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id=''>",
							"<div id=donatorName>"+obj.name+"</div>", "<div id=donatorFName>"+obj.fName+"</div>",
							"<div id=donatorShowMe>"+obj.showMe+"</div>","<div id=donatorMobile>"+obj.mobile+"</div>", 
							"<div id=donatorAddress>"+obj.address+"</div>", obj.dated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Donations") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=donationId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id=''>",
							"<div id=donationName>"+obj.name+"</div>", "<div id=donationFName>"+obj.fName+"</div>",
							"<div id=donationMobile>"+obj.mobile+"</div>", "<div id=donationAddress>"+obj.address+"</div>", 
							"<div id=donationAmount>"+obj.amount+"</div>", "<div id=donationReceivedBy>"+obj.receivedBy+"</div>",
							obj.dated
							];
						datatable.row.add(arr).draw();
					});
				}
			},
			 error: function(jqXHR, textStatus, errorThrown) {
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
}

function getAllDonators() {	
	$("#donations").empty();
    $("#donations").append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getAllDonators",function(data){
    	$("#donations").empty().append(data);
    })
	.fail(function(data) {
		$("#donations").empty().append("<option value = ''> System error  </option>");
	});
}

