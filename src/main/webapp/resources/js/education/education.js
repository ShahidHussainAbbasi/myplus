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
	
$(document).ready(function() {
    $('table.display').DataTable( {
        "paging":   false,
        "ordering": false,
        "info":     false,
        "searching": false
    } );
    
    $("#fvp").change(function(){
    	if($(this).val()=="4"){
    		$("#fvdrdiv").show();
    	}else{
    		$("#fvdrdiv").hide();
    	}
    });    
} );

function loadDataTable(){
	//check if data table exist destroy it
	if (datatable!=null){
		datatable.destroy();
	}
	datatable = $("#table" + tableV).DataTable({
		"autoWidth" : true,
		"order": [[ 0, "desc" ]],
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
					getUserStudents(table);
					getUserStaffs(table);
					getUserGuardians(table);
					getUserVehicles(table);
					getUserDiscounts(table);
					getUserSubjects(table);
					reload=tableV;
					getUserStudentMap();

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
							"<div id=ownerAddress>"+obj.address+"</div>", "<div id=ownerStatus>"+obj.status+"</div>",
							"<div id=ownerDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "School") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=schoolId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=schoolOwnerDD>"+obj.ownerNames+"</div>",/*"<div id=schoolName>"+obj.name+"</div>",*/
							"<div id=schoolBranchName>"+obj.branchName+"</div>","<div id=schoolPhone>"+obj.phone+"</div>",
							"<div id=schoolEmail>"+obj.email+"</div>","<div id=schoolAddress>"+obj.address+"</div>",
							"<div id=schoolStatus>"+obj.status+"</div>",
							"<div id=schoolDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Grade") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=gradeId>"+obj.id+"</div>", "<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=gradeSchoolDD>"+obj.schoolName+"</div>","<div id=gradeName>"+obj.name+"</div>", 
							"<div id=gradeCode>"+obj.code+"</div>", "<div id=gradeTimeFromStr>"+obj.timeFromStr+"</div>", 
							"<div id=gradeTimeToStr>"+obj.timeToStr+"</div>", "<div id=gradeRoom>"+obj.room+"</div>",
							"<div id=gradeStatus>"+obj.status+"</div>","<div id=gradeRoomDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Staff") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=staffId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=staffName>"+obj.name+"</div>", "<div id=staffEmail>"+obj.email+"</div>",
							"<div id=staffMobile>"+obj.mobile+"</div>", "<div id=staffPhone>"+obj.phone+"</div>", 
							"<div id=staffTimeInStr>"+obj.timeInStr+"</div>", "<div id=staffTimeOutStr>"+obj.timeOutStr+"</div>",
							"<div id=staffDesignation>"+obj.designation+"</div>", "<div id=staffQualification>"+obj.qualification+"</div>",
							/*"<div id=staffSchoolDD>"+obj.schoolNames+"</div>",*/ 
							"<div id=staffGradeDD>"+obj.gradeNames+"</div>", "<div id=staffGender>"+obj.gender+"</div>",
							"<div id=staffDateOfBirth>"+obj.dateOfBirth+"</div>","<div id=staffMartialStatus>"+obj.martialStatus+"</div>",
							 "<div id=staffAddress>"+obj.address+"</div>","<div id=staffStatus>"+obj.status+"</div>",
							 "<div id=staffDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Guardian") {
					var i=0;
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=guardianId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=guardianName>"+obj.name+"</div>", "<div id=guardianEmail>"+obj.email+"</div>", 
							"<div id=guardianMobile>"+obj.mobile+"</div>", "<div id=guardianPhone>"+obj.phone+"</div>", 
							"<div id=guardianCNIC>"+obj.cnic+"</div>", "<div id=guardianPermAddress>"+obj.permAddress+"</div>",
							"<div id=guardianRelation>"+obj.relation+"</div>", "<div id=guardianOccupation>"+obj.occupation+"</div>",
							"<div id=guardianStatus>"+obj.status+"</div>","<div id=guardianDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Student") {
					var i=0;
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=studentId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=studentEnrollNo>"+obj.enrollNo+"</div>","<div id=studentEnrollDate>"+obj.enrollDate+"</div>",
							"<div id=studentName>"+obj.name+"</div>","<div id=studentSchoolDD>"+obj.schoolName+"</div>",
							"<div id=studentGradeDD>"+obj.gradeName+"</div>","<div id=studentGuardianDD>"+obj.guardianName+"</div>",
							"<div id=studentFeeMode>"+obj.feeMode+"</div>","<div id=studentFee>"+obj.fee+"</div>",
							"<div id=studentVehicleDD>"+obj.vehicleName+"</div>","<div id=studentvf>"+obj.vf+"</div>",
							"<div id=studentDiscountDD>"+obj.discountName+"</div>","<div id=studentND>"+obj.nd+"</div>",
							"<div id=studentDIDD>"+obj.di+"</div>","<div id=studentDueDay>"+obj.dueDay+"</div>",
							"<div id=studentMobile>"+obj.mobile+"</div>","<div id=studentEmail>"+obj.email+"</div>",
							"<div id=studentGender>"+obj.gender+"</div>","<div id=studentDateOfBirth>"+obj.dateOfBirth+"</div>",
							"<div id=studentBloodBroup>"+obj.bloodGroup+"</div>","<div id=studentAddress>"+obj.address+"</div>",
							"<div id=studentStatus>"+obj.status+"</div>","<div id=studentDated>"+obj.updatedStr+"</div>",
							"<div id=studentYS>"+obj.ys+"</div>","<div id=studentYE>"+obj.ye+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Subject") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=subjectId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=subjectGradeDD>"+obj.gradeName+"</div>",
							"<div id=subjectName>"+obj.name+"</div>", "<div id=subjectCode>"+obj.code+"</div>",
							"<div id=subjectPublisher>"+obj.publisher+"</div>", "<div id=subjectEdition>"+obj.edition+"</div>", 
							"<div id=subjectStatus>"+obj.status+"</div>","<div id=subjectDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Vehicle") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=vehicleId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=vehicleSchoolDD>"+obj.schoolName+"</div>","<div id=vehicleName>"+obj.name+"</div>",
							"<div id=vehicleNumber>"+obj.number+"</div>", "<div id=vehicleDriverName>"+obj.driverName+"</div>",
							"<div id=vehicleDriverMobile>"+obj.driverMobile+"</div>", "<div id=vehicleOwnerName>"+obj.ownerName+"</div>", 
							"<div id=vehicleOwnerMobile>"+obj.ownerMobile+"</div>", "<div id=vehicleStatus>"+obj.status+"</div>", 
							"<div id=vehicleDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Discount") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=discountId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=discountNameDD>"+obj.name+"</div>","<div id=discountTypeDD>"+obj.di+"</div>", 
							"<div id=discountAmount>"+obj.amount+"</div>","<div id=discountDescription>"+obj.description+"</div>",
							"<div id=discountStatus>"+obj.status+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Alerts") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=aId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=acdd>"+obj.c+"</div>","<div id=atdd>"+obj.at+"</div>",
							"<div id=adcdd>"+obj.dc+"</div>","<div id=adpdd>"+obj.dp+"</div>",
							"<div id=adtdd>"+obj.dt+"</div>","<div id=ast>"+obj.st+"</div>",
							"<div id=asd>"+obj.sdStr+"</div>", "<div id=aed>"+obj.edStr+"</div>", 
							"<div id=ah>"+obj.ah+"</div>","<div id=am>"+obj.am+"</div>",
							"<div id=as>"+obj.as+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Fc") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=fcId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=inputFc>"+obj.en+"</div>","<div id=fcpd>"+obj.pdStr+"</div>",
							"<div id=fcp>"+obj.p+"</div>","<div id=fcrb>"+obj.rb+"</div>",
							"<div id=fchf>"+obj.f+"</div>","<div id=fchvf>"+obj.vf+"</div>",
							"<div id=fchd>"+obj.d+"</div>","<div id=fchdt>"+obj.dt+"</div>",
							"<div id=fcod>"+obj.od+"</div>","<div id=fcodd>"+obj.odd+"</div>",
							"<div id=fchda>"+obj.da+"</div>","<div id=fcfp>"+obj.fp+"</div>", 
							"<div id=fcdb>"+obj.db+"</div>","<div id=fchdd>"+obj.dd+"</div>", 
							"<div id=fcri>"+obj.ri+"</div>","<div id=fccn>"+obj.cn+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "A") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=aId>"+obj.id+"</div>",
							"<div id=aen>"+obj.en+"</div>","<div id=asn>"+obj.sn+"</div>","<div id=fcdb>"+obj.gn+"</div>",
							"<div id=fchdt>"+obj.dtStr+"</div>"
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

