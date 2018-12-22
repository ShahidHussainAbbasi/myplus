var buttonV = "Company";
//var searchV = "Company";
var deleteV = "Company";
var tableV = "Company";
var getAll = "Company";
var datatable=null;


$(document).ready(function() {

/*	datatable = $("#tableCompany").DataTable(
			{
				"autoWidth" : true,
				"columnDefs" : [ {
					"targets" : [ 0 ],
					"visible" : true,
					"searchable" : true
				}]});*/
	
	$switchInputs =function(val) {
		buttonV = val;
//		searchV = val;
		deleteV = val;
		tableV = val;
		getAll = val;
		
		$("#add"+buttonV).off().click(
			function() {
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
			$(".form-control").val("");
			
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
	
	}
	
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
			$("[name="+ option + "Div").show()
			$switchInputs(option);
			//check if data table exist destroy it
			if (datatable!=null){
				datatable.destroy();
				datatable = null;
			}
			// Activated the table
			datatable = $("#table" + tableV).DataTable(
			{
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
										obj.id,"<input type='checkbox' value='"+ obj.id+ "' id=''>",
										obj.name, obj.nameSub,obj.brands, obj.phone,
										obj.mobile,obj.address,obj.description,obj.dated
									];
								datatable.row.add(arr).draw();
							});
						} else if (getAll === "Vender") {
							$.each(collections, function(ind, obj) {
								arr = [
										obj.id,"<input type='checkbox' value='"+ obj.id+ "' id=''>",
										obj.name, obj.email,obj.phone, obj.mobile,obj.address,obj.description,obj.dated
									];
								datatable.row.add(arr).draw();
							});
						} else if (getAll === "ItemType") {
							$.each(collections, function(ind, obj) {
								arr = [
										obj.id,"<input type='checkbox' value='"+ obj.id+ "' id=''>",
										obj.name,obj.description,obj.dated
									];
								datatable.row.add(arr).draw();
							});
						} else if (getAll === "ItemUnit") {
							$.each(collections, function(ind, obj) {
								arr = [
										obj.id,"<input type='checkbox' value='"+ obj.id+ "' id=''>",
										obj.name,obj.description,obj.dated
									];
								datatable.row.add(arr).draw();
							});
						} else if (getAll === "Item") {
							$.each(collections, function(ind, obj) {
								arr = [
										obj.id,"<input type='checkbox' value='"+ obj.id+ "' id=''>",
										obj.itemId,obj.name,obj.itemType,obj.itemUnit, obj.description,obj.company,obj.vender,obj.dated
									];
								datatable.row.add(arr).draw();
							});
							//load dropdowns for user
							getUserCompanies();
							getUserVenders();
							getUserItemTypes();
							getUserItemUnits();
						}
					},
					error : function(e) {
						alert(data.responseJSON.message);
				    	
				        if(data.responseJSON.error.iRndexOf("MailError") > -1)
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
				        	var errors = $.parseJSON(data.responseJSON.message);
				            $.each( errors, function( index,item ){
				            	if (item.field){
				            		$("#"+item.field+"Error").show().append(item.defaultMessage+"<br/>");
				            	}
				            	else {
				            		$("#globalError").show().append(item.defaultMessage+"<br/>");
				            	}
				               
				            });
				        }						
						alert("ERROR: ", e);
					}
				}
			});
		});
	});


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
				datatable.clear().draw();
				datatable.ajax.reload();
				return false;
			},
			error : function(e) {
				alert("ERROR: ", e);
			}
		});
	}

});

function getUserCompanies() {	
	$("#company").empty();
    $("#company").append("<option value = ''> Please wait....  </option>");
    
//	$("#company").append("/resources/img/waiting-animation.gif");
    $.get(serverContext+ "getUserCompanies",function(data){
    	$("#company").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#company").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserVenders() {	
	$("#vender").empty();
    $("#vender").append("<option value = ''> Please wait....  </option>");
    
//	$("#company").append("/resources/img/waiting-animation.gif");
    $.get(serverContext+ "getUserVenders",function(data){
    	$("#vender").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#vender").empty().append("<option value = ''> System error  </option>");
	});
}
function getUserItemTypes() {	
	$("#itemType").empty().append("<option value = ''> Please wait....  </option>");
    $.get(serverContext+ "getUserItemTypes",function(data){
    	$("#itemType").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#itemType").empty().append("<option value = ''> System error  </option>");
	});
}
function getUserItemUnits() {	
	$("#itemUnit").empty().append("<option value = ''> Please wait....  </option>");
    $.get(serverContext+ "getUserItemUnits",function(data){
    	$("#itemUnit").empty().append(data);
    })
	.fail(function(data) {
		alert(xhr.responseText);
		$("#itemUnit").empty().append("<option value = ''> System error  </option>");
	});
}

