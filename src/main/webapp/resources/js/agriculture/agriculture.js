
$(document).ready(function() {

});

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
				
				if (getAll === "AgricultureExpense") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id='expenseId'>"+ obj.id+ "</div>","<input type='checkbox' value='"+ obj.id+ "'>",
							"<div id='expenseLandNameDD'>"+obj.landName+"</div>",/* "<div id='landName'>"+obj.landName+"</div>", */
							"<div id='cropNameDD'>"+obj.cropName+"</div>", "<div id='cropType'>"+obj.cropType+"</div>",
							"<div id='expenseTypeDD'>"+obj.expenseType+"</div>", "<div id='expenseName'>"+obj.expenseName+"</div>",
							"<div id='amount'>"+obj.amount+"</div>", "<div id='datedStr'>"+obj.datedStr+"</div>", 
							"<div id='updatedStr'>"+obj.updatedStr+"</div>","<div id='description'>"+obj.description+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					loadLandDD("getUserLands","expenseLandNameDD");
				} else if (getAll === "AgricultureIncome") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id='incomeId'>"+ obj.id+ "</div>","<input type='checkbox' value='"+ obj.id+ "'>",
							"<div id='incomeLandNameDD'>"+obj.landName+"</div>",/* "<div id='landName'>"+obj.landName+"</div>", */ 
							"<div id='incomeCropNameDD'>"+obj.cropName+"</div>", "<div id='incomeCropType'>"+obj.cropType+"</div>",
							"<div id='incomeTypeDD'>"+obj.incomeType+"</div>", "<div id='incomeName'>"+obj.incomeName+"</div>",
							"<div id='incomeAmount'>"+obj.amount+"</div>", "<div id='incomeDatedStr'>"+obj.datedStr+"</div>", 
							"<div id='incomeUpdatedStr'>"+obj.updatedStr+"</div>","<div id='incomeDescription'>"+obj.description+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					loadLandDD("getUserLands","incomeLandNameDD");
				} else if (getAll === "Land") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id='landId'>"+ obj.id+ "</div>","<input type='checkbox' value='"+ obj.id+ "'>",
							"<div id='landUnitDD'>"+obj.landUnit+"</div>", "<div id='totalLandUnitOf'>"+obj.totalLandUnit+"</div>", 
							"<div id='landName'>"+obj.landName+"</div>", "<div id='landType'>"+obj.landType+"</div>",
							"<div id='landDatedStr'>"+obj.datedStr+"</div>", 
							"<div id='landUpdatedStr'>"+obj.updatedStr+"</div>","<div id='landDescription'>"+obj.description+"</div>"
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

function loadLandDD(remoteMethod,destinationId){
	loadDD(remoteMethod,destinationId);
}

function loadLastCropAttached(destinationId){
	var value  = $("#"+destinationId)[0].selectedOptions[0].value;
	if(!value || value == '')
		return false;
    $.get(serverContext+ "loadLastCropAttached?landId="+value ,function(data){
		$("#cropNameDD  option").each(function() {
			if(data.object.cropName.indexOf($(this).text()) > -1) {
				$(this).prop('selected', true);
			}else{
				$(this).prop('selected', false);
			}                      
		});
		$("#cropType")[0].value = data.object.cropType;
    })
	.fail(function(data) {
		alert(xhr.responseText);
	});
}