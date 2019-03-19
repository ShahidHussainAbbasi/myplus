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
			$("#"+form[i].id).removeClass("alert-danger");
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
    		  $("#"+form[i].id).removeClass("alert-danger");
    	  else
    		  $("#"+form[i].id).addClass("alert-danger");
      }
      formValidated = false;
    }
}

$(document).ready(function() {
		
	$(".datePicker").datetimepicker({
		format : 'DD-MM-YYYY'
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
    	console.log(this)
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
			validateForm();
		    if(formValidated){
				var formData = $('form').serialize();
					formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');
					$(this).callAjax("add" + buttonV,formData);
		    }else{
		    	alert("Please make sure you have entered valid values");
		    	return false;
		    }
		});

		//All button get initialized when user switch form
		$("#revert"+buttonV).off().click(function() {
		    //If all form's required fields are filled
			validateForm();
		    if(formValidated){
				var formData = $('form').serialize();
					formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');
					$(this).callAjax("revert" + buttonV,formData);
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
				var formData = $('form').serialize();
					formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');
					$(this).callAjax("send" + buttonV,formData);
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
/*	  	 var table = $('#example').DataTable();
	     
	     $('#example tbody').on('click', 'tr', function () {
	         var data = table.row( this ).data();
	         alert( 'You clicked on '+data[0]+'\'s row' );
	     } );*/	  	
		$("#table" + tableV).on( 'click', 'tr', function () {
			var html = datatable.row(this).data();//.selector.rows.innerHTML;
			var doc = new DOMParser().parseFromString(html, "text/html");
			
			resetForm();
			editRecord(doc);
		} );
	  });
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
					alert("Already exist");
					return false;
				}
				datatable.clear().draw();
				datatable.ajax.reload();
				resetForm();
				$("#globalError").empty();
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
	            		//$("#globalError").focus();
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
		edit = false;
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
	edit = true;
	for(var i=0; i<(formFields); i++){
		if(doc.getElementById(form[i].id)){
			var text = doc.getElementById(form[i].id).textContent;
//			var value = doc.getElementById(form[i].id).value;
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
			if(form[i].className.indexOf("selectpicker")>-1){
				$( "#"+form[i].id+" :selected" ).text(text);
				//$( "#"+form[i].id+" option:selected" ).text(text);
				//$("#"+form[i].id).text(text);
				$("#"+form[i].id).selectpicker('refresh');
			}
		}
	}
}

function resetBSDD(id){
	edit = false;
	$("#"+id).val('default').selectpicker("refresh");
}