function getUserOwners(table){
    $("#"+table.toLowerCase()+"OwnerDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserOwners",function(data){
    	$("#"+table.toLowerCase()+"OwnerDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"OwnerDD").empty().append("<option value = ''> System error  </option>");
	});
}
function getUserSchools(table) {	
	$select = $("#"+table.toLowerCase()+"SchoolDD");    
    $.get(serverContext+ "getUserSchools",function(data){
    	$select.empty().append(data);//.selectpicker('refresh');
    }).fail(function(data) {
		$select.empty().append("<option value = ''> System error  </option>");
	});
}

function getUserStaffs(table){
    $("#"+table.toLowerCase()+"StaffDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserStaffs",function(data){
    	$("#"+table.toLowerCase()+"StaffDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"StaffDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserGuardians(table){
    $("#"+table.toLowerCase()+"GuardianDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserGuardians",function(data){
    	$("#"+table.toLowerCase()+"GuardianDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"GuardianDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserSubjects(table) {	
    $("#"+table+"SubjectDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserSubjects",function(data){
    	$("#"+table.toLowerCase()+"SubjectDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"SubjectDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserGrades(table) {	
    $("#"+table+"GradeDD").empty().append("<option value = ''> Please wait....  </option>");
    $.get(serverContext+ "getUserGrades",function(data){
    	$("#"+table.toLowerCase()+"GradeDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"GradeDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserStudents(table) {	
	console.log(tableV);
    $("#"+table+"StudentDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserStudents",function(data){
    	$("#"+table.toLowerCase()+"StudentDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"StudentDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserVehicles(table){
	console.log(table);
    $("#"+table.toLowerCase()+"VehicleDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserVehicles",function(data){
    	$("#"+table.toLowerCase()+"VehicleDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"VehicleDD").empty().append("<option value = ''> System error  </option>");
	});
}

function getUserDiscounts(table){
	console.log(table);
    $("#"+table.toLowerCase()+"DiscountDD").empty().append("<option value = ''> Please wait....  </option>");
    
    $.get(serverContext+ "getUserDiscounts",function(data){
    	$("#"+table.toLowerCase()+"DiscountDD").empty().append(data);
    })
	.fail(function(data) {
		$("#"+table.toLowerCase()+"DiscountDD").empty().append("<option value = ''> System error  </option>");
	});
}

//Temp Fee Voucher(FV) object
var fvObj = "";
function findBy(method,data){
	$.ajax({
		type : "GET",
		url : serverContext + method,
		dataType : "json",
	//	timeout : 100000,
		data : data,
		success : function(data) {
			if(data.status==="NOT_FOUND"){
				removeTableBody();
				return alert("Enrolled ID is invalid or not exist.");
			}else if(data.status==="SUCCESS"){
				console.log(data);
				fvObj = data;
				if(!data || !data.object)
					return alert("Data not found.");
					
					var o = data.object.sf;
					if(!o)
						return alert("Invalid data");
					
					var dm=0;//due months
					
					if(o.lpd){
						var lpd = new Date(Date.parse(o.lpd));
						dm = monthDiff(lpd,new Date());
						if(dm <= 0){
							resetForm();
							removeTableBody();
							return alert("Current month's Fee has been paid.");
						}
					}
					$("#fcsn").html(o.sn);
					$("#fcgn").html(o.gn);
					$("#fcscn").html(o.scn);
					$("#fcg").html(o.g);
					$("#fcf").html(o.f);
					$("#fcf").val(o.f);
					var vf = s2n(o.vf);
					var d  = s2n(o.d);
					var f = s2n(o.f);
					$("#fchf").val(f);
					$("#fcvf").html(vf);
					$("#fchvf").val(vf);
					$("#fchdt").val(o.dt);
					var tf = f;
					if(o.dt=="%"){
						var d = f * (d / 100);
						$("#fcd").html(d+" in "+o.dt);
						$("#fchd").val(d);
					}else{
						$("#fcd").html(d);
						$("#fchd").val(d);
					}
					tf = f + vf - d;
					if(dm>1)
						tf = tf*dm;
					if(o.db)
						tf = tf+o.db;
					
					$("#fcda").html(tf);
					$("#fchda").val(tf);
					//again setting to get in print
					o.da=tf;
					o.d=d;

					var dd = s2n(o.dd);
					$("#fchdd").val(dd);
					if(new Date().getDate() <=dd){
						$("#fcdd").removeClass("alert-danger");
						$("#fcdd").html(dd - new Date().getDate() +" day(s) left");
					}else{
						$("#fcdd").html(new Date().getDate() - dd +" day(s) over");
						$("#fcdd").addClass("alert-danger");
					}
					
					$("#fcpd").val(o.pdStr);
				//sfd - student fee detail
					var l = data.object.sfd;
					if(l){
						fd(l);
					}	
					return false;
			}
			return false;
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
			 alert("Status : error : "+textStatus+" : "+errorThrown);
        }
	});
}

function fd(l){
	$('#fcDT').DataTable().clear().draw();
	l.forEach(function(obj,i){
		var t = $('#fcDT').DataTable();
		t.row.add( [
			"<div id=fcdpd>"+dateToDMY(new Date(obj.pd))+"</div>","<div id=fcdp>"+obj.p+"</div>","<div id=fcdri>"+obj.rb+"</div>",
			"<div id=fcdhf>"+obj.f+"</div>","<div id=fcdhvf>"+obj.vf+"</div>","<div id=fcdhd>"+obj.d+"</div>",
			"<div id=fcdod>"+obj.od+"</div>","<div id=fcdodd>"+obj.odd+"</div>","<div id=fcdhda>"+obj.da+"</div>",
			"<div id=fcdfp>"+obj.fp+"</div>", "<div id=fcddb>"+obj.db+"</div>",
		] ).draw( false );			
	});
}

function monthDiff(d1, d2) {
	return d2.getMonth() - d1.getMonth() + (12 * (d2.getFullYear() - d1.getFullYear()));
}

function getDB(){
	var da = $("#fchda").val()*ONE; 
	var fp = $("#fcfp").val()*ONE;
	if(da>fp)
		$("#fcdb").val(da - fp);
	else
		$("#fcdb").val(0);
}

function removeTableBody(){
    $('.fcDTC').empty();
    $('#fcDT').DataTable().clear().draw();
}

function ma(v){
	for ( var i in sm) {
		if(v===i){
			$(this).callAjax("markAttendance2",sm[i]);
		}
	}
}

var sm = {"":[]};
function getUserStudentMap(){
    $.get(serverContext+ "getUserStudentMap",function(data){
		console.log(data)
		if(data.status=="SUCCESS"){
			sm = data.object;//new Map(ind,val);
		}
		console.log(sm)
    })
	.fail(function(data) {
		console.log(data)
	});
	
}

function getImgFromUrl(logo_url, callback) {
    var img = new Image();
   // var logo_url = serverContext+"resources/a.jpg";
    img.src = logo_url;
    img.onload = function () {
        callback(img);
    };
} 

function toDataURL(url, callback) {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		var reader = new FileReader();
	    reader.onloadend = function() {
	      callback(reader.result);
	    }
	    reader.readAsDataURL(xhr.response);
	};
	xhr.open('GET', url);
	xhr.responseType = 'blob';
	xhr.send();
}

function loadFV(){
	var formData = $('form').serialize();
	formData = formData.replace(/[^&]+=\.?(?:&|$)/g, '');

	$.ajax({
		type : "POST",
		url : serverContext + "loadFV",
		dataType : "json",
		data : formData,
		success : function(data) {
			if(data.status==="NOT_FOUND"){
				return alert(data.status+" - "+data.msg);
			}else if(data.status==="SUCCESS"){
				if(!data || !data.object)
					return alert("Data not found.");
					
//					$.each(data.object.sfd, function(ind, o) {
					var o = data.object.sf;
					console.log(o);
					if(!o)
						return alert("Invalid data");
					
					var FVL = [];
					var dm=0;//due months
					
					if(o.lpd){
						var lpd = new Date(Date.parse(o.lpd));
						dm = monthDiff(lpd,new Date());
					}
//						$("#fcsn").html(o.sn);
//						$("#fcgn").html(o.gn);
//						$("#fcscn").html(o.scn);
//						$("#fcg").html(o.g);
//						$("#fcf").html(o.f);
//						$("#fcf").val(o.f);
					var vf = s2n(o.vf);
					var d  = s2n(o.d);
					var f = s2n(o.f);
//						$("#fchf").val(f);
//						$("#fcvf").html(vf);
//						$("#fchvf").val(vf);
//						$("#fchdt").val(o.dt);
					var tf = f;
					if(o.dt=="%"){
						var d = f * (d / 100);
//							$("#fcd").html(d+" in "+o.dt);
//							$("#fchd").val(d);
					}else{
//							$("#fcd").html(d);
//							$("#fchd").val(d);
					}
					tf = f + vf - d;
					if(dm>1)
						tf = tf*dm;
					if(o.db)
						tf = tf+o.db;
					
//						$("#fcda").html(tf);
//						$("#fchda").val(tf);
					//again setting to get in print
					o.da=tf;
					o.d=d;

					var dd = s2n(o.dd);
//						$("#fchdd").val(dd);
//						if(new Date().getDate() <=dd){
//							$("#fcdd").removeClass("alert-danger");
//							$("#fcdd").html(dd - new Date().getDate() +" day(s) left");
//						}else{
//							$("#fcdd").html(new Date().getDate() - dd +" day(s) over");
//							$("#fcdd").addClass("alert-danger");
//						}
					
//						$("#fcpd").val(o.pdStr);
				//sfd - student fee detail
					var doc = new jsPDF('p', 'pt', 'letter');
					var L = 20; var U = 20;
					var logo_url = serverContext+"resources/img/logos/ASL_logo.jpg";
					toDataURL(logo_url, function(dataUrl) {
						doc.addImage(dataUrl, 'JPEG', 10, 10, 1000, 500);
						doc.text("School's fee voucher", 430, 25);
						  var head = [["Summary"]];
						  doc.autoTable({head: head,startY: 80});
						  var head = [["Student", "Guardian", "Grade","Fee","V. Fee","Discount","Due Amount","Due Day"]];
//						  var sf = fvObj.object.sf;
						  var body = [[o.sn, o.gn, o.g,o.f,o.vf,o.d,o.da,o.dd]];
						  doc.autoTable({head: head, body: body, startY: 100});
						  var sfds = data.object.sfd;
						  if(sfds){
							  var head = [["Fee details since last payment"]];
							  doc.autoTable({head: head,startY: 145});
							  var head = [["Date", "Payer", "Payee","Fee","V. Fee","Dis.","O Payment","O Desc.","Due","Paid","Bal."]];
							  var body = [];
							  sfds.forEach(function(sfd,i){
									var row = [dateToDMY(new Date(sfd.pd)), sfd.p, sfd.rb,sfd.f,sfd.vf,sfd.d,sfd.od,sfd.odd,sfd.da,sfd.fp,sfd.db]
									body[i] = row;
								});
							  doc.autoTable({head: head, body: body, startY: 165});
						  }
						  doc.text("Instructions for GUARDIAN", 10, 220);
						  doc.text("NOTE:PLEASE IMMEDIATELY NOTIFY THE SCHOOL OF ANY CHANGES IN GIVEN CELL NO. FOR USE OF EMERGENCY / SMS ALERTS AND ETC.", 10, 235);
							  
//							  1. Fee is payable in advance EVERY MONTH and only cash
//							  payment will be accepted.
//							  2. Ensuring the timely receipt of fee voucher is the responsibility
//							  of parents AND shall NOT be considered AS an excuse.
//							  3. For RE-ISSUANCE of Fee Voucher, Rs. 20/- will be charged.
//							  4. Parents must retain their copy of the PAID fee voucher in safe
//							  custody for future reference.
//							  5. Summer vacation fee to be paid in advance as follows;
//							  FOR June together WITH January December / January Fee.
//							  FOR July together WITH January / February Fee.
//							  5. Fee once paid is not transferable and Non-Refundable.
//							  6. Fee will not be acceptable in installments.
//							  7. Fee will not be accepted without FEE VOUCHER.
//						  doc.output("dataurlnewwindow");
						  doc.autoPrint({variant: 'non-conform'});
						  doc.save("feeslip.pdf");
						})
					
					
					var l = data.object.sfd;
					if(l){
						fd(l);
					}	
					return false;
			}
			return false;
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
			 alert("Status : error : "+textStatus+" : "+errorThrown);
        }
	});
}

function printFc2(){
	if(!fvObj)
		return alert("No data available to print, Do a search first");
	
	var doc = new jsPDF('p', 'pt', 'letter');
	//doc.addImage(imgData, 'JPEG', 15, 40, 180, 160);
	
	var L = 20; var U = 20;
	//doc.setFontSize(14)
	//doc.text(L,U,'Summary')
	var logo_url = serverContext+"resources/ASL_logo.jpg";
	toDataURL(logo_url, function(dataUrl) {
		  console.log('RESULT:', dataUrl)
		//  doc.addImage(dataUrl, 'JPEG', 50, 20, 70, 70);
		  var head = [["Summary"]];
		  doc.autoTable({head: head,startY: 100});
		  var head = [["Student", "Guardian", "Grade","Fee","V. Fee","Discount","Due Amount","Due Day"]];
		  var sf = fvObj.object.sf;
		  var body = [[sf.sn, sf.gn, sf.g,sf.f,sf.vf,sf.d,sf.da,sf.dd]];
		  doc.autoTable({head: head, body: body, startY: 120});
		  var sfds = fvObj.object.sfd;
		  if(sfds){
			  var head = [["Fee details since last payment"]];
			  doc.autoTable({head: head,startY: 180});
			  var head = [["Date", "Payer", "Payee","Fee","V. Fee","Dis.","O Payment","O Desc.","Due","Paid","Bal."]];
			  var body = [];
			  sfds.forEach(function(sfd,i){
					var row = [dateToDMY(new Date(sfd.pd)), sfd.p, sfd.rb,sfd.f,sfd.vf,sfd.d,sfd.od,sfd.odd,sfd.da,sfd.fp,sfd.db]
					body[i] = row;
				});
			  doc.autoTable({head: head, body: body, startY: 200});
		  }
//		  doc.output("dataurlnewwindow");
		  doc.autoPrint({variant: 'non-conform'});
		  doc.save("feeslip.pdf");
		})

   // doc.output("dataurlnewwindow");
	
//	doc.save("file.pdf")
	
}