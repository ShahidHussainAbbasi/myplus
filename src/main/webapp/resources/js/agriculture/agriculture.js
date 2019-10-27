
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
							"<div id='expenseLandNameDD'>"+obj.landName+"</div>",/* "<div id='landName'>"+obj.landName+"</div>", */
							"<div id='expenseCropNameDD'>"+obj.cropName+"</div>", "<div id='expenseCropType'>"+obj.cropType+"</div>",
							 "<div id='expenseNameDD'>"+obj.expenseName+"</div>","<div id='expenseDescription'>"+obj.description+"</div>",
							 /*"<div id='expenseTypeDD'>"+obj.expenseType+"</div>",*/
							"<div id='expenseAmount'>"+obj.amount+"</div>", "<div id='expenseDatedStr'>"+obj.datedStr+"</div>", 
							"<div id='expenseUpdatedStr'>"+obj.updatedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					loadDD("getUserLands","expenseLandNameDD");
				} else if (getAll === "AgricultureIncome") {
					$.each(collections, function(ind, obj) {
						arr = [
							/*"<div id='incomeId'>"+ obj.id+ "</div>",*/"<input id='incomeId' type='checkbox' value='"+ obj.id+ "'>",
							"<div id='incomeLandNameDD'>"+obj.landName+"</div>",/* "<div id='landName'>"+obj.landName+"</div>", */ 
							"<div id='incomeCropNameDD'>"+obj.cropName+"</div>", "<div id='incomeCropType'>"+obj.cropType+"</div>",
							"<div id='incomeNameDD'>"+obj.incomeName+"</div>","<div id='incomeDescription'>"+obj.description+"</div>",
							"<div id='incomeAmount'>"+obj.amount+"</div>", "<div id='incomeDatedStr'>"+obj.datedStr+"</div>", 
							"<div id='incomeUpdatedStr'>"+obj.updatedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					loadDD("getUserLands","incomeLandNameDD");
				} else if (getAll === "Land") {
					$.each(collections, function(ind, obj) {
						arr = [
							/*"<div id='landId'>"+ obj.id+ "</div>",*/
							"<input id='landId' type='checkbox' value='"+ obj.id+ "'>","<div id='landUnitDD'>"+obj.landUnit+"</div>",
							"<div id='totalLandUnitOf'>"+obj.totalLandUnit+"</div>", "<div id='landName'>"+obj.landName+"</div>", 
							"<div id='landType'>"+obj.landType+"</div>", "<div id='landDatedStr'>"+obj.datedStr+"</div>", 
							"<div id='landUpdatedStr'>"+obj.updatedStr+"</div>","<div id='description'>"+obj.description+"</div>"
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