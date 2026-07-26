
$(document).ready(function() {


});

function loadDataTable(){
	//check if data table exist destroy it
	var offset = $( "select[name='table"+tableV+"_length']" ).val();
	if(!offset)
		offset = 5;
	
	var pl = offset;
	if(pl==-1)
		pl=100;
	if (datatable!=null){
		datatable.destroy();
		datatable = null;
	}
	datatable = $("#table" + tableV).DataTable({
		lengthMenu:[[5, 20, 50,100, -1 ],[ '5', '20', '50', '100', 'All' ]],
		"iDisplayLength": offset,
		"pageLength": pl,
		"order": [[ 0, "desc" ]],
		"autoWidth" : true,
		dom: 'Bfrtip',
        buttons: [
        	'pageLength',
            { extend: 'copyHtml5', footer: true },
            { extend: 'csvHtml5', footer: true },
            { extend: 'excelHtml5', footer: true },
            {extend:'print', footer: true },
        	{
                extend: 'pdfHtml5',
                orientation: 'landscape',
                pageSize: 'LEGAL',
                footer: true
            }
        ],
        "footerCallback": function ( row, data, start, end, display ) {
 	        var api = this.api(), data;
 	        if(tableV!="Land"){
	 	        // Remove the formatting to get integer data for summation
	 	        var intVal = function ( i ) {
	 	            return typeof i === 'string' ?
	 	                i.replace(/[\$,]/g, '')*ONE :
	 	                typeof i === 'number' ?
	 	                    i : 0;
	 	        };
	 	        var elementId = "expenseAmount";
	 	        if(tableV == "AgricultureIncome")
	 	        	elementId = "incomeAmount";
		        // Total over all pages
		        dueTotal = api.column(6).data().reduce( function (a, b) {
		                return intVal(a) + intVal(getDocument(b).getElementById(elementId).textContent*ONE);
		            }, 0 );
		
		        // Total over this page
		        duePageTotal = api.column(6, { page: 'current'} ).data().reduce( function (a, b) {
		        	
		                return intVal(a) + intVal(getDocument(b).getElementById(elementId).textContent*ONE);
		            }, 0 );
		
		        // Update footer
		        $( api.column(6).footer() ).html(
		        		duePageTotal +'/'+ dueTotal
		        );
        	}
 	        
 	    },        
		"ajax" : {
			"url" : serverContext + "getUser" + getAll,
			"type" : "GET",
			"success" : function(data) {
				var collections = data.collection;
				console.log("getUser : "+getAll+" collections : "+collections);
				var arr = [" No Data Found "];
				
				if (getAll === "AgricultureExpense") {
					$.each(collections, function(ind, obj) {
						arr = [
							/*"<div id='expenseId'>"+ obj.id+ "</div>",*/"<input id='expenseId' type='checkbox' value='"+ obj.id+ "'>",
							"<div id='expenseLandNameDD'>"+escHtml(obj.landName)+"</div>",/* "<div id='landName'>"+escHtml(obj.landName)+"</div>", */
							"<div id='expenseCropNameDD'>"+escHtml(obj.cropName)+"</div>", "<div id='expenseCropType'>"+escHtml(obj.cropType)+"</div>",
							 "<div id='expenseNameDD'>"+escHtml(obj.expenseName)+"</div>","<div id='expenseDescription'>"+escHtml(obj.description)+"</div>",
							 /*"<div id='expenseTypeDD'>"+escHtml(obj.expenseType)+"</div>",*/
							"<div id='expenseAmount'>"+escHtml(obj.amount)+"</div>", "<div id='expenseDatedStr'>"+escHtml(obj.datedStr)+"</div>", 
							"<div id='expenseUpdatedStr'>"+escHtml(obj.updatedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					loadDD("getUserLands","expenseLandNameDD");
				} else if (getAll === "AgricultureIncome") {
					$.each(collections, function(ind, obj) {
						arr = [
							/*"<div id='incomeId'>"+ obj.id+ "</div>",*/"<input id='incomeId' type='checkbox' value='"+ obj.id+ "'>",
							"<div id='incomeLandNameDD'>"+escHtml(obj.landName)+"</div>",/* "<div id='landName'>"+escHtml(obj.landName)+"</div>", */ 
							"<div id='incomeCropNameDD'>"+escHtml(obj.cropName)+"</div>", "<div id='incomeCropType'>"+escHtml(obj.cropType)+"</div>",
							"<div id='incomeNameDD'>"+escHtml(obj.incomeName)+"</div>","<div id='incomeDescription'>"+escHtml(obj.description)+"</div>",
							"<div id='incomeAmount'>"+escHtml(obj.amount)+"</div>", "<div id='incomeDatedStr'>"+escHtml(obj.datedStr)+"</div>", 
							"<div id='incomeUpdatedStr'>"+escHtml(obj.updatedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					loadDD("getUserLands","incomeLandNameDD");
				} else if (getAll === "Land") {
					$.each(collections, function(ind, obj) {
						arr = [
							/*"<div id='landId'>"+ obj.id+ "</div>",*/
							"<input id='landId' type='checkbox' value='"+ obj.id+ "'>","<div id='landUnitDD'>"+escHtml(obj.landUnit)+"</div>",
							"<div id='totalLandUnitOf'>"+escHtml(obj.totalLandUnit)+"</div>", "<div id='landName'>"+escHtml(obj.landName)+"</div>", 
							"<div id='landType'>"+escHtml(obj.landType)+"</div>", "<div id='landDatedStr'>"+escHtml(obj.datedStr)+"</div>", 
							"<div id='landUpdatedStr'>"+escHtml(obj.updatedStr)+"</div>","<div id='description'>"+escHtml(obj.description)+"</div>"
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


/*function loadFVIBSDD(element,destinationId){
	var value  = $(element)[0].selectedOptions[0].value;
	if(!value || value == '')
		return false;
	loadBSDD("getUser"+value.trim(),destinationId);
}*/

//function loadLandDD(remoteMethod,destinationId){
//	loadDD(remoteMethod,destinationId);
//}

function loadLastCropAttached(destinationId){
	var value  = $("#"+destinationId)[0].selectedOptions[0].value;
	if(!value || value == '')
		return false;
	
     var controller = "expense";
      if(tableV == "AgricultureIncome")
    	  controller = "income";
	
    $.get(serverContext+""+controller+"/loadLastCropAttached?landId="+value ,function(data){
		if(data.status === "NOT_FOUND"){
			$(this).prop('selected', false);
			$("#"+controller+"CropNameDD").prop('selectedIndex',0);
			$("#"+controller+"CropType")[0].value = "";
			return;
		}
		$("#"+controller+"CropNameDD  option").each(function() {
			if(data.object.cropName && data.object.cropName.indexOf($(this).text()) > -1) {
				$(this).prop('selected', true);
			}else{
				$(this).prop('selected', false);
			}                      
		});
		$("#"+controller+"CropType")[0].value = data.object.cropType;
    })
	.fail(function(data) {
		alert(xhr.responseText);
	});
}
// ===== Owner Configuration (generic per-tenant settings, shared common-settings backend) =====
// Self-renders from the agriculture-service catalog (/getAgricultureConfig → GenericResponse.collection): each row
// is one configurable policy grouped by section. A toggle saves immediately (/saveAgricultureConfig key=&value=).
// Adding a new setting is a catalog entry in the service (AgricultureSettingsCatalog) — no change here.
function showConfig(){
	$('.formDiv').hide();
	$('#ConfigDiv').show();
	$('#agriConfigMsg').hide();
	loadConfig();
}

function loadConfig(){
	$('#agriConfigBody').text('Loading…');
	$.get(serverContext + 'getAgricultureConfig', function(res){
		var items = (res && (res.collection || res.object)) || [];
		if(!items.length){ $('#agriConfigBody').html('<p style="color:#7a889c">No configurable settings.</p>'); return; }
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
		$('#agriConfigBody').html('<div class="form-horizontal">' + html + '</div>');
	}, 'json').fail(function(){ $('#agriConfigBody').html('<p style="color:#c0392b">Could not load configuration.</p>'); });
}

function saveConfigToggle(el){
	var key = el.getAttribute('data-key');
	var value = el.checked ? 'true' : 'false';
	$.post(serverContext + 'saveAgricultureConfig', { key: key, value: value }, function(res){
		var ok = res && (res.status === 'SUCCESS');
		$('#agriConfigMsg').removeClass('alert-success alert-danger')
			.addClass(ok ? 'alert-success' : 'alert-danger')
			.text(ok ? 'Saved.' : ((res && res.message) || 'Save failed')).show();
		if(!ok){ el.checked = !el.checked; }
	}).fail(function(){
		el.checked = !el.checked;
		$('#agriConfigMsg').removeClass('alert-success').addClass('alert-danger').text('Save failed').show();
	});
}
