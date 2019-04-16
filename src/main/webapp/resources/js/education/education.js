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
var tableFeeReport;
	
$(document).ready(function() {
	
    tableFeeReport = $('#tableFeeReport').DataTable( {
        dom: 'Bfrtip',
        buttons: [
            { extend: 'copyHtml5', footer: true },
            { extend: 'csvHtml5', footer: true },
            { extend: 'excelHtml5', footer: true },
            { extend: 'print', footer: true },
        	{ extend: 'pdfHtml5',
              orientation: 'landscape',
              pageSize: 'LEGAL',
              footer: true
            }
        ],
	    
	    "footerCallback": function ( row, data, start, end, display ) {
	        var api = this.api(), data;
	
	        // Remove the formatting to get integer data for summation
	        var intVal = function ( i ) {
	            return typeof i === 'string' ?
	                i.replace(/[\$,]/g, '')*1 :
	                typeof i === 'number' ?
	                    i : 0;
	        };
	
	        // Total over all pages
	        feeTotal = api
	            .column( 4 )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        feePageTotal = api
	            .column( 4, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column( 4 ).footer() ).html(
	            feePageTotal +'/'+ feeTotal
	        );

	        // Total over all pages
	        otherTotal = api
	            .column( 5 )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        otherPageTotal = api
	            .column( 5, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column( 5 ).footer() ).html(
	            otherPageTotal +'/'+ otherTotal
	        );
	    

	        // Total over all pages
	        disTotal = api
	            .column(7)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        disPageTotal = api
	            .column(7, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(7).footer() ).html(
	        		disPageTotal +'/'+ disTotal
	        );

	        // Total over all pages
	        dueTotal = api
	            .column(11)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        duePageTotal = api
	            .column(11, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(11).footer() ).html(
	        		duePageTotal +'/'+ dueTotal
	        );
	    
	        // Total over all pages
	        paidTotal = api
	            .column(12)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        paidPageTotal = api
	            .column(12, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(12).footer() ).html(
	        		paidPageTotal +'/'+ paidTotal
	        );
	    
	        // Total over all pages
	        balTotal = api
	            .column(13)
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Total over this page
	        balPageTotal = api
	            .column(13, { page: 'current'} )
	            .data()
	            .reduce( function (a, b) {
	                return intVal(a) + intVal(b);
	            }, 0 );
	
	        // Update footer
	        $( api.column(13).footer() ).html(
	        		balPageTotal +'/'+ balTotal
	        );
	    
	        
	    }    
    } );
	
    
    $("table.display").DataTable( {
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
	$('.datePicker').val(currentFormattedDate());
	datatable = $("#table" + tableV).DataTable({
		dom: 'Bfrtip',
        buttons: [
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
		"autoWidth" : true,
		"order": [[ 0, "desc" ]],
        dom: 'Bfrtip',
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
					//don"t want to load ever DD for every row update on table
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
							"<div id=ownerMobile>"+obj.mobile+"</div>","<div id=ownerAddress>"+obj.address+"</div>",
							"<div id=ownerEmail>"+obj.email+"</div>", "<div id=ownerStatus>"+obj.status+"</div>",
							"<div id=ownerDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "School") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=schoolId>"+obj.id+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=schoolOwnerDD>"+obj.ownerNames+"</div>","<div id=schoolAddress>"+obj.address+"</div>",/*"<div id=schoolName>"+obj.name+"</div>",*/
							"<div id=schoolBranchName>"+obj.branchName+"</div>","<div id=schoolPhone>"+obj.phone+"</div>",
							"<div id=schoolEmail>"+obj.email+"</div>",
							"<div id=schoolStatus>"+obj.status+"</div>",
							"<div id=schoolDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Grade") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=gradeId>"+obj.id+"</div>", "<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
							"<div id=staffId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
							"<div id=guardianId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=guardianName>"+obj.name+"</div>", "<div id=guardianMobile>"+obj.mobile+"</div>", 
							"<div id=relationDD>"+obj.relation+"</div>", "<div id=guardianPermAddress>"+obj.permAddress+"</div>",
							"<div id=guardianEmail>"+obj.email+"</div>", "<div id=guardianPhone>"+obj.phone+"</div>", 
							"<div id=guardianCNIC>"+obj.cnic+"</div>", "<div id=guardianOccupation>"+obj.occupation+"</div>",
							"<div id=guardianStatus>"+obj.status+"</div>","<div id=guardianDated>"+obj.datedStr+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Student") {
					var i=0;
					var en;
					$.each(collections, function(ind, obj) {
						en = obj.enrollNo;
						++i;//Adding new enroll number
						arr = [
							"<div id=studentId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=studentEnrollNo>"+obj.enrollNo+"</div>","<div id=studentStatus>"+obj.status+"</div>","<div id=studentEnrollDate>"+obj.enrollDate+"</div>",
							"<div id=studentName>"+obj.name+"</div>","<div id=studentSchoolDD>"+obj.schoolName+"</div>",
							"<div id=studentGradeDD>"+obj.gradeName+"</div>","<div id=studentGuardianDD>"+obj.guardianName+"</div>",
							"<div id=studentGender>"+obj.gender+"</div>","<div id=studentFee>"+obj.fee+"</div>","<div id=studentFeeMode>"+obj.feeMode+"</div>",
							"<div id=studentMN>"+obj.mn+"</div>","<div id=studentBloodBroup>"+obj.bloodGroup+"</div>",
							"<div id=studentVehicleDD>"+obj.vehicleName+"</div>","<div id=studentvf>"+obj.vf+"</div>",
							"<div id=studentDateOfBirth>"+obj.dateOfBirth+"</div>","<div id=studentPOB>"+obj.pob+"</div>",
							"<div id=studentDiscountDD>"+obj.discountName+"</div>","<div id=studentND>"+obj.nd+"</div>",
							"<div id=studentDIDD>"+obj.di+"</div>","<div id=studentDueDay>"+obj.dueDay+"</div>",
							"<div id=studentMobile>"+obj.mobile+"</div>","<div id=studentWA>"+obj.wa+"</div>",
							"<div id=studentEmail>"+obj.email+"</div>","<div id=studentAddress>"+obj.address+"</div>",
							"<div id=studentDated>"+obj.updatedStr+"</div>",
							"<div id=studentYS>"+obj.ys+"</div>","<div id=studentYE>"+obj.ye+"</div>"
							];
						datatable.row.add(arr).draw();
					});
					$('#studentYE').val(currentFormattedNextYearDate());
					if(!isNaN(en))
						$('#studentEnrollNo').val(++i);
				} else if (getAll === "Subject") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=subjectId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
							"<div id=vehicleId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
							"<div id=discountId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
							"<div id=aId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
							"<div id=fcId>"+obj.id+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
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
	                console.log("jqXHR:");
	                console.log(jqXHR);
	                console.log("textStatus:");
	                console.log(textStatus);
	                console.log("errorThrown:");
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
    	$select.empty().append(data);//.selectpicker("refresh");
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
				removeTableBody();
				fvObj = data;
				if(!data || !data.object)
					return alert("Data not found.");
					
					var o = data.object.sf;
					if(!o)
						return alert("Invalid data");
					
					var dm=0;//due months
					
					$("#fcda").removeClass("alert-danger");
					if(o.lpd){
						var lpd = new Date(Date.parse(o.lpd));
						dm = monthDiff(lpd,new Date());
						if(dm <= 0 && o.db <=0){
							resetForm();
							return alert("Current month's Fee has been paid.");
						}else if(dm <= 0){//reset dues
							o.f = 0;
							o.vf = 0;
							o.d
							$("#fcda").addClass("alert-danger");
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
					if(o.dt=="%" && d>0){
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
        	window.location.href = serverContext + "login?message=" + errorThrown;
        }
	});
}

function fd(l){
	$("#fcDT").DataTable().clear().draw();
	l.forEach(function(obj,i){
		var t = $("#fcDT").DataTable();
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
    $(".fcDTC").empty();
    $("#fcDT").DataTable().clear().draw();
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
	xhr.open("GET", url);
	xhr.responseType = "blob";
	xhr.send();
}

function loadFR(){
	tableFeeReport.clear().draw();
	var formData = $("form").serialize();
	formData = formData.replace(/[^&]+=\.?(?:&|$)/g, "");

	$.ajax({
		type : "POST",
		url : serverContext + "loadFR",
		dataType : "json",
		data : formData,
		success : function(data) {
			if(data.status!=="SUCCESS"){
				return alert(data.status+" : "+data.message);
			}else{
				if(!data || !data.collection)
					return alert("Data not found.");

				var tfee=0;
				var tdis=0;
				var tod=0;
				var tdue=0;
				var tpaid=0;
				var bal=0;
				$.each(data.collection, function(ind, obj) {
					var o = obj.object;
					var objs = obj.collection;
					console.log(o);
					if(!o)
						return alert(data.status+" : "+data.message);
					
					var dm=0;//due months
					if(o.lpd){
						var lpd = new Date(Date.parse(o.lpd));
						dm = monthDiff(lpd,new Date());
						if(dm <= 0){//reset dues
							o.f = 0;
							o.vf = 0;
							o.d
						}
					}
					var vf = s2n(o.vf);
					var d  = s2n(o.d);
					var f = s2n(o.f);
					var tf = f;
					if(o.dt=="%")
						d = f * (d / 100);

					tf = f + vf - d;
					if(dm>1)
						tf = tf*dm;
					if(o.db)
						tf = tf+o.db;
					
					//again setting to get in print
					o.da=tf;
					o.d=d;
					o.dd = s2n(o.dd);
					if(objs && objs.length > 0){
						objs.forEach(function(sfd,i){
							var row = [o.scn, o.g,o.gn,o.sn,sfd.f,sfd.od,sfd.odd,sfd.d,sfd.p,sfd.rb,dateToDMY(new Date(sfd.pd)), sfd.da,sfd.fp,sfd.db]
							tfee+=sfd.f;
							tdis+=sfd.d;
							tod+=sfd.od;
							tdue+=sfd.da;
							tpaid+=sfd.fp;
							bal+=sfd.db;
							tableFeeReport.row.add(row).draw();
						});
					}
				});
				//var row = ["Totals"," "," "," "," "," "," ",tfee,tdis*ONE,tod*ONE,tdue*ONE,tdue*ONE,tpaid*ONE,bal*ONE];
				//tableFeeReport.row.add(row).draw();
			}
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
        	window.location.href = serverContext + "login?message=" + errorThrown;
        }
	});
}

function PFR(doc,o,sfds,logo_url,X,Y,dataUrl){
	var L = 10;
	var T = 10;
	//var doc = new jsPDF("p", "pt", "a4");
	console.log(L,T);
	doc.addImage(dataUrl, "JPEG", L, T, X, Y);
	L = L+390;//410
	T = T+25;//25
/*	console.log(L,T);
	var head = [["Summary"]];
	T = T+55;//80
	console.log(L,T);
	doc.autoTable({head: head,startY: T});
*/	
	//var head = [["Branch("+o.scn+")","Student("+o.en+")", "Guardian("+o.gid+")", "Grade("+o.grId+")"]];
//	var body = [[o.scn,o.sn, o.gn, o.g]];
	//T = T+20;//100
	//console.log(L,T);
//	doc.autoTable({head: head, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
	if(sfds && sfds.length > 0){
/*		var head = [["Fee details since last payment"]];
		T = T+45;//145
		console.log(L,T);
		doc.autoTable({head: head,startY: T});
*/		
		var head = [["Branch("+o.scn+")","Student("+o.en+")", "Guardian("+o.gid+")", "Grade("+o.grId+")","Date", "Payer", "Payee","Fee","Dis.","O Payment","O Desc.","Due","Paid","Bal."]];
		var body = [];
		sfds.forEach(function(sfd,i){
			var row = [o.scn,o.sn, o.gn, o.g,dateToDMY(new Date(sfd.pd)), sfd.p, sfd.rb,sfd.f,sfd.d,sfd.od,sfd.odd,sfd.da,sfd.fp,sfd.db]
			body[i] = row;
		});
		T = T+20;//165
		console.log(L,T);
		doc.autoTable({head: head, body: body, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
	}
	doc.setFontSize(16);
	L=40;
	T = T+70;
	console.log(L,T);

}

function loadFL(){
	var formData = $("form").serialize();
	formData = formData.replace(/[^&]+=\.?(?:&|$)/g, "");

	$.ajax({
		type : "POST",
		url : serverContext + "loadFL",
		dataType : "json",
		data : formData,
		success : function(data) {
			if(data.status!=="SUCCESS"){
				return alert(data.status+" : "+data.message);
			}else{
				if(!data || !data.collection)
					return alert("Data not found.");

				//iterate over list of map
				$.each(data.collection, function(ind, obj) {
					var o = obj.object;
					var sfd = obj.collection;
					console.log(o);
					if(!o)
						return alert(data.status+" : "+data.message);
					
					var FVL = [];
					var dm=0;//due months
					
					if(o.lpd){
						var lpd = new Date(Date.parse(o.lpd));
						dm = monthDiff(lpd,new Date());
						if(dm <= 0){//reset dues
							o.f = 0;
							o.vf = 0;
							o.d
						}
					}
					var vf = s2n(o.vf);
					var d  = s2n(o.d);
					var f = s2n(o.f);
					var tf = f;
					if(o.dt=="%")
						d = f * (d / 100);

					tf = f + vf - d;
					if(dm>1)
						tf = tf*dm;
					if(o.db)
						tf = tf+o.db;
					
					//again setting to get in print
					o.da=tf;
					o.d=d;
					o.dd = s2n(o.dd);

					if(o.userId == 61 || o.userId == 520){
						var logo_url = serverContext+"resources/img/logos/IQRA_logo.png";
						toDataURL(logo_url, function(dataUrl) {
							PFL(o,sfd,logo_url,30,35,dataUrl,getIqraInst());//print fee voucher
						});
					}else if(o.userId == 61  || o.userId == 823 || o.userId == 821){
							var logo_url = serverContext+"resources/img/logos/ASL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFL(o,sfd,logo_url,300,40,dataUrl,getASLInst());//print fee voucher
							});
					}else if(o.userId == 61 || o.userId == 203){
							var logo_url = serverContext+"resources/img/logos/TYL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFL(o,sfd,logo_url,150,40,dataUrl,getASLInst());//print fee voucher
							});
					}
				});
			}
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
        	window.location.href = serverContext + "login?message=" + errorThrown;
        }
	});
}

function PFL(o,sfds,logo_url,X,Y,dataUrl){
	var L = 40;
	var T = 10;
	var doc = new jsPDF("p", "pt", "a4");
	console.log(L,T);
	doc.addImage(dataUrl, "JPEG", L, T, X, Y);
	L = L+390;//410
	T = T+25;//25
/*	console.log(L,T);
	var head = [["Summary"]];
	T = T+55;//80
	console.log(L,T);
	doc.autoTable({head: head,startY: T});
*/	
	var head = [["Branch("+o.scn+")","Student("+o.en+")", "Guardian("+o.gid+")", "Grade("+o.grId+")"]];
//	var body = [[o.scn,o.sn, o.gn, o.g]];
	T = T+20;//100
	console.log(L,T);
	doc.autoTable({head: head, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
	if(sfds && sfds.length > 0){
/*		var head = [["Fee details since last payment"]];
		T = T+45;//145
		console.log(L,T);
		doc.autoTable({head: head,startY: T});
*/		
		var head = [["Date", "Payer", "Payee","Fee","Dis.","O Payment","O Desc.","Due","Paid","Bal."]];
		var body = [];
		sfds.forEach(function(sfd,i){
			var row = [dateToDMY(new Date(sfd.pd)), sfd.p, sfd.rb,sfd.f,sfd.d,sfd.od,sfd.odd,sfd.da,sfd.fp,sfd.db]
			body[i] = row;
		});
		T = T+20;//165
		console.log(L,T);
		doc.autoTable({head: head, body: body, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
	}
	doc.setFontSize(16);
	L=40;
	T = T+70;
	console.log(L,T);

	doc.autoPrint({variant: "non-confirm"});
	doc.save(o.sn+"("+o.en+") fee ledger.pdf");
	return;
}

function loadFV(){
	var formData = $("form").serialize();
	formData = formData.replace(/[^&]+=\.?(?:&|$)/g, "");

	$.ajax({
		type : "POST",
		url : serverContext + "loadFV",
		dataType : "json",
		data : formData,
		success : function(data) {
			if(data.status!=="SUCCESS"){
				return alert(data.status+" : "+data.message);
			}else{
				if(!data || !data.collection)
					return alert("Data not found.");

				//iterate over list of map
				$.each(data.collection, function(ind, obj) {
					var o = obj.object;
					var sfd = obj.collection;
					console.log(o);
					if(!o)
						return alert(data.status+" : "+data.message);
					
					var FVL = [];
					var dm=0;//due months
					
					if(o.lpd){
						var lpd = new Date(Date.parse(o.lpd));
						dm = monthDiff(lpd,new Date());
					}
					var vf = s2n(o.vf);
					var d  = s2n(o.d);
					var f = s2n(o.f);
					var tf = f;
					if(o.dt=="%")
						d = f * (d / 100);

					tf = f + vf - d;
					if(dm>1)
						tf = tf*dm;
					if(o.db)
						tf = tf+o.db;
					
					//again setting to get in print
					o.da=tf;
					o.d=d;
					o.dd = s2n(o.dd);

					if(o.userId == 601 || o.userId == 520){
						var logo_url = serverContext+"resources/img/logos/IQRA_logo.png";
						toDataURL(logo_url, function(dataUrl) {
							PFV_2Colum(o,logo_url,55,4,30,35,dataUrl,getIqraInst());//print fee voucher
						});
					}else if(o.userId == 601  || o.userId == 823 || o.userId == 821){
							var logo_url = serverContext+"resources/img/logos/ASL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFV_3Colum(o,logo_url,0,3,95,20,dataUrl,getASLInst());//print fee voucher
							});
					}else if(o.userId == 61 || o.userId == 203){
							var logo_url = serverContext+"resources/img/logos/TYL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFV_2Colum(o,logo_url,8,5,100,40,dataUrl,getASLInst());//print fee voucher
							});
					}
				});
			}
		},
		 error: function(data, textStatus, errorThrown) {
			resetForm();
        	window.location.href = serverContext + "login?message=" + errorThrown;
        }
	});
}

function PFV_3Colum(o,logo_url,X,Y,W,H,dataUrl,insts){
	var L = 3;
	var T = 5;
	var V = ["School","Guardian","Bank"]
	var doc = new jsPDF('landscape');
	for(i=0;i<V.length;i++){
		console.log(L,T);
		doc.addImage(dataUrl, "JPEG", L+X, Y, W, H);
//		doc.addImage(dataUrl, "JPEG", L, T, X, Y);
		T = T+25;//20
		console.log(L,T);
		doc.setFontSize(8);
		L = L+20;//25
		doc.text("Campus : "+o.scn, L, T);
		T = T+10;//40
//		doc.setFontSize();
		L = L-20;//5
		doc.text("Fee Voucher", L, T);
		L = L+70;//60
		doc.text(V[i]+" copy", L, T);
		T = T+6;//46
		doc.line(2, T, 300, T);
		L = L-70;//5
		T= T+3;//44
		doc.text("Issue date: "+dateToDMY(new Date()), L, T);
		L = L+32;//37
		doc.text("Valid date: "+dateToDMY(new Date()), L, T);
		L = L+32;//69
		doc.text("Due date: "+currentdateByDay(o.dd), L, T);
		T+=10;//54
		L =L-64;//5
		doc.text("Name: "+o.sn, L, T);
		L +=64;//69
		doc.text("GR/Enrol No: "+o.en, L, T);
		T+=1;//55
		doc.line(2, T, 300, T);
		T+=10;//54
		L =L-64;//5
		doc.text("Guardian: "+o.gn, L, T);
		L +=64;//69
		doc.text("Guardian No: "+o.gid, L, T);
		T+=1;//55
		doc.line(2, T, 300, T);
		T+=3;//57
		L =L-64;//5
		doc.text("Grade: "+o.g, L, T);
		L +=55;//66
		doc.text("Section: "+o.g, L, T);
		L +=9;//69
		
		T = T+13;//70
		doc.setFontSize(7);
		L =L-64;//5
		doc.text("Date: ", L, T);
		L =L+30;//35
		doc.text("Description: ", L, T);
		L =L+35;//70
		doc.text("Fee: ", L, T);

		T = T+1;//71
		doc.line(2, T, 300, T);
		T = T+3;//72
		L =L-65;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+30;//35
		doc.text("Monthly fee", L, T);
		L =L+35;//70
		doc.text(o.f+"", L, T);
		
		T = T+3;//75
		L =L-65;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+30;//35
		doc.text("Discount", L, T);
		L =L+35;//70
		doc.text(o.d+"", L, T);

		T = T+3;//76
		L =L-65;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+30;//35
		doc.text("Vehicle Fee", L, T);
		L =L+35;//70
		doc.text(o.vf+"", L, T);
		
		T = T+1;//78
		doc.line(2, T, 300, T);
		T = T+3;//80
		L =L-65;//5
		doc.text("Total", L, T);
		L =L+65;//35
		doc.text(o.da+"", L, T);

		T = T+20;//78
		L =L-66;//5
		doc.setFontSize(8);
		doc.text("Instuctions for Guardians", L, T);
		doc.setFontSize(7);
		
		T = T+4;//78
		insts.forEach(function(inst,i){
			T = T+4;//78
			doc.text(inst, L, T);
		});
		/*T = T+4;//78
		doc.text("1. For RE-ISSUANCE of Fee Voucher, Rs. 50/- will be charged", L, T);
		T = T+3;//78
		doc.text("2. Parents must retain their copy of the PAID fee voucher in safe custody", L, T);
		T = T+3;//78
		doc.text("  for future reference", L, T);
		T = T+3;//78
		doc.text("3. Fee once paid is not transferable and Non-Refundable", L, T);*/
		L =L+66;//35

//		L -=54;//5
//		doc.setLineWidth(1.5);
		/*var head = [["Summary"]];
		T = T+55;//80
		console.log(L,T);
		doc.autoTable({head: head,startY: T});
		var head = [["Student("+o.en+")", "Guardian("+o.gid+")", "Grade("+o.grId+")","Fee","Discount","Due Amount","Due Date"]];
		var body = [[o.sn, o.gn, o.g,o.f,o.d,o.da,currentdateByDay(o.dd)]];
		T = T+20;//100
		console.log(L,T);
		doc.autoTable({head: head, body: body, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
		if(sfds && sfds.length > 0){
			var head = [["Fee details since last payment"]];
			T = T+45;//145
			console.log(L,T);
			doc.autoTable({head: head,startY: T});
			var head = [["Date", "Payer", "Payee","Fee","Dis.","O Payment","O Desc.","Due","Paid","Bal."]];
			var body = [];
			sfds.forEach(function(sfd,i){
				var row = [dateToDMY(new Date(sfd.pd)), sfd.p, sfd.rb,sfd.f,sfd.d,sfd.od,sfd.odd,sfd.da,sfd.fp,sfd.db]
				body[i] = row;
			});
			T = T+20;//165
			console.log(L,T);
			doc.autoTable({head: head, body: body, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
		}*/
		
		L+=35;
		T = 5;
		console.log(L,T);
	}
	doc.line(100, 250, 100, 0);
	doc.line(200, 250, 200, 0);

	doc.autoPrint({variant: "non-conform"});
	doc.save(o.sn+"("+o.en+") fee voucher.pdf");
	return;
}

function PFV_2Colum(o,logo_url,X,Y,W,H,dataUrl,insts){
	var L = 3;
	var T = 5;
	var V = ["School","Guardian"]	
	var doc = new jsPDF('landscape');
	for(i=0;i<V.length;i++){
		console.log(L,T);
		doc.addImage(dataUrl, "JPEG", L+X, Y, W, H);
		T = T+40;//20
		console.log(L,T);
		doc.setFontSize(8);
		L = L+40;//25
		doc.text("Campus : "+o.scn, L, T);
		T = T+5;//40
		L = L-20;//5
		doc.text("Fee Voucher", L, T);
		L = L+75;//60
		doc.text(V[i]+" copy", L, T);
		T = T+8;//46
		doc.line(2, T, 300, T);
		L = L-75;//5
		T= T+3;//44
		doc.text("Issue date: "+dateToDMY(new Date()), L, T);
		L = L+42;//37
		doc.text("Valid date: "+dateToDMY(new Date()), L, T);
		L = L+42;//69
		doc.text("Due date: "+currentdateByDay(o.dd), L, T);
		T+=10;//54
		L =L-84;//5
		doc.text("Name: "+o.sn, L, T);
		L +=74;//69
		doc.text("GR/Enrol No: "+o.en, L, T);
		T+=1;//55
		doc.line(2, T, 300, T);
		T+=10;//54
		L =L-74;//5
		doc.text("Guardian: "+o.gn, L, T);
		L +=74;//69
		doc.text("Guardian No: "+o.gid, L, T);
		T+=1;//55
		doc.line(2, T, 300, T);
		T+=3;//57
		L =L-74;//5
		doc.text("Grade: "+o.g, L, T);
		L +=65;//66
		doc.text("Section: "+o.g, L, T);
		L +=9;//69
		
		T = T+13;//70
		doc.setFontSize(7);
		L =L-74;//5
		doc.text("Date: ", L, T);
		L =L+40;//35
		doc.text("Description: ", L, T);
		L =L+40;//70
		doc.text("Fee: ", L, T);

		T = T+1;//71
		doc.line(2, T, 300, T);
		T = T+3;//72
		L =L-80;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+40;//35
		doc.text("Monthly fee", L, T);
		L =L+40;//70
		doc.text(o.f+"", L, T);
		
		T = T+3;//75
		L =L-80;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+40;//35
		doc.text("Discount", L, T);
		L =L+40;//70
		doc.text(o.d+"", L, T);

		T = T+3;//76
		L =L-80;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+40;//35
		doc.text("Vehicle Fee", L, T);
		L =L+40;//70
		doc.text(o.vf+"", L, T);
		
		T = T+1;//78
		doc.line(2, T, 300, T);
		T = T+3;//80
		L =L-80;//5
		doc.text("Total", L, T);
		L =L+80;//35
		doc.text(o.da+"", L, T);

		T = T+20;//78
		L =L-80;//5
		doc.setFontSize(8);
		doc.text("Instuctions for Guardians", L, T);
		doc.setFontSize(7);
		
		T = T+4;//78
		insts.forEach(function(inst,i){
			T = T+3;//78
			doc.text(inst, L, T);
		});
		
		L =L+125;//35
		T = 5;
		console.log(L,T);
	}
	doc.line(150, 220, 150, 0);

	doc.autoPrint({variant: "non-conform"});
	doc.save(o.sn+"("+o.en+") fee voucher.pdf");
	return;
}

function getIqraInst(){
	var inst = [];
	inst.push("1. For RE-ISSUANCE of Fee Voucher, Rs. 50/- will be charged");
	inst.push("2. Parents must retain their copy of the PAID fee voucher in safe custody for future reference");
	inst.push("3. Fee once paid is not transferable and Non-Refundable");
	return inst;
}

function getASLInst(){
	var inst = [];
	inst.push("NOTE: PLEASE IMMEDIATELY NOTIFY THE SCHOOL OF ANY CHANGES IN");
	inst.push("   GIVEN CELL NO. FOR USE OF EMERGENCY / SMS ALERTS AND ETC.");
	inst.push("1. Fee is payable in advance EVERY MONTH and only cash payment will be accepted.");
	inst.push("2. Ensuring the timely receipt of fee voucher is the responsibility of parents AND");
	inst.push("   shall NOT be considered AS an excuse.");
	inst.push("3. For RE-ISSUANCE of Fee Voucher, Rs. 20/- will be charged.");
	inst.push("4. Parents must retain their copy of the PAID fee voucher in safe custody for future");
	inst.push("   reference.");
	inst.push("5. Summer vacation fee to be paid in advance as follows;");
	inst.push("   FOR June together WITH January December / January Fee.");
	inst.push("   FOR July together WITH January / February Fee.");
	inst.push("5. Fee once paid is not transferable and Non-Refundable.");
	inst.push("6. Fee will not be acceptable in installments.");
	inst.push("7. Fee will not be accepted without FEE VOUCHER.");
	return inst;
}

function PFV_back(o,sfds,V,logo_url,X,Y,dataUrl){
		var L = 40;
		var T = 10;
		var doc = new jsPDF("p", "pt", "a4");
		for(i=0;i<V.length;i++){
			console.log(L,T);
			doc.addImage(dataUrl, "JPEG", L, T, X, Y);
			L = L+390;//410
			T = T+15;//25
			console.log(L,T);
			doc.text(V[i]+" copy", L, T);
			var head = [["Summary"]];
			T = T+55;//80
			console.log(L,T);
			doc.autoTable({head: head,startY: T});
			var head = [["Student("+o.en+")", "Guardian("+o.gid+")", "Grade("+o.grId+")","Fee","Discount","Due Amount","Due Date"]];
			var body = [[o.sn, o.gn, o.g,o.f,o.d,o.da,currentdateByDay(o.dd)]];
			T = T+20;//100
			console.log(L,T);
			doc.autoTable({head: head, body: body, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
			if(sfds && sfds.length > 0){
				var head = [["Fee details since last payment"]];
				T = T+45;//145
				console.log(L,T);
				doc.autoTable({head: head,startY: T});
				var head = [["Date", "Payer", "Payee","Fee","Dis.","O Payment","O Desc.","Due","Paid","Bal."]];
				var body = [];
				sfds.forEach(function(sfd,i){
					var row = [dateToDMY(new Date(sfd.pd)), sfd.p, sfd.rb,sfd.f,sfd.d,sfd.od,sfd.odd,sfd.da,sfd.fp,sfd.db]
					body[i] = row;
				});
				T = T+20;//165
				console.log(L,T);
				doc.autoTable({head: head, body: body, startY: T,theme: "grid", columnStyles: {first_name: {fillColor: [41, 128, 185], textColor: 255, fontStyle: "bold"}}});
			}
			doc.setFontSize(16);
			L=40;
			T = T+70;
			console.log(L,T);
		}
		doc.autoPrint({variant: "non-conform"});
		doc.save(o.sn+"("+o.en+") fee voucher.pdf");
		return;
}

function printFc2(){
	if(!fvObj)
		return alert("No data available to print, Do a search first");
	
	var doc = new jsPDF("p", "pt", "letter");
	//doc.addImage(imgData, "JPEG", 15, 40, 180, 160);
	
	var L = 20; var U = 20;
	//doc.setFontSize(14)
	//doc.text(L,U,"Summary")
	var logo_url = serverContext+"resources/ASL_logo.jpg";
	toDataURL(logo_url, function(dataUrl) {
		  console.log("RESULT:", dataUrl)
		//  doc.addImage(dataUrl, "JPEG", 50, 20, 70, 70);
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
		  doc.autoPrint({variant: "non-conform"});
		  doc.save("feeslip.pdf");
		})

   // doc.output("dataurlnewwindow");
	
//	doc.save("file.pdf")
	
}

//create standard voucher
function CSV(url){
 let mywindow = window.open('', 'PRINT', 'height=650,width=900,top=100,left=150');

// mywindow.document.write(`<html><head><title>${title}</title>`);
 mywindow.document.write('</head><body >');
// $("#campusName").src = serverContext+"resources/ASL_logo.jpg";
 /*var ld = document.getElementById("logoDiv");
 var img = document.createElement("img");
 img.src = serverContext+"resources/ASL_logo.jpg";
 ld.appendChild(img);*/
 
 mywindow.document.write(document.getElementById("logoDiv").innerHTML);
 $("#campusName").empty().append(o.sn);
 $("#span").append(o.sn);
 mywindow.document.write(document.getElementById("vp").innerHTML);
 mywindow.document.write('</body></html>');

 mywindow.document.close(); // necessary for IE >= 10
 mywindow.focus(); // necessary for IE >= 10*/

 mywindow.print();
 mywindow.close();

 return true;
}
