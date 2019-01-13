var buttonV = "Donation";
//var searchV = "Donation";
var deleteV = "Donation";
var tableV = "Donation";
var getAll = "Donation";
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
				if(data.status==="FOUND" || data.status==="ERROR"){
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

const nonCapitalize = (s) => {
	if (typeof s !== 'string') return ''
  		return s.charAt(0).toLowerCase() + s.slice(1)
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

function loadDataTable(){
	//check if data table exist destroy it
	if (datatable!=null){
		datatable.destroy();
		datatable = null;
	}
	console.log(1);
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
				if(reload != tableV){
					//don't want to load ever DD for every row update on table
					var table = tableV.toLowerCase();
					getUserOwners(table);
					getUserSchools(table);
					getUserGrades(table);
					getSubjects(table);

					reload=tableV;
				}
				
				var collections = data.collection;
				console.log("getAll : "+getAll+" collections : "+collections);
				var arr = [" No Data Found "];
				if (getAll === "Owner") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=ownerId>"+obj.id+"</div>",
							"<input type='checkbox' value='"+ obj.id+ "' id='abc'>","<div id=ownerName>"+obj.name+"</div>", 
							"<div id=ownerEmail>"+obj.email+"</div>", "<div id=ownerMobile>"+obj.mobile+"</div>",
							"<div id=ownerAddress>"+obj.address+"</div>", obj.dated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "School") {
					//check if this user have already registered main branch display it
					getMainBranchName();
					//getUserOwners();
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=schoolId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=schoolOwnerDD>"+obj.ownerNames+"</div>","<div id=schoolName>"+obj.name+"</div>",
							"<div id=schoolBranchName>"+obj.branchName+"</div>","<div id=schoolPhone>"+obj.phone+"</div>",
							"<div id=schoolEmail>"+obj.email+"</div>","<div id=schoolAddress>"+obj.address+"</div>",obj.dated
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Grade") {
					//getUserSchools();
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=gradeId>"+obj.id+"</div>", "<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=gradeSchoolDD>"+obj.schoolName+"</div>", "<div id=gradeName>"+obj.name+"</div>", 
							"<div id=gradeCode>"+obj.code+"</div>", "<div id=gradeTimeFrom>"+obj.timeFrom+"</div>", 
							"<div id=gradeTimeTo>"+obj.timeTo+"</div>", "<div id=gradeRoom>"+obj.room+"</div>",
							"<div id=dated>"+obj.dated+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Staff") {
					var i=0;
					$.each(collections, function(ind, obj) {
						//getUserSchools();
						arr = [
							"<div id=staffId>"+obj.staffId+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=staffName>"+obj.name+"</div>", "<div id=staffEmail>"+obj.email+"</div>",
							"<div id=staffMobile>"+obj.mobile+"</div>", "<div id=staffPhone>"+obj.phone+"</div>", 
							"<div id=staffTimeIn>"+obj.timeIn+"</div>", "<div id=staffTimeOut>"+obj.timeOut+"</div>",
							"<div id=staffDesignation>"+obj.designation+"</div>", "<div id=staffQualification>"+obj.qualification+"</div>",
							"<div id=staffSchoolDD>"+obj.schoolNames+"</div>", "<div id=staffGradeDD>"+obj.gradeNames+"</div>",
							"<div id=staffSubjectDD>"+obj.subjectNames+"</div>", "<div id=staffStatus>"+obj.status+"</div>",
							"<div id=staffAddress>"+obj.address+"</div>","<div id=staffGender>"+obj.gender+"</div>","<div id=dated>"+obj.dated+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Guardian") {
					var i=0;
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=id>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=donatorName>"+obj.name+"</div>", "<div id=donatorFName>"+obj.fName+"</div>",
							"<div id=donatorMobile>"+obj.mobile+"</div>", "<div id=donatorAddress>"+obj.address+"</div>", 
							"<div id=donatorAmount>"+obj.amount+"</div>", "<div id=donatorReceivedBy>"+obj.receivedBy+"</div>",
							"<div id=dated>"+obj.dated+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Student") {
					var i=0;
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=studentId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=donatorName>"+obj.name+"</div>", "<div id=donatorFName>"+obj.fName+"</div>",
							"<div id=donatorMobile>"+obj.mobile+"</div>", "<div id=donatorAddress>"+obj.address+"</div>", 
							"<div id=donatorAmount>"+obj.amount+"</div>", "<div id=donatorReceivedBy>"+obj.receivedBy+"</div>",
							"<div id=dated>"+obj.dated+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Subject") {
					//getUserGrades();
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=subjectId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=subjectGradeDD>"+obj.gradeName+"</div>","<div id=subjectName>"+obj.name+"</div>", "<div id=subjectCode>"+obj.code+"</div>",
							"<div id=subjectPublisher>"+obj.publisher+"</div>", "<div id=subjectEdition>"+obj.edition+"</div>", 
							"<div id=subjectStatus>"+obj.status+"</div>",
							"<div id=dated>"+obj.dated+"</div>"
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

function getSubjects(table) {	
	console.log(tableV);
    $("#"+table+"SubjectDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserSubjects",function(data){
    	console.log(2);
    	$("#"+table+"SubjectDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table+"SubjectDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserGrades(table) {	
	console.log(tableV);

    $("#"+table+"GradeDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserGrades",function(data){
    	console.log(2);
    	$("#"+table+"GradeDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table+"GradeDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserSchools(table) {	
	console.log(tableV);
    $("#"+table+"SchoolDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserSchools",function(data){
    	console.log(2);
    	$("#"+table+"SchoolDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table+"SchoolDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserOwners(table){
	console.log("getUserOwners");
    $("#"+table.toLowerCase()+"OwnerDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserOwners",function(data){
    	$("#"+table+"OwnerDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table+"OwnerDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getMainBranchName(){
	console.log(3);
    $.get(serverContext+ "getMainBranchName",function(name){
    	console.log("Name : "+name);
    	if(name){
        	$("#schoolName").val(name);
    	}
    })
	.fail(function(name) {
    	$("#schoolName").val(name);
	});
}

