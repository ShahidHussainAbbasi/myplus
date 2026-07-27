var buttonV = "Donation";
//var searchV = "Donation";
var deleteV = "Donation";
var tableV = "Donation";
var getAll = "Donation";
var datatable=null;
var formValidated = true;
var form=null;
var formFields = 0;

// resetGlobalError / resetForm / validateForm / editRecord are SHARED helpers — defined once in main.js.
// (Removed the welfare duplicates to satisfy DRY: same function must not live in two files.)

$(document).ready(function() {
/*	$switchInputs =function(val) {
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
*/	
	// It will show hide
/*	$(function() {
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
	            		$("#globalError").show().append(escHtml(item.defaultMessage)+"<br/>");
	            	}
	         	});
            }
		});
	}

});
/*
const capitalize = (s) => {
	if (typeof s !== 'string') return ''
  		return s.charAt(0).toUpperCase() + s.slice(1)
}
*/
// editRecord is a SHARED helper defined in main.js (see note above). Welfare copy removed for DRY.
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
				if (getAll === "Donation") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=donationId>"+ obj.id+ "</div>","<input type='checkbox' value='"+ obj.id+ "' id='abc'>",
							"<div id=donationDonatorDD>"+escHtml(obj.donatorName)+"</div>", "<div id=donationAmount>"+escHtml(obj.amount)+"</div>", 
							"<div id=donationReceivedBy>"+escHtml(obj.receivedBy)+"</div>", "<div id=donationDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					getAllDonators();
				} else if (getAll === "Donator") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=donatorId>"+ obj.id+ "</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							// Contact-360 rides in the name cell (no new column): this donor's roles across modules.
							"<div id=donatorName>"+escHtml(obj.name)+contact360Button(obj.partyId)+"</div>", "<div id=donatorFName>"+escHtml(obj.fName)+"</div>",
							"<div id=donatorMobile>"+escHtml(obj.mobile)+"</div>", "<div id=donatorAddress>"+escHtml(obj.address)+"</div>",
							"<div id=donatorDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Donations") {
					var i=0;
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=donationsId>"+ obj.id+ "</div>","<div id=donationsrName>"+escHtml(obj.name)+"</div>", "<div id=donationsFName>"+escHtml(obj.fName)+"</div>",
							"<div id=donationsMobile>"+escHtml(obj.mobile)+"</div>", "<div id=donationsAddress>"+escHtml(obj.address)+"</div>", 
							"<div id=donationsAmount>"+escHtml(obj.amount)+"</div>", "<div id=donationsReceivedBy>"+escHtml(obj.receivedBy)+"</div>",
							"<div id=donationsDated>"+escHtml(obj.datedStr)+"</div>"
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
	$("#donationDonatorDD").empty();
    $("#donationDonatorDD").append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getAllDonators",function(data){
    	$("#donationDonatorDD").empty().append(data);
    })
	.fail(function(data) {
		$("#donationDonatorDD").empty().append("<option value = ''> System error  </option>");
	});
}


// ===== Owner Configuration (generic per-tenant settings, shared common-settings backend) =====
// Self-renders from the welfare-service catalog (/getWelfareConfig → GenericResponse.collection): each row is one
// configurable policy grouped by section. A toggle saves immediately (/saveWelfareConfig key=&value=). Adding a
// new setting is a catalog entry in the service (WelfareSettingsCatalog) — no change here.
function showConfig(){
	$('.formDiv').hide();
	$('#ConfigDiv').show();
	$('#welfareConfigMsg').hide();
	loadConfig();
}

function loadConfig(){
	$('#welfareConfigBody').text('Loading…');
	$.get(serverContext + 'getWelfareConfig', function(res){
		var items = (res && (res.collection || res.object)) || [];
		if(!items.length){ $('#welfareConfigBody').html('<p style="color:#7a889c">No configurable settings.</p>'); return; }
		var groups = {};
		items.forEach(function(it){ (groups[it.group] = groups[it.group] || []).push(it); });
		var html = '';
		Object.keys(groups).forEach(function(g){
			html += '<h4 style="margin-top:18px">' + escHtml(g) + '</h4>';
			groups[g].forEach(function(it){
				var on = String(it.value) === 'true';
				if(it.type === 'BOOL'){
					html += '<div class="form-group" style="margin-bottom:12px">'
						+ '<label class="control-label col-sm-5" for="cfg_' + escHtml(it.key) + '">' + escHtml(it.label) + '</label>'
						+ '<div class="col-sm-7">'
						+ '<input type="checkbox" id="cfg_' + escHtml(it.key) + '" data-key="' + escHtml(it.key) + '"'
						+ (on ? ' checked' : '') + ' onchange="saveConfigToggle(this)"/>'
						+ '<div style="color:#7a889c;font-size:12px;margin-top:3px">' + escHtml(it.help || '') + '</div>'
						+ '</div></div>';
				}
			});
		});
		$('#welfareConfigBody').html('<div class="form-horizontal">' + html + '</div>');
	}, 'json').fail(function(){ $('#welfareConfigBody').html('<p style="color:#c0392b">Could not load configuration.</p>'); });
}

function saveConfigToggle(el){
	var key = el.getAttribute('data-key');
	var value = el.checked ? 'true' : 'false';
	$.post(serverContext + 'saveWelfareConfig', { key: key, value: value }, function(res){
		var ok = res && (res.status === 'SUCCESS');
		$('#welfareConfigMsg').removeClass('alert-success alert-danger')
			.addClass(ok ? 'alert-success' : 'alert-danger')
			.text(ok ? 'Saved.' : ((res && res.message) || 'Save failed')).show();
		if(!ok){ el.checked = !el.checked; }
	}).fail(function(){
		el.checked = !el.checked;
		$('#welfareConfigMsg').removeClass('alert-success').addClass('alert-danger').text('Save failed').show();
	});
}
