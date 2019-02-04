var buttonV = "Company";
//var searchV = "Company";
var deleteV = "Company";
var tableV = "Company";
var getAll = "Company";
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
/*
function validateForm(){
    formValidated = true;
    var forms = document.getElementsByClassName('form-horizontal');
    // Loop over them and prevent submission
    var validation = Array.prototype.filter.call(forms, function(form) {
        if (form.id === tableV && form.checkValidity() === false) {
          event.preventDefault();
          event.stopPropagation();
          var length = form.length;
          for(var i=0; i<length; i++){
        	  if(form[i].validity.valid)
        		  $("#"+form[i].id).removeClass("alert-danger");
        	  else
        		  $("#"+form[i].id).addClass("alert-danger");
          }
          formValidated = false;
        }
    });
}
*/
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
/*function switchInputs(val) {
	reset();
	
    buttonV = val;
	deleteV = val;
	tableV = val;
	getAll = val;
}*/

$(document).ready(function() {
/*
	datatable = $('#tableCompany').DataTable({
		"autoWidth": true,
		"columnDefs": [
			{"targets": [ 0 ],
		     "visible": false,
		     "searchable": false}
		]
	});	*/
	
	$switchInputs =function(val) {
		resetForm();
		
	    buttonV = val;
		deleteV = val;
		tableV = val;
		getAll = val;	

		//All button get initialized when user switch form
		$("#add"+buttonV).off().click(function() {
		    //If all form's required fields are filled
			validateForm();
		    if(formValidated){
				var formData = $('form').serialize();
				   /* 
				    * var formData = $("#"+val+" :input").filter(function(index, element) {
				        return $(element).val() != '';
				    })
				    .serialize();
					*/
					formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');
					console.log(" formData for "+val +" is =  "+formData);
					$(this).callAjax("add" + buttonV,formData);
		    }else{
		    	alert("Please make sure you have entered valid values");
		    	return false;
		    }
		});
/*
		$("#update"+buttonV).off().click(function() {
		    //If all form's required fields are filled
			validateForm();
		    if(formValidated){
				var formData = $('form').serialize();
				formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');
				console.log(" formData for "+val +" is =  "+formData);
				$(this).callAjax("update" + buttonV,formData);
		    }else{
		    	alert("Please make sure you have entered valid values");
		    	return false;
		    }
		});
*/
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
	
	/*	
	var tableClient = $('#tableCompany').DataTable({
		"autoWidth": true,
		"columnDefs": [
			{"targets": [ 0 ],
		     "visible": false,
		     "searchable": false}
		],
		"ajax": {
			"url": serverContext+"getAllCompanies",
			"type": "GET",
			"success" : function(data) {
				var collections = data.collection;
				$.each(collections, function(ind, obj) {
					tableClient.row.add([
						obj.cliNumber,
						"<input type='checkbox' value='"+obj.id+"' id=''>",
						obj.cliName,
						obj.cliLastname,
						obj.cliDatebirth,
						obj.cliRegister
					]).draw();
				});
			}
		},
	});	
	tableClient.destroy();
*/	
	 
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
/*
	function parseHTML(markup) {
	    if (markup.toLowerCase().trim().indexOf('<!doctype') === 0) {
	        var doc = document.implementation.createHTMLDocument("");
	        doc.documentElement.innerHTML = markup;
	        return doc;
	    } else if ('content' in document.createElement('td')) {
	       // Template tag exists!
	       var el = document.createElement('td');
	       el.innerHTML = markup;
	       return el.content;
	    } else {
	       // Template tag doesn't exist!
	       var docfrag = document.createDocumentFragment();
	       var el = document.createElement('body');
	       el.innerHTML = markup;
	       for (i = 0; 0 < el.childNodes.length;) {
	           docfrag.appendChild(el.childNodes[i]);
	           console.log(el.childNodes[i])
	       }
	       return docfrag;
	    }
	}
	*/
	$("#pickerDateBirth").datetimepicker({
		format : 'DD/MM/YYYY'
	});

	$(window).load(function() {

	});

	/*
	 * $("#buttonSearch").click(function(){
	 * datatable.clear().draw(); datatable.ajax.reload();
	 * 
	 * });
	 */
/*	$("#add"+buttonV).click(
			function() {
				console.log("insertV : "+buttonV);
				$(this).callAjax("add" + buttonV,
						$('form').serialize());

				$(".form-control").val("");

			});
*/	/*
	 * $("#buttonInsert").click(function(){
	 * $(this).callAjax("insertClient", "");
	 * 
	 * $(".form-control").val("");
	 * 
	 * });
	 */
/*	
  $("#delete"+deleteV).click(function() {
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
*/
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
/*
	if(tableV=="Company"){
		$("#companyId").val(doc.getElementById("companyId").innerHTML);
		$("#companyName").val(doc.getElementById("companyName").innerHTML);
		$("#nameSub").val(doc.getElementById("nameSub").innerHTML);
		$("#brands").val(doc.getElementById("brands").innerHTML);
		$("#companyPhone").val(doc.getElementById("companyPhone").innerHTML);
		$("#companyMobile").val(doc.getElementById("companyMobile").innerHTML);
		$("#companyAddress").val(doc.getElementById("companyAddress").innerHTML);
		$("#companyDescription").val(doc.getElementById("companyDescription").innerHTML);
	}*/
	
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
			"url" : serverContext + "getUser" + getAll,
			"type" : "GET",
			"success" : function(data) {
				var collections = data.collection;
				console.log("getUser : "+getAll+" collections : "+collections);
				var arr = [" No Data Found "];
				if (getAll === "Company") {
					$.each(collections, function(ind, obj) {
						arr = [
								"<div id=companyId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id=''>",
								"<div id=companyName>"+obj.name+"</div>", "<div id=nameSub>"+obj.nameSub+"</div>", "<div id=brands>"+obj.brands+"</div>", "<div id=companyPhone>"+obj.phone+"</div>",
								"<div id=companyMobile>"+obj.mobile+"</div>", "<div id=companyAddress>"+obj.address+"</div>", "<div id=companyDescription>"+obj.description+"</div>", obj.dated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Vender") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=venderId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='abc'>",
							"<div id=venderName>"+obj.name+"</div>", "<div id=venderCompany>"+obj.companyName+"</div>", "<div id=venderEmail>"+obj.email+"</div>",
							"<div id=venderPhone>"+obj.phone+"</div>", "<div id=venderMobile>"+obj.mobile+"</div>",
							"<div id=venderAddress>"+obj.address+"</div>", "<div id=venderDescription>"+obj.description+"</div>",obj.dated
							];
						datatable.row.add(arr).draw();
					});
					loadUserCompanies();
				} else if (getAll === "ItemType") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=itemTypeId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id=''>",
							"<div id=itemTypeName>"+obj.name+"</div>","<div id=itemTypeDescription>"+obj.description+"</div>",obj.dated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "ItemUnit") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=itemUnitId>"+obj.id+"</div>", "<input type='checkbox' value='"+ obj.id+ "' id=''>",
							"<div id=itemUnitName>"+obj.name+"</div>", "<div id=itemUnitDescription>"+obj.description+"</div>",obj.dated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Item") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=itemId>"+obj.id+"</div>", "<input type='checkbox' value='"+ obj.id+ "' id=''>",
							"<div id=itemId2>"+obj.itemId+"</div>", "<div id=itemName>"+obj.name+"</div>", "<div id=itemType>"+obj.itemType+"</div>", 
							"<div id=itemUnit>"+obj.itemUnit+"</div>", "<div id=purchaseAmount>"+obj.purchaseAmount+"</div>", "<div id=sellAmount>"+obj.sellAmount+"</div>",
							"<div id=discount>"+obj.discount+"</div>", "<div id=net>"+obj.net+"</div>", "<div id=itemCompany>"+obj.companyName+"</div>", 
							"<div id=itemVender>"+obj.vender+"</div>",obj.dated
							];
						datatable.row.add(arr).draw();
					});
					//load dropdowns for user
					loadUserCompanies();
					loadUserVenders();
					laodUserItemTypes();
					loadUserItemUnits();
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
}

function loadUserCompanies() {	
	$("#company").empty();
    $("#company").append("<option value = ''> Please wait....  </option>");
    
//	$("#company").append("/resources/img/waiting-animation.gif");
    $.get(serverContext+ "getUserCompanies",function(data){
    	if(tableV==="Item")
    		$("#itemCompany").empty().append(data);
    	else
    		$("#venderCompany").empty().append(data);
    })
	.fail(function(data) {
		if(tableV==="Item")
			$("#itemCompany").empty().append("<option value = ''> System error  </option>");
		else
			$("#venderCompany").empty().append("<option value = ''> System error  </option>");
	});
}

function loadUserVenders() {	
	$("#vender").empty();
    $("#vender").append("<option value = ''> Please wait....  </option>");
    
//	$("#company").append("/resources/img/waiting-animation.gif");
    $.get(serverContext+ "getUserVenders",function(data){
    	$("#itemVender").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#itemVender").empty().append("<option value = ''> System error  </option>");
	});
}
function laodUserItemTypes() {	
	$("#itemType").empty().append("<option value = ''> Please wait....  </option>");
    $.get(serverContext+ "getUserItemTypes",function(data){
    	$("#itemType").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#itemType").empty().append("<option value = ''> System error  </option>");
	});
}
function loadUserItemUnits() {	
	$("#itemUnit").empty().append("<option value = ''> Please wait....  </option>");
    $.get(serverContext+ "getUserItemUnits",function(data){
    	$("#itemUnit").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#itemUnit").empty().append("<option value = ''> System error  </option>");
	});
}

function calculateNet(val){
	$('#net').removeClass("alert-danger");
	$("#net").val($("#sellAmount").val() - $("#purchaseAmount").val() - $("#discount").val());
	if(($("#net").val()*1) <0){
		$("#net").val(0.0);
		$('#net').addClass("alert-danger"); 
	}
}
