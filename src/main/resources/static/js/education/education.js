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

	// The org switcher wires itself up (see /js/common/org-switcher.js).

	// P4 active-branch (school) switcher — hides itself for a single-branch school.
	loadMyBranches();
	$(document).on('change', '#branchSwitcher', switchBranch);

	// Attendance roster: populate the class dropdown + default the date to today.
	if ($("#attendanceGrade").length) {
		getUserGrades("a", "attendanceGrade");
		var _t = new Date();
		$("#aDate").val(_t.getFullYear() + "-" + ("0"+(_t.getMonth()+1)).slice(-2) + "-" + ("0"+_t.getDate()).slice(-2));
	}

	// Fee settings: load current org policy into the form.
	if ($("#fsPaymentMode").length) {
		loadFeeSetting();
	}

	// Alerts module (slice 16).
	if ($("#Alerts").length) {
		$("#addAlerts").off("click").on("click", submitAlert);
		$("#deleteAlerts").off("click").on("click", deleteSelectedAlerts);
		$("#sendAlerts").off("click").on("click", sendAlert);
	}
	if ($("#PA").length) {
		$("#sendPA").off("click").on("click", sendPublicAlert);
	}

    tableFeeReport = $('#tableFeeReport').DataTable( {
        dom: 'Bfrtip',
		lengthMenu: [
            [ 10, 25, 50, -1 ],
            [ '10 rows', '25 rows', '50 rows', 'Show all' ]
        ],
        buttons: [
        	'pageLength',
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
	
    
 /*   $("table.display").DataTable( {
        "paging":   false,
        "ordering": false,
        "info":     false,
        "searching": false
    } );*/
    
} );

function loadDataTable(){
	//check if data table exist destroy it
	if (datatable!=null){
		datatable.destroy();
	}
	$('.datePicker').val(currentFormattedDate());
	datatable = $("#table" + tableV).DataTable({
		dom: 'Bfrtip',
		lengthMenu: [
            [ 10, 25, 50, -1 ],
            [ '10 rows', '25 rows', '50 rows', 'Show all' ]
        ],
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
				
				if(!data || !data.collection)
					return; 
				
				var collections = data.collection;
				console.log("getAll : "+getAll+" collections : "+collections);
				var arr = [" No Data Found "];
				if (getAll === "Owner") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=ownerId>"+escHtml(obj.id)+"</div>",
							"<input type='checkbox' value='"+ obj.id+ "' id='abc'>","<div id=ownerName>"+escHtml(obj.name)+"</div>", 
							"<div id=ownerMobile>"+escHtml(obj.mobile)+"</div>","<div id=ownerAddress>"+escHtml(obj.address)+"</div>",
							"<div id=ownerEmail>"+escHtml(obj.email)+"</div>", "<div id=ownerStatus>"+escHtml(obj.status)+"</div>",
							"<div id=ownerDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "School") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=schoolId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value='"+ obj.id+ "' id='"+ obj.id+ "'>",
							"<div id=schoolOwnerDD>"+escHtml(obj.ownerNames)+"</div>","<div id=schoolAddress>"+escHtml(obj.address)+"</div>",/*"<div id=schoolName>"+escHtml(obj.name)+"</div>",*/
							"<div id=schoolBranchName>"+escHtml(obj.branchName)+"</div>","<div id=schoolPhone>"+escHtml(obj.phone)+"</div>",
							"<div id=schoolEmail>"+escHtml(obj.email)+"</div>",
							"<div id=schoolStatus>"+escHtml(obj.status)+"</div>",
							"<div id=schoolDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Grade") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=gradeId>"+escHtml(obj.id)+"</div>", "<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=gradeSchoolDD>"+escHtml(obj.schoolName)+"</div>","<div id=gradeName>"+escHtml(obj.name)+"</div>", 
							"<div id=gradeCode>"+escHtml(obj.code)+"</div>","<div id=gradeFee>"+escHtml(obj.fee)+"</div>",
							"<div id=gradeTimeFromStr>"+escHtml(obj.timeFromStr)+"</div>", 
							"<div id=gradeTimeToStr>"+escHtml(obj.timeToStr)+"</div>", "<div id=gradeRoom>"+escHtml(obj.room)+"</div>",
							"<div id=gradeStatus>"+escHtml(obj.status)+"</div>","<div id=gradeRoomDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Staff") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=staffId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=staffName>"+escHtml(obj.name)+"</div>", "<div id=staffEmail>"+escHtml(obj.email)+"</div>",
							"<div id=staffMobile>"+escHtml(obj.mobile)+"</div>", "<div id=staffPhone>"+escHtml(obj.phone)+"</div>", 
							"<div id=staffTimeInStr>"+escHtml(obj.timeInStr)+"</div>", "<div id=staffTimeOutStr>"+escHtml(obj.timeOutStr)+"</div>",
							"<div id=staffDesignation>"+escHtml(obj.designation)+"</div>", "<div id=staffQualification>"+escHtml(obj.qualification)+"</div>",
							/*"<div id=staffSchoolDD>"+escHtml(obj.schoolNames)+"</div>",*/ 
							"<div id=staffGradeDD>"+escHtml(obj.gradeNames)+"</div>", "<div id=staffGender>"+escHtml(obj.gender)+"</div>",
							"<div id=staffDOB>"+escHtml(obj.staffDOB)+"</div>","<div id=staffMartialStatus>"+escHtml(obj.martialStatus)+"</div>",
							 "<div id=staffAddress>"+escHtml(obj.address)+"</div>","<div id=staffStatus>"+escHtml(obj.status)+"</div>",
							 "<div id=staffDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Guardian") {
					var i=0;
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=guardianId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=guardianName>"+escHtml(obj.name)+"</div>", "<div id=guardianMobile>"+escHtml(obj.mobile)+"</div>", 
							"<div id=relationDD>"+escHtml(obj.relation)+"</div>", "<div id=guardianPermAddress>"+escHtml(obj.permAddress)+"</div>",
							"<div id=guardianEmail>"+escHtml(obj.email)+"</div>", "<div id=guardianPhone>"+escHtml(obj.phone)+"</div>", 
							"<div id=guardianCNIC>"+escHtml(obj.cnic)+"</div>", "<div id=guardianOccupation>"+escHtml(obj.occupation)+"</div>",
							"<div id=guardianStatus>"+escHtml(obj.status)+"</div>","<div id=guardianDated>"+escHtml(obj.datedStr)+"</div>"
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
							"<div id=studentId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=studentEnrollNo>"+escHtml(obj.enrollNo)+"</div>","<div id=studentStatus>"+escHtml(obj.status)+"</div>","<div id=studentEnrollDate>"+escHtml(obj.enrollDate)+"</div>",
							// Contact-360 rides in the name cell (no new column): this student's roles across modules.
						"<div id=studentName>"+escHtml(obj.name)+contact360Button(obj.partyId)+"</div>","<div id=studentSchoolDD>"+escHtml(obj.schoolName)+"</div>",
							"<div id=studentGradeDD>"+escHtml(obj.gradeName)+"</div>","<div id=studentGuardianDD>"+escHtml(obj.guardianName)+"</div>",
							"<div id=studentGender>"+escHtml(obj.gender)+"</div>","<div id=studentFee>"+escHtml(obj.fee)+"</div>","<div id=studentFeeMode>"+escHtml(obj.feeMode)+"</div>",
							"<div id=studentMN>"+escHtml(obj.mn)+"</div>","<div id=studentBloodBroup>"+escHtml(obj.bloodGroup)+"</div>",
							"<div id=studentVehicleDD>"+escHtml(obj.vehicleName)+"</div>","<div id=studentvf>"+escHtml(obj.vf)+"</div>",
							"<div id=studentDateOfBirth>"+escHtml(obj.dateOfBirth)+"</div>","<div id=studentPOB>"+escHtml(obj.pob)+"</div>",
							"<div id=studentDiscountDD>"+escHtml(obj.discountName)+"</div>","<div id=studentND>"+escHtml(obj.nd)+"</div>",
							"<div id=studentDIDD>"+escHtml(obj.di)+"</div>","<div id=studentDueDay>"+escHtml(obj.dueDay)+"</div>",
							"<div id=studentMobile>"+escHtml(obj.mobile)+"</div>","<div id=studentWA>"+escHtml(obj.wa)+"</div>",
							"<div id=studentEmail>"+escHtml(obj.email)+"</div>","<div id=studentAddress>"+escHtml(obj.address)+"</div>",
							"<div id=studentDated>"+escHtml(obj.updatedStr)+"</div>",
							"<div id=studentYS>"+escHtml(obj.ys)+"</div>","<div id=studentYE>"+escHtml(obj.ye)+"</div>"
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
							"<div id=subjectId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=subjectGradeDD>"+escHtml(obj.gradeName)+"</div>",
							"<div id=subjectName>"+escHtml(obj.name)+"</div>", "<div id=subjectCode>"+escHtml(obj.code)+"</div>",
							"<div id=subjectPublisher>"+escHtml(obj.publisher)+"</div>", "<div id=subjectEdition>"+escHtml(obj.edition)+"</div>", 
							"<div id=subjectStatus>"+escHtml(obj.status)+"</div>","<div id=subjectDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Vehicle") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=vehicleId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=vehicleSchoolDD>"+escHtml(obj.schoolName)+"</div>","<div id=vehicleName>"+escHtml(obj.name)+"</div>",
							"<div id=vehicleNumber>"+escHtml(obj.number)+"</div>", "<div id=vehicleDriverName>"+escHtml(obj.driverName)+"</div>",
							"<div id=vehicleDriverMobile>"+escHtml(obj.driverMobile)+"</div>", "<div id=vehicleOwnerName>"+escHtml(obj.ownerName)+"</div>", 
							"<div id=vehicleOwnerMobile>"+escHtml(obj.ownerMobile)+"</div>", "<div id=vehicleStatus>"+escHtml(obj.status)+"</div>", 
							"<div id=vehicleDated>"+escHtml(obj.datedStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Discount") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							"<div id=discountId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=discountNameDD>"+escHtml(obj.name)+"</div>","<div id=discountTypeDD>"+escHtml(obj.di)+"</div>", 
							"<div id=discountAmount>"+escHtml(obj.amount)+"</div>","<div id=discountDescription>"+escHtml(obj.description)+"</div>",
							"<div id=discountStatus>"+escHtml(obj.status)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Alerts") {
					$.each(collections, function(ind, obj) {
						i++;
						arr = [
							// Display-only cells: NO id attributes. Every one of these names (acdd, atdd, ah, am, as …) is
							// also a FORM field id on this page, so emitting them per row created duplicate ids —
							// getElementById then returned the table cell instead of the form control once a row rendered.
							"<div>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div>"+escHtml(obj.consumers)+"</div>","<div>"+escHtml(obj.alertType)+"</div>",
							"<div>"+escHtml(obj.deliveryChannel)+"</div>","<div>"+escHtml(obj.deliveryPeriod)+"</div>",
							"<div>"+escHtml(obj.deliveryType)+"</div>","<div>"+escHtml(obj.status)+"</div>",
							"<div>"+escHtml(obj.startDateStr)+"</div>", "<div>"+escHtml(obj.endDateStr)+"</div>",
							"<div>"+escHtml(obj.heading)+"</div>","<div>"+escHtml(obj.message)+"</div>",
							"<div>"+escHtml(obj.signature)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "Fc") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=fcId>"+escHtml(obj.id)+"</div>","<input type='checkbox' value="+ obj.id+ " id="+ obj.id+ ">",
							"<div id=inputFc>"+escHtml(obj.enrollNo)+"</div>","<div id=fcpd>"+escHtml(obj.pdStr)+"</div>",
							"<div id=fcp>"+escHtml(obj.payee)+"</div>","<div id=fcrb>"+escHtml(obj.receivedBy)+"</div>",
							"<div id=fchf>"+escHtml(obj.fee)+"</div>","<div id=fchvf>"+escHtml(obj.vehicleFee)+"</div>",
							"<div id=fchd>"+escHtml(obj.discount)+"</div>","<div id=fchdt>"+escHtml(obj.discountType)+"</div>",
							"<div id=fcod>"+escHtml(obj.otherDues)+"</div>","<div id=fcodd>"+escHtml(obj.otherDuesDescription)+"</div>",
							"<div id=fchda>"+escHtml(obj.dueAmount)+"</div>","<div id=fcfp>"+escHtml(obj.feePaid)+"</div>", 
							"<div id=fcdb>"+escHtml(obj.dueBalance)+"</div>","<div id=fchdd>"+escHtml(obj.dueDayOfMonth)+"</div>", 
							"<div id=fcri>"+escHtml(obj.receivedIn)+"</div>","<div id=fccn>"+escHtml(obj.checkNo)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "A") {
					$.each(collections, function(ind, obj) {
						arr = [
							"<div id=aId>"+escHtml(obj.id)+"</div>",
							"<div id=aen>"+escHtml(obj.en)+"</div>","<div id=asn>"+escHtml(obj.sn)+"</div>","<div id=fcdb>"+escHtml(obj.gn)+"</div>",
							"<div id=fchdt>"+escHtml(obj.dtStr)+"</div>"
							];
						datatable.row.add(arr).draw();
					});
				} else if (getAll === "PA") {
					$.each(collections, function(ind, obj) {
						arr = [
							escHtml(obj.id),escHtml(obj.datedStr),escHtml(obj.channel),escHtml(obj.target),escHtml(obj.status)
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

// The active-organization (tenant) switcher now lives in /js/common/org-switcher.js — one implementation,
// shared with the commerce dashboard (which previously had none, pinning multi-org users to a single tenant).

// ---- P4: active-branch (school) switcher ----
// The active branch lives in the JWT and is what new students/grades are filed under, so switching means
// getting a fresh token and reloading. It reuses the vertical-agnostic /switchStore endpoint: auth resolves
// the location module from the caller's own userType, so a school id goes through the same door as a store id.
function loadMyBranches() {
	$.get(serverContext + "getMySchools", function(resp) {
		var rows = (resp && (resp.collection || resp.data)) || [];
		if (rows.length < 2) { $("#branchSwitcherLi").hide(); return; }
		var $s = $("#branchSwitcher").empty();
		if (!rows.some(function(b) { return b.active; })) {
			$s.append($("<option>").val("").text("Select a branch…"));
		}
		rows.forEach(function(b) {
			var $o = $("<option>").val(b.id).text(b.branchName || b.name || ("Branch " + b.id));
			if (b.active) { $o.prop("selected", true); }
			$s.append($o);
		});
		$("#branchSwitcherLi").show();
	}, "json").fail(function() { $("#branchSwitcherLi").hide(); });
}

function switchBranch() {
	var schoolId = $("#branchSwitcher").val();
	if (!schoolId) { return; }
	$.ajax({
		url: serverContext + "switchStore", type: "POST", contentType: "application/json",
		data: JSON.stringify({ storeId: Number(schoolId) }), dataType: "json",
		success: function(res) {
			if (res && res.status === "SUCCESS") { window.location.reload(); }
			else { alert((res && res.message) || "Could not switch branch."); loadMyBranches(); }
		},
		error: function() { alert("Could not switch branch."); loadMyBranches(); }
	});
}

// ---- Attendance: class-roster marking (slice 13) ----
function aDateStr() {
	var v = $("#aDate").val();
	var d = v ? new Date(v) : new Date();
	return ("0"+d.getDate()).slice(-2) + "-" + ("0"+(d.getMonth()+1)).slice(-2) + "-" + d.getFullYear();
}

function aRosterRow(r) {
	var en = escHtml(r.enrollNo == null ? "" : r.enrollNo);
	var status = r.status || "Present";
	function opt(v) { return "<option value='"+v+"'"+(status===v?" selected":"")+">"+v+"</option>"; }
	return "<tr data-enroll='"+en+"'>"
		+ "<td>"+en+"</td>"
		+ "<td>"+escHtml(r.studentName == null ? "" : r.studentName)+"</td>"
		+ "<td><select class='form-control aStatus'>"+opt("Present")+opt("Absent")+opt("Late")+"</select></td>"
		+ "<td><input type='time' class='form-control aIn' value='"+escHtml(r.timeInStr||"")+"'></td>"
		+ "<td><input type='time' class='form-control aOut' value='"+escHtml(r.timeOutStr||"")+"'></td>"
		+ "<td><input type='text' class='form-control aRem' value='"+escHtml(r.remark||"")+"'></td>"
		+ "</tr>";
}

function loadClassRoster() {
	var gradeId = $("#attendanceGrade").val();
	if (!gradeId) { alert("Please select a class"); return; }
	$.get(serverContext + "getClassRoster?gradeId=" + encodeURIComponent(gradeId)
			+ "&dateStr=" + encodeURIComponent(aDateStr()), function(res) {
		var $body = $("#aRosterBody").empty();
		if (!res || res.status !== "SUCCESS" || !res.collection || res.collection.length === 0) {
			$("#aRosterWrap").hide();
			$("#aRosterEmpty").show().text(res && res.message ? res.message : "No students found for this class.");
			return;
		}
		$.each(res.collection, function(i, r) { $body.append(aRosterRow(r)); });
		$("#aRosterEmpty").hide();
		$("#aRosterWrap").show();
	}).fail(function() { alert("Could not load roster"); });
}

function aMarkAll(status) {
	$("#aRosterBody .aStatus").val(status);
}

function saveAttendance() {
	var rows = [];
	$("#aRosterBody tr").each(function() {
		var $t = $(this);
		rows.push({
			enrollNo: $t.attr("data-enroll"),
			status: $t.find(".aStatus").val(),
			timeInStr: $t.find(".aIn").val(),
			timeOutStr: $t.find(".aOut").val(),
			remark: $t.find(".aRem").val()
		});
	});
	if (rows.length === 0) { alert("Nothing to save"); return; }
	$.ajax({
		url: serverContext + "markAttendanceBulk",
		type: "POST",
		contentType: "application/json",
		data: JSON.stringify({ gradeId: $("#attendanceGrade").val(), dateStr: aDateStr(), rows: rows }),
		success: function(res) {
			alert(res && res.message ? res.message : (res && res.status === "SUCCESS" ? "Saved" : "Save failed"));
		},
		error: function() { alert("Save failed"); }
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

/**
 * Populates a class dropdown.
 *
 * The id is <table>GradeDD by convention (staffGradeDD, studentGradeDD, subjectGradeDD). `targetId`
 * overrides that for dropdowns named after what they ARE rather than by the prefix — the attendance
 * roster's #attendanceGrade. Without this the id was built by string concatenation, so renaming that
 * one element silently left the dropdown empty with no error anywhere.
 *
 * The selector is resolved ONCE: the original built it three times, and only two of them lowercased
 * `table`, so a mixed-case caller would have shown "Please wait…" into one element and the results
 * into another.
 */
function getUserGrades(table, targetId) {
	var sel = "#" + (targetId || (String(table).toLowerCase() + "GradeDD"));
	$(sel).empty().append("<option value = ''> Please wait....  </option>");
	$.get(serverContext + "getUserGrades", function (data) {
		$(sel).empty().append(data);
	})
	.fail(function (data) {
		$(sel).empty().append("<option value = ''> System error  </option>");
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
							o.d = 0;
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
			"<div id=fcdpd>"+dateToDMY(new Date(obj.paymentDate))+"</div>","<div id=fcdp>"+escHtml(obj.payee)+"</div>","<div id=fcdri>"+escHtml(obj.receivedBy)+"</div>",
			"<div id=fcdhf>"+escHtml(obj.fee)+"</div>","<div id=fcdhvf>"+escHtml(obj.vehicleFee)+"</div>","<div id=fcdhd>"+escHtml(obj.discount)+"</div>",
			"<div id=fcdod>"+escHtml(obj.otherDues)+"</div>","<div id=fcdodd>"+escHtml(obj.otherDuesDescription)+"</div>","<div id=fcdhda>"+escHtml(obj.dueAmount)+"</div>",
			"<div id=fcdfp>"+escHtml(obj.feePaid)+"</div>", "<div id=fcddb>"+escHtml(obj.dueBalance)+"</div>",
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
	if(!v || v===" ")
		return;
	
	for (var i in sm) {
		if(!i && i===v){
			$(this).callAjax("markAttendance",sm[i]);
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
    });
	
}

// getImgFromUrl is a SHARED helper defined in main.js — education duplicate removed for DRY.

/*function toDataURL(url, callback) {
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
}*/

function loadFR(){
	tableFeeReport.clear().draw();
	
//	var formData = $("form").serialize();
//	formData = formData.replace(/[^&]+=\.?(?:&|$)/g, "");
	formFields++;

	$.ajax({
		type : "POST",
		url : serverContext + "loadFR",
		dataType : "json",
		data : populateFormData(),
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
	//var formData = $("form").serialize();
	//formData = formData.replace(/[^&]+=\.?(?:&|$)/g, "");
	//this form has only one button

	$.ajax({
		type : "POST",
		url : serverContext + "loadFL",
		dataType : "json",
		data : populateFormData(),
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

					if(o.userId == 601 || o.userId == 520){
						var logo_url = serverContext+"resources/img/logos/IQRA_logo.png";
						toDataURL(logo_url, function(dataUrl) {
							PFL(o,sfd,logo_url,30,35,dataUrl,getIqraInst());//print fee voucher
						});
					}else if(o.userId == 61  || o.userId == 823 || o.userId == 821){
							var logo_url = serverContext+"resources/img/logos/ASL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFL(o,sfd,logo_url,300,40,dataUrl,getASLInst());//print fee voucher
							});
					}else if(o.userId == 601 || o.userId == 203){
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
		debugger;
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
	validateForm();
//	var formData = $("form").serialize();
//	formData = formData.replace(/[^&]+=\.?(?:&|$)/g, "");

	$.ajax({
		type : "POST",
		url : serverContext + "loadFV",
		dataType : "json",
		data : populateFormData(),
		success : function(data) {
			if(data.status!=="SUCCESS"){
				return alert(data.status+" : "+data.message);
			}else{
				if(!data || !data.collection)
					return alert("Data not found.");

				//iterate over list of map
//				$.each(data.collection, function(ind, obj) {
					var o = data.collection[0].object;
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
					if(o.userId == 1426 || o.userId == 16 || o.userId == 1829){
						var logo_url = serverContext+"resources/img/logos/ll_logo.jpg";
						toDataURL(logo_url, function(dataUrl) {
							PFV_3ColumBy3(data.collection,logo_url,3,6,15,15,dataUrl,getLLInst());//print fee voucher//PFV_1by4
						});
					}else if(o.userId == 601 || o.userId == 241 || o.userId == 520){
						var logo_url = serverContext+"resources/img/logos/IQRA_logo.png";
						toDataURL(logo_url, function(dataUrl) {
							PFV_2Colum(o,logo_url,55,4,30,35,dataUrl,getIqraInst());//print fee voucher
						});
					}else if(o.userId == 601  || o.userId == 823 || o.userId == 821){
							var logo_url = serverContext+"resources/img/logos/ASL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFV_3Colum(o,logo_url,0,3,95,20,dataUrl,getASLInst());//print fee voucher
							});
					}else if(o.userId == 601 || o.userId == 203){
							var logo_url = serverContext+"resources/img/logos/TYL_logo.jpg";
							toDataURL(logo_url, function(dataUrl) {
								PFV_2Colum(o,logo_url,8,5,100,40,dataUrl,getASLInst());//print fee voucher
							});
					}
//				});
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
		T = T+4;//72
		L =L-65;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+30;//35
		doc.text("Monthly fee", L, T);
		L =L+35;//70
		doc.text(o.f+"", L, T);
		
		T = T+4;//75
		L =L-65;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+30;//35
		doc.text("Discount", L, T);
		L =L+35;//70
		doc.text(o.d+"", L, T);

		T = T+4;//76
		L =L-65;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+30;//35
		doc.text("Vehicle Fee", L, T);
		L =L+35;//70
		doc.text(o.vf+"", L, T);
		
		T = T+1;//78
		doc.line(2, T, 300, T);
		T = T+4;//80
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
		doc.setFontSize(9);
		doc.setFont("arial");
		doc.setFontType('bold');
//		L = L+40;//25
		doc.text("Campus : IQRA TALEEM-O-TARBIAT-UL-ATFAL", L, T);
		T = T+5;//40
//		L = L+20;//5
		doc.setFontSize(7);
		doc.setFont("arial");
		doc.text("Fee voucher : "+V[i]+" copy", L, T);
//		L = L-75;//60
//		doc.text(V[i]+" copy", L, T);
		T = T+8;//46
		doc.text("Issue date: "+dateToDMY(new Date()), L, T);
		L = L+42;//37
		doc.text("Valid date: "+dateToDMY(new Date()), L, T);
		L = L+40;//69
		doc.text("Due date: "+currentdateByDay(o.dd), L, T);
//		L = L+75;//5
		T= T+1;//44
		doc.setFontType('normal');
		doc.line(2, T, 300, T);
		doc.setFontType('bold');
		T+=3;//54
		L =L-82;//5
		doc.setFontSize(9);
		doc.setFont("arial");
		doc.text("Guardian: "+o.gn, L, T);
		L +=82;//69
//		T+=10;//54
		doc.text("Guardian No: "+o.gid, L, T);
		T+=1;//55
//		doc.line(2, T, 300, T);
		T+=10;//54
		L =L-82;//5
		doc.text("Name: "+o.sn, L, T);
		L +=82;//69
		doc.text("GR/Enrol No: "+o.en, L, T);
		T+=1;//55
		doc.setFontType('normal');
		doc.line(2, T, 300, T);
		doc.setFontType('bold');
		T+=3;//57
		L =L-82;//5
		doc.text("Grade: "+o.g, L, T);
		L +=82;//66
		doc.text("Section: "+o.g, L, T);
		L +=9;//69
		
		T = T+13;//70
		doc.setFontSize(7);
		L =L-91;//5
		doc.setFontSize(9);
		doc.text("Date: ", L, T);
		L =L+40;//35
		doc.text("Description: ", L, T);
		L =L+42;//70
		doc.text("Fee: ", L, T);

		doc.setFontSize(8);
		T = T+1;//71
		doc.line(2, T, 300, T);
		T = T+3;//72
		L =L-82;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+40;//35
		doc.text("Monthly fee", L, T);
		L =L+42;//70
		doc.text(o.f+"", L, T);
		
		T = T+3;//75
		L =L-82;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+40;//35
		doc.text("Discount", L, T);
		L =L+42;//70
		doc.text(o.d+"", L, T);

		T = T+3;//76
		L =L-82;//5
		doc.text(getMonthYear(new Date())+"", L, T);
		L =L+40;//35
		doc.text("Vehicle Fee", L, T);
		L =L+42;//70
		doc.text(o.vf+"", L, T);
		
		T = T+1;//78
		doc.setFontType('normal');
		doc.line(2, T, 300, T);
		doc.setFontType('bold');
		T = T+3;//80
		L =L-82;//5
		doc.setFontSize(9);
		doc.text("Total", L, T);
		L =L+82;//35
		doc.text(o.da+"", L, T);

		T = T+20;//78
		L =L-80;//5
		doc.setFontSize(10);
		doc.text("Instuctions for Guardians", L, T);
		doc.setFontSize(7);
		
		T = T+4;//78
		insts.forEach(function(inst,i){
			T = T+3;//78
			doc.text(inst, L, T);
		});
		
		L =L+152;//35
		T = 5;
		console.log(L,T);
	}
	doc.line(150, 220, 150, 0);

	doc.autoPrint({variant: "non-conform"});
	doc.save(o.sn+"("+o.en+") fee voucher.pdf");
	return;
}

function PFV_3ColumBy3(collection,logo_url,X,Y,W,H,dataUrl,insts){
	var monthOption = $("#dateRangeDDFV")[0].selectedOptions[0].value;
	
//	var doc = new jsPDF('landscape');
	var orientation = "landscape"//,portrait
	var unit = "mm"//,mm,cm,in
	var format = "a4" //"a3", "a4" (default), "a5", "letter", "legal".
	var doc = new jsPDF(orientation, unit, format);
//	var doc = new jsPDF("p", "pt", "a4");
	var total = collection.length;
	var payableAfterDue= 500;
	var payableAfterValidity = 1000;
	if(collection.length>3){
		total = total/3;
	}if(total >1 && total/2 !=0){
		total++;
	}
	for(var i=0; i<total;i++){

		var L = 15;
		var n = 0;
		doc.line(L-3, 250, L-3, 6);
		var xLineStart = 15;
		var xLineEnd = 93;
//		Sorting on enrolled No in ASC order
		collection.sort(function(a, b) {
			  return a.object.en - b.object.en;
		});
		
		while(collection.length>0){
			//check if month range is valid
			var firstYear = $("#fvsd")[0].value; 
			var lastYear = $("#fved")[0].value;
			if(monthOption ==="1"){
				var parts =firstYear.split('-');
				firstYear = new Date(parts[1], parts[0] - 1, 1); 
				firstYear = Date.parse(firstYear);
				
				parts =lastYear.split('-');
				lastYear = new Date(parts[1], parts[0] - 1, 1); 
				lastYear = Date.parse(lastYear);
				
				if(Date.compare(firstYear, lastYear) > 0){
					alert("Fist Month can not be greater than last month");
					return false;
				}
			}
								
			var T = 7;
			var o = collection[0].object;
			var sfd = obj.collection;
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
			
			doc.addImage(dataUrl, "JPEG", L, Y, W, H);
			doc.setFontSize(13);
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			L = L+18;//25
			T = T+3;//40
			doc.text("Learning Links School System", L, T);
			doc.setFontSize(7);
			T = T+5;//40
			doc.text("C-5, Block D, North Nazimabad, Karachi, Pakistan-74700", L, T);
			
			L = L-18;//25
			T = T+20;//40
			doc.setFontSize(12);
			var pageXEnd = 270;
			var pageXstart = 15;
			doc.line(xLineStart, T, xLineEnd, T);
			T = T+4;//40
			doc.text("Fee Challan ", L, T);
			T = T+1.5;//40
			doc.line(xLineStart, T, xLineEnd, T);
			T = T+10;//40
			doc.setFontSize(10);
			doc.text("Issue date", L, T);
			T+=4;//54
			doc.setFontType('normal');
			var issueDate = $("#fIssueDate")[0].value;
			doc.text(issueDate, L, T);
			L = L+28;//40
			T-=4;//54
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			doc.text("Due date", L, T);
			T = T+4;//37
			doc.setFontType('normal');
			var dueDate = $("#fDueDate")[0].value;
			doc.text(dueDate, L, T);		
			T = T-4;//40
			L = L+27;//69
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			doc.text("Valid date", L, T);
			T = T+4;//37
			doc.setFontType('normal');		
			var validDate = $("#fValidDate")[0].value;
			doc.text(validDate, L, T);		
			T= T+10;//44
			var pageLeftRight = 55;
			L =L-pageLeftRight;//5
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			doc.text("Student Name", L, T);
			L +=pageLeftRight;
			doc.text("G.R.No", L, T);
			T+=1;//pageLeftRight
			T+=3.5;//54
			L =L-pageLeftRight;//5
			doc.setFontType('normal');
			doc.text(o.sn, L, T);
			L +=pageLeftRight;//69
			doc.text(o.en, L, T);
			T+=1;//pageLeftRight
			T+=10;//57
			L =L-pageLeftRight;//5
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			doc.text("Class ", L, T);
			L +=pageLeftRight;//66
			doc.text("Section ", L, T);
			T+=1;//pageLeftRight
			T+=3.5;//57
			L =L-pageLeftRight;//5
			doc.setFontType('normal');
			doc.text(o.g, L, T);
			L +=pageLeftRight;//66
			doc.text(o.g, L, T);
			T+=1;//pageLeftRight			
			T = T+15;//70
			L =L-pageLeftRight;//5
			doc.setFontSize(11);
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			T+=3.5;//57
			doc.text("Date ", L, T);
			L =L+28;//35
			doc.text("Description ", L, T);
			L =L+27;//70
			doc.text("Fee ", L, T);
			doc.setFontSize(10);
			doc.setFontType('normal');
			doc.setFont('Comic Sans');
			T = T+1;//71
			doc.line(xLineStart, T, xLineEnd, T);
			T = T+3.5;//72
			L =L-pageLeftRight;//5
			var totalFee = 0;
			if(monthOption==="0"){
				var month =getMonthYear(new Date());
				doc.text(month, L, T);
				L =L+28;//35
				doc.text("Monthly fee", L, T);
				L =L+27;//70
				doc.text(o.f+"", L, T);
				totalFee +=o.f;
				T = T+1;//78
				T = T+3.5;//72
				L =L-pageLeftRight;//5
			}else{
				if(monthOption==="2"){
					var selectedYear = $("#fvYearDD :selected").val();
					$("#fvMonthsDD option:selected").each(function() {
						var month = $(this).val();
						if(month === "0")
							return;
						
						var date = new Date(selectedYear, month - 1, 1);
						var month =getMonthYear(date);
						console.log(month);
						doc.text(month, L, T);
						L =L+28;//35
						doc.text("Monthly fee", L, T);
						L =L+27;//70
						doc.text(o.f+"", L, T);
						totalFee +=o.f;
						T = T+1;//78
						T = T+3.5;//72
						L =L-pageLeftRight;//5
					});
				}else{
					var monthDiff = DateDiff.inMonths(firstYear,lastYear);
					for(var m=0;m<=monthDiff;m++){
						var month =getMonthYear(firstYear);
						console.log(month);
						doc.text(month, L, T);
						L =L+28;//35
						doc.text("Monthly fee", L, T);
						L =L+27;//70
						doc.text(o.f+"", L, T);
						totalFee +=o.f;
						T = T+1;//78
						T = T+3.5;//72
						L =L-pageLeftRight;//5
						firstYear = Date.addMonths(firstYear,1);
					}
				}
			}

			var others = $("#fOthers").val();
			var charges = $("#fCharges").val()*ONE;
			if ((others !== undefined && others !== '') || (charges !== undefined && charges !== '')) {
				T = T+2;//78
				doc.text('---', L, T);
				L =L+28;//35
				doc.text(others, L, T);
				L =L+27;//70
				doc.text(charges+"", L, T);
				if (charges !== undefined && charges !== '') {
					totalFee = totalFee + charges;
				}
				T = T+1;//78
				T = T+3.5;//72
				L =L-pageLeftRight;//5
			}
			
			doc.text("Arrears", L, T);
			var manualArear = getManualArrears(o.en);
			if(manualArear === undefined)
				manualArear = 0;
			
			o.db = o.db+manualArear;
			if(o.db && o.db >0){
				L =L+pageLeftRight;
				doc.text(o.db+"", L, T);
				L =L-pageLeftRight;
			}
			totalFee = totalFee + o.db;
			T = T+2;//78
			doc.line(xLineStart, T, xLineEnd, T);
			T = T+3.5;//80
			doc.text("Payable before due date", L, T);
			L =L+pageLeftRight;//35
			doc.text(totalFee+"", L, T);


			T = T+4;//80
			L =L-pageLeftRight	;//5
			doc.text("Payable after due date("+payableAfterDue+")", L, T);
			L =L+pageLeftRight;//35
			doc.text((totalFee+payableAfterDue)+"", L, T);

			T = T+4;//80
			L =L-pageLeftRight	;//5
			doc.text("Payable after valid date("+payableAfterValidity+")", L, T);
			L =L+pageLeftRight;//35
			doc.text((totalFee+payableAfterValidity)+"", L, T);

//			T = T+45;//78
			
			var width = doc.internal.pageSize.width;
			var height = doc.internal.pageSize.height;
			T = height - 25;
			L =L-pageLeftRight;//5
			doc.setFontSize(12);
			doc.setFontType('bold');
			doc.text("Instuctions for Guardians", L, T);
			doc.setFontSize(8);
			T = T+1;//78
			insts.forEach(function(inst,i){
				T = T+4;//78
				doc.text(inst, L, T);
			});

//			T = T+30;//80
//			doc.text("Design and developed by maxtheservice", L, T);
//			T = T+4;//78
//			doc.text("Web:  https://maxtheservice.com/login", L, T);
//			T = T+4;//78
//			doc.text("Tel: 03114499660", L, T);
		
			L+=89;
//			T = 5;

			doc.line(L-8, 250, L-8, 4);

			n++;
			collection.splice(0,1);
			if(n==3){
				break;
			}else{
				doc.line(L-3, 250, L-3, 4);				
			}
			xLineStart =xLineEnd+ 11;
			xLineEnd += 89;
			
		}
		if(collection.length > 0){
			doc.addPage();//doc.addPage(i+1);
		}
	}
	doc.autoPrint({variant: "non-conform"});
	window.open(doc.output('bloburl'), '_blank');
	doc.save("LL_Fee Vouchers.pdf");
	return;
}

function getManualArrears(en){
	var arear=0;
	arrears.forEach(function(obj){
		if(obj.en == en){
			arear = obj[en];
			return;
		}
	});	
	return arear;
}
/*
function PFV_3ColumBy3_backup3(collection,logo_url,X,Y,W,H,dataUrl,insts){
//	var V = ["School","Guardian","Bank"]
	var doc = new jsPDF('landscape');
	var total = collection.length;
	if(collection.length>3)
		total = total/3;
	if(total/2 !=0)
		total++;
	for(var i=0; i<total;i++){
		var L = 3;
		var T = 5;
		var n = 0;
		while(collection.length>0){
			var o = collection[0].object;
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
			
			console.log(L,T);
			doc.addImage(dataUrl, "JPEG", L, Y, W, H);
//			T = T+25;//20
			doc.setFontSize(14);
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			L = L+18;//25
			T = T+3;//40
			doc.text("Learning Links School System", L, T);
			doc.setFontSize(8);
//			doc.setFont("Sans-serif");
			T = T+5;//40
			doc.text("C-5, Block D, North Nazimabad, Karachi, Pakistan-74700", L, T);
			
			L = L-18;//25
			T = T+20;//40
			doc.setFontSize(12);
			doc.line(2, T, 300, T);
			T = T+4;//40
			doc.text("Fee Challan ", L, T);
			T = T+1.5;//40
			doc.line(2, T, 300, T);
			T = T+5;//40
//			doc.line(2, T, 300, T);
			T = T+3.5;//40
			doc.setFontSize(10);
			doc.setFontType('normal');
			doc.text("Issue date: ", L, T);
			L = L+64;//37
			doc.text(dateToDMY(new Date()), L, T);
			T = T+1;//40
//			doc.line(2, T, 300, T);
			T+=3.5;//54
			L = L-64;//37
			doc.text("Due date: ", L, T);
			L = L+64;//37
			doc.text(currentdateByDay(o.dd==0?10:odd), L, T);
			T = T+1;//40
//			doc.line(2, T, 300, T);
			T+=3.5;//54
			L = L-64;//69
			doc.text("Valid date: ", L, T);
			L = L+64;//37
			doc.text(dateToDMY(Date.today().clearTime().moveToLastDayOfMonth()), L, T);
			T+=1;//pageLeftRight
//			doc.line(2, T, 300, T);
			T= T+8;//44
//			doc.line(2, T, 300, T);
//			doc.setFontType('bold');
			T+=3.5;//54
			L =L-64;//5
//			doc.setFontSize(9);
			doc.setFont('Comic Sans');
			doc.text("Student Name: ", L, T);
			L +=64;
			doc.text(o.sn, L, T);
			T+=1;//pageLeftRight
//			doc.line(2, T, 300, T);
			T+=3.5;//54
			L =L-64;//5
			doc.text("GR No: ", L, T);
			L +=64;//69
			doc.text(o.en, L, T);
			T+=1;//pageLeftRight
//			doc.line(2, T, 300, T);
			T+=3.5;//57
			L =L-64;//5
			doc.text("Class: ", L, T);
			L +=64;//66
			doc.text(o.g, L, T);
			T+=1;//pageLeftRight
//			doc.line(2, T, 300, T);
			T+=3.5;//57
			L =L-64;//5
			doc.text("Section: ", L, T);
			L +=64;//66
			doc.text(o.g, L, T);
			T+=1;//pageLeftRight
//			doc.line(2, T, 300, T);
			L +=13;//69
			
			T = T+8;//70
			L =L-77;//5
			doc.setFontSize(11);
			doc.setFontType('bold');
//			doc.line(2, T, 300, T);
			T+=3.5;//57
			doc.text("Date ", L, T);
			L =L+35;//35
			doc.text("Description ", L, T);
			L =L+33;//70
			doc.text("Fee ", L, T);
			//calculate selected months fee
			doc.setFontSize(10);
			doc.setFontType('normal');
			doc.setFont('Comic Sans');
			T = T+1;//71
			doc.line(2, T, 300, T);
			T = T+3.5;//72
			L =L-68;//5
			doc.text(getMonthYear(new Date())+"", L, T);
			L =L+35;//35
			doc.text("Monthly fee", L, T);
			L =L+33;//70
			doc.text(o.f+"", L, T);
			T = T+1;//78
//			doc.line(2, T, 300, T);
			
			T = T+3.5;//72
			L =L-68;//5
			doc.text(getNextMonthYear(new Date())+"", L, T);
			L =L+35;//35
			doc.text("Monthly fee", L, T);
			L =L+33;//70
			doc.text(o.f+"", L, T);
			T = T+1;//78
//			doc.line(2, T, 300, T);

			T = T+3.5;//75
			L =L-68;//5
//			doc.text(getMonthYear(new Date())+"", L, T);
//			L =L+35;//35
			doc.text("Arrears", L, T);
//			L =L+68;//70
			if(o.db && o.db >0){
				L =L+68;
				doc.text(o.db+"", L, T);
				L =L-68;
			}
			T = T+2;//78
			doc.line(2, T, 300, T);
//			L =L-68;//5
//			T = T+1;//78
//			doc.line(2, T, 300, T);
			T = T+3.5;//80
//			L =L-33	;//5
			doc.text("Payable before due date", L, T);
			L =L+68;//35
			doc.text(o.da*2+"", L, T);

			T = T+4;//80
			L =L-68	;//5
			doc.text("Payable after due date", L, T);
			L =L+68;//35
			doc.text((o.da*2+500)+"", L, T);

			T = T+4;//80
			L =L-68	;//5
			doc.text("Payable after expiry date", L, T);
			L =L+68;//35
			doc.text((o.da*2+1500)+"", L, T);

			T = T+60;//78
			L =L-68;//5
			doc.setFontSize(12);
			doc.setFontType('bold');
			doc.text("Instuctions for Guardians", L, T);
			doc.setFontSize(8);
			
			T = T+2;//78
			insts.forEach(function(inst,i){
				T = T+4;//78
				doc.text(inst, L, T);
			});

			T = T+30;//80
			doc.text("Design and developed by maxtheservice", L, T);
			T = T+4;//78
			doc.text("Web:  https://maxtheservice.com/login", L, T);
			T = T+4;//78
			doc.text("Tel: 03114499660", L, T);
			
			L+=99;
			T = 5;
			doc.line(100, 250, 99, 0);
			doc.line(200, 250, 198, 0);
			n++;
			collection.splice(0,1);
			if(n==3){
				break;
			}
		}
		if(collection.length > 0){
			doc.addPage();//doc.addPage(i+1);
		}
	}
	doc.autoPrint({variant: "non-conform"});
	window.open(doc.output('bloburl'), '_blank');
	doc.save("LL_Fee Vouchers.pdf");
	return;
}


function PFV_3ColumBy3_backuup2(collection,logo_url,X,Y,W,H,dataUrl,insts){
//	var V = ["School","Guardian","Bank"]
	var doc = new jsPDF('landscape');
	var total = collection.length;
	if(collection.length>3)
		total = total/3;
	if(total/2 !=0)
		total++;
	for(var i=0; i<total;i++){
		var L = 3;
		var T = 5;
		var n = 0;
		while(collection.length>0){
			var o = collection[0].object;
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
			
			console.log(L,T);
			doc.addImage(dataUrl, "JPEG", L, Y, W, H);
//			T = T+25;//20
			doc.setFontSize(14);
			doc.setFont('Comic Sans');
			doc.setFontType('bold');
			L = L+18;//25
			T = T+3;//40
			doc.text("Learning Links School System", L, T);
			doc.setFontSize(8);
//			doc.setFont("Sans-serif");
			T = T+4;//40
			doc.text("C-5, Block D, North Nazimabad, Karachi, Pakistan-74700", L, T);
			
			L = L-18;//25
			T = T+25;//40
			doc.setFontSize(12);
			doc.text("Fee Challan ", L, T);
			T = T+5;//40
			doc.line(2, T, 300, T);
			T = T+3.5;//40
			doc.setFontSize(10);
			doc.setFontType('normal');
			doc.text("Issue date: ", L, T);
			L = L+64;//37
			doc.text(dateToDMY(new Date()), L, T);
			T = T+1;//40
			doc.line(2, T, 300, T);
			T+=3.5;//54
			L = L-64;//37
			doc.text("Due date: ", L, T);
			L = L+64;//37
			doc.text(currentdateByDay(o.dd==0?10:odd), L, T);
			T = T+1;//40
			doc.line(2, T, 300, T);
			T+=3.5;//54
			L = L-64;//69
			doc.text("Valid date: ", L, T);
			L = L+64;//37
			doc.text(dateToDMY(Date.today().clearTime().moveToLastDayOfMonth()), L, T);
			T+=1;//pageLeftRight
			doc.line(2, T, 300, T);
			T= T+8;//44
			doc.line(2, T, 300, T);
//			doc.setFontType('bold');
			T+=3.5;//54
			L =L-64;//5
//			doc.setFontSize(9);
			doc.setFont('Comic Sans');
			doc.text("Student Name: ", L, T);
			L +=64;
			doc.text(o.sn, L, T);
			T+=1;//pageLeftRight
			doc.line(2, T, 300, T);
			T+=3.5;//54
			L =L-64;//5
			doc.text("GR No: ", L, T);
			L +=64;//69
			doc.text(o.en, L, T);
			T+=1;//pageLeftRight
			doc.line(2, T, 300, T);
			T+=3.5;//57
			L =L-64;//5
			doc.text("Class: ", L, T);
			L +=64;//66
			doc.text(o.g, L, T);
			T+=1;//55
			doc.line(2, T, 300, T);
			T+=3.5;//57
			L =L-64;//5
			doc.text("Section: ", L, T);
			L +=64;//66
			doc.text(o.g, L, T);
			T+=1;//55
			doc.line(2, T, 300, T);
			L +=13;//69
			
			T = T+8;//70
			L =L-77;//5
			doc.setFontSize(11);
			doc.setFontType('bold');
//			doc.line(2, T, 300, T);
			T+=3.5;//57
			doc.text("Date ", L, T);
			L =L+35;//35
			doc.text("Description ", L, T);
			L =L+33;//70
			doc.text("Fee ", L, T);
			//calculate selected months fee
			doc.setFontSize(10);
			doc.setFontType('normal');
			doc.setFont('Comic Sans');
			T = T+1;//71
			doc.line(2, T, 300, T);
			T = T+3.5;//72
			L =L-68;//5
			doc.text(getMonthYear(new Date())+"", L, T);
			L =L+35;//35
			doc.text("Monthly fee", L, T);
			L =L+33;//70
			doc.text(o.f+"", L, T);
			T = T+1;//78
//			doc.line(2, T, 300, T);
			
			T = T+3.5;//72
			L =L-68;//5
			doc.text(getNextMonthYear(new Date())+"", L, T);
			L =L+35;//35
			doc.text("Monthly fee", L, T);
			L =L+33;//70
			doc.text(o.f+"", L, T);
			T = T+1;//78
//			doc.line(2, T, 300, T);

			T = T+3.5;//75
			L =L-68;//5
//			doc.text(getMonthYear(new Date())+"", L, T);
//			L =L+35;//35
			doc.text("Arrears", L, T);
//			L =L+68;//70
			if(o.db && o.db >0){
				L =L+68;
				doc.text(o.db+"", L, T);
				L =L-68;
			}
			T = T+2;//78
			doc.line(2, T, 300, T);
//			L =L-68;//5
//			T = T+1;//78
//			doc.line(2, T, 300, T);
			T = T+3.5;//80
//			L =L-33	;//5
			doc.text("Payable before due date", L, T);
			L =L+68;//35
			doc.text(o.da*2+"", L, T);

			T = T+4;//80
			L =L-68	;//5
			doc.text("Payable after due date", L, T);
			L =L+68;//35
			doc.text((o.da*2+500)+"", L, T);

			T = T+4;//80
			L =L-68	;//5
			doc.text("Payable after expiry date", L, T);
			L =L+68;//35
			doc.text((o.da*2+1500)+"", L, T);

			T = T+60;//78
			L =L-68;//5
			doc.setFontSize(12);
			doc.setFontType('bold');
			doc.text("Instuctions for Guardians", L, T);
			doc.setFontSize(8);
			
			T = T+2;//78
			insts.forEach(function(inst,i){
				T = T+4;//78
				doc.text(inst, L, T);
			});

			T = T+30;//80
			doc.text("Design and developed by maxtheservice", L, T);
			T = T+4;//78
			doc.text("Web:  https://maxtheservice.com/login", L, T);
			T = T+4;//78
			doc.text("Tel: 03114499660", L, T);
			
			L+=99;
			T = 5;
			doc.line(100, 250, 99, 0);
			doc.line(200, 250, 198, 0);
			n++;
			collection.splice(0,1);
			if(n==3){
				break;
			}
		}
		if(collection.length > 0){
			doc.addPage();//doc.addPage(i+1);
		}
	}
	doc.autoPrint({variant: "non-conform"});
	window.open(doc.output('bloburl'), '_blank');
	doc.save("LL_Fee Vouchers.pdf");
	return;
}


function PFV_3ColumBy3_backup(collection,logo_url,X,Y,W,H,dataUrl,insts){
//	var V = ["School","Guardian","Bank"]
	var doc = new jsPDF('landscape');
	var total = collection.length;
	if(collection.length>3)
		total = total/3;
	if(total/2 !=0)
		total++;
	for(var i=0; i<total;i++){
		var L = 3;
		var T = 5;
		var n = 0;
		while(collection.length>0){
			var o = collection[0].object;
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
			
			console.log(L,T);
			doc.addImage(dataUrl, "JPEG", L, Y, W, H);
//			T = T+25;//20
			doc.setFontSize(11);
			doc.setFont("arial");
			doc.setFontType('bold');
			L = L+18;//25
			T = T+2;//40
			doc.text("Learning Links School System", L, T);
			doc.setFontSize(8);
			doc.setFont("Sans-serif");
			T = T+8;//40
			doc.text("C-5, Block D, North Nazimabad, Karachi, Pakistan-74700", L, T);
			
			L = L-18;//25
			T = T+25;//40
			doc.setFontSize(10);
			doc.text("Fee Challan ", L, T);
			T = T+1;//40
			doc.line(2, T, 300, T);
			T = T+4;//40
			doc.setFontSize(7);
			doc.text("Issue date: "+dateToDMY(new Date()), L, T);
			L = L+35;//37
			doc.text("Due date: "+currentdateByDay(o.dd==0?10:odd), L, T);
			L = L+33;//69
			doc.text("Valid date: "+dateToDMY(Date.today().clearTime().moveToLastDayOfMonth()), L, T);
			T= T+10;//44
			doc.line(2, T, 300, T);
			doc.setFontType('bold');
			T+=3;//54
			L =L-68;//5
			doc.setFontSize(9);
			doc.setFont('Comic Sans');
			doc.text("Name: "+o.sn, L, T);
			L +=68;//69
			doc.text("GR No: "+o.en, L, T);
			T+=1;//55
			doc.line(2, T, 300, T);
			doc.setFontType('bold');
			T+=3;//57
			L =L-68;//5
			doc.text("Grade: "+o.g, L, T);
			L +=68;//66
			doc.text("Section: "+o.g, L, T);
			L +=9;//69
			
			T = T+15;//70
			doc.setFontSize(7);
			L =L-77;//5
			doc.setFontSize(9);
			doc.text("Date ", L, T);
			L =L+35;//35
			doc.text("Description ", L, T);
			L =L+33;//70
			doc.text("Fee ", L, T);

			doc.setFontSize(8);
			T = T+1;//71
			doc.line(2, T, 300, T);
			T = T+4;//72
			L =L-68;//5
			doc.text(getMonthYear(new Date())+"", L, T);
			L =L+35;//35
			doc.text("Monthly fee", L, T);
			L =L+33;//70
			doc.text(o.f+"", L, T);
			
			T = T+4;//75
			L =L-68;//5
			doc.text(getMonthYear(new Date())+"", L, T);
			L =L+35;//35
			doc.text("Arrears", L, T);
			L =L+33;//70
			doc.text(0+"", L, T);
			L =L-35;//5
			T = T+1;//78
			doc.line(2, T, 300, T);
			T = T+4;//80
			L =L-33	;//5
			doc.text("Payable before due date", L, T);
			L =L+68;//35
			doc.text(o.da+"", L, T);

			T = T+4;//80
			L =L-68	;//5
			doc.text("Payable after due date", L, T);
			L =L+68;//35
			doc.text((o.da+500)+"", L, T);

			T = T+10;//78
			L =L-68;//5
			doc.setFontSize(10);
			doc.setFontType('italic');
			doc.text("Instuctions for Guardians", L, T);
			doc.setFontSize(8);
			
			T = T+2;//78
			insts.forEach(function(inst,i){
				T = T+4;//78
				doc.text(inst, L, T);
			});
			
			L+=99;
			T = 5;
			doc.line(100, 250, 99, 0);
			doc.line(200, 250, 198, 0);
			n++;
			collection.splice(0,1);
			if(n==3){
				break;
			}
		}
		if(collection.length > 0){
			doc.addPage();//doc.addPage(i+1);
		}
	}
	doc.autoPrint({variant: "non-conform"});
	doc.save("LL_Fee Vouchers.pdf");
	return;
}
*/

function getLLInst(){
	var inst = [];
	inst.push("1. For RE-ISSUANCE of Fee Voucher, Rs. 50/- will be charged");
	inst.push("2. Parents must retain their copy of the PAID fee voucher in safe");
	inst.push("   custody for future reference");
	inst.push("3. Fee once paid is not transferable and Non-Refundable");
	inst.push("4. At the expiry of the validity of voucher Rs.1000 will be charged");
	return inst;
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
/*
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
*/
/*
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
	
}*/
/*
//create standard voucher
function CSV(url){
 let mywindow = window.open('', 'PRINT', 'height=650,width=900,top=100,left=150');
 mywindow.document.write('</head><body >');
 
 mywindow.document.write(document.getElementById("logoDiv").innerHTML);
 $("#campusName").empty().append(o.sn);
 $("#span").append(o.sn);
 mywindow.document.write(document.getElementById("vp").innerHTML);
 mywindow.document.write('</body></html>');

 mywindow.document.close(); // necessary for IE >= 10
 mywindow.focus(); // necessary for IE >= 10*/
/*
 mywindow.print();
 mywindow.close();

 return true;
}*/

function loadFVIBSDD(element,destinationId){
//	var lable  = $(element)[0].selectedOptions[0].text;
	var value  = $(element)[0].selectedOptions[0].value;
	$("#arrearsDiv").hide();
	if(!value || value == '')
		return false;
	if(value === "Students")
		$("#arrearsDiv").show();
	
	loadBSDD("getUser"+value.trim(),destinationId);
	
}

/*

var arrears = [];
function populateArrearsDiv(element){
	arrears = [];
	$("#arrearsDiv").empty();
	var span ="<p class='h5 text-info'>Please specify arrears below for individuals</p>";
	var table = span+"<table id='tableArrears'>";
	var tr ="<tr role='row'>";
	var n = 0
	$("#"+element.id+" option:selected").each(function() {
		var en = $(this).text().split("-")[1];
		var obj = {en:en};
		arrears.push(obj);

		n++;
		var td ="<td>";
		td += "<label class='control-label'>"+$(this).text()+"</label>";
		td += "<div>";
		td += "<input id='arrear_"+en+"' type='text' onkeyup=populateArrearsMap('"+en+"') class='form-control' placeholder=' Arrears '>";
		td += "</div>";
		td +="</td>"
		tr +=td;
		if(n==4){
			tr +="</tr>";
			table+=tr;
			n=0;
			tr ="<tr role='row'>";
		}
			
	});
	tr +="</tr>";
	table+=tr;
	table += "</table></div>"
	$("#arrearsDiv").append(table);
	
}
*/
var arrears = [];
function populateArrearsDiv(id){
	//if option is exclude selected
	arrears = [];
	$("#arrearsDiv").empty();
	if($("#incl_excl_selected").val() == 'exclude'){
		return;
	}

	var span ="<p class='h5 text-info'>Please specify arrears below for individuals</p>";
	var table = span+"<table id='tableArrears'>";
	var tr ="<tr role='row'>";
	var n = 0
	$("#"+id+" option:selected").each(function(ind,selected) {
		var en = selected.text.split("~")[1].trim();
		var obj = {en:en};
		arrears.push(obj);

		n++;
		var td ="<td>";
		td += "<label class='control-label'>"+selected.text+"</label>";
		td += "<div>";
		td += "<input id='arrear_"+en+"' type='number' onkeyup=populateArrearsMap('"+en+"') class='form-control' placeholder=' Arrears '>";
		td += "</div>";
		td +="</td>"
		tr +=td;
		if(n==4){
			tr +="</tr>";
			table+=tr;
			n=0;
			tr ="<tr role='row'>";
		}
			
	});
	tr +="</tr>";
	table+=tr;
	table += "</table></div>"
	$("#arrearsDiv").append(table);
	
}

function populateArrearsMap(en){
	var arrear = $("#arrear_"+en).val()*ONE;
	if(!arrear && arrear.length < 0)
		return;
	
	arrears.forEach(function(obj){
		if(obj.en == en)
			obj[en] = arrear;
	});
}

// ===== Slice 14: Fee report / ledger / voucher / settings (clean rebuild; overrides legacy) =====
function dmy(v){
	if(!v) return "";
	var d = new Date(v);
	return ("0"+d.getDate()).slice(-2) + "-" + ("0"+(d.getMonth()+1)).slice(-2) + "-" + d.getFullYear();
}

function loadFR(){
	var data = {
		by: $("#frScope").val(),
		id: $("#frId").val(),
		fromStr: dmy($("#frFrom").val()),
		toStr: dmy($("#frTo").val())
	};
	$.post(serverContext + "loadFR", data, function(res){
		var $b = $("#frBody").empty();
		$("#frTotals").empty();
		if(!res || res.status !== "SUCCESS" || !res.collection){
			return alert(res && res.message ? res.message : "No fee records");
		}
		res.collection.forEach(function(r){
			$b.append("<tr><td>"+escHtml(r.enrollNo||"")+"</td><td>"+escHtml(r.studentName||"")
				+"</td><td>"+escHtml(r.gradeName||"")+"</td><td>"+escHtml(r.schoolName||"")
				+"</td><td>"+escHtml(r.paymentDateStr||"")+"</td><td>"+escHtml(r.payee||"")
				+"</td><td>"+escHtml(r.receivedBy||"")+"</td><td>"+r.fee+"</td><td>"+r.discount
				+"</td><td>"+r.otherDues+"</td><td>"+r.dueAmount+"</td><td>"+r.feePaid+"</td><td>"+r.balance+"</td></tr>");
		});
		var t = res.object || {};
		$("#frTotals").html("<th colspan='7'>Totals ("+(t.count||0)+")</th><th>"+(t.fee||0)+"</th><th>"
			+(t.discount||0)+"</th><th>"+(t.otherDues||0)+"</th><th>"+(t.dueAmount||0)+"</th><th>"
			+(t.feePaid||0)+"</th><th>"+(t.balance||0)+"</th>");
	}).fail(function(){ alert("Could not load report"); });
}

function loadFV(){
	var en = $("#fvEnroll").val();
	if(!en){ return alert("Enter a student enroll no"); }
	$.get(serverContext + "loadFV?enrollNo=" + encodeURIComponent(en), function(res){
		var $v = $("#fvVoucher");
		if(!res || res.status !== "SUCCESS" || !res.object){
			$v.hide();
			return alert(res && res.message ? res.message : "Voucher not found");
		}
		var o = res.object;
		$v.html(
			"<div class='panel panel-default' style='max-width:480px'>"
			+ "<div class='panel-heading'><b>Fee Voucher</b></div>"
			+ "<div class='panel-body'>"
			+ "<p><b>Student:</b> "+escHtml(o.studentName||"")+" ("+escHtml(o.enrollNo||"")+")</p>"
			+ "<p><b>Class:</b> "+escHtml(o.gradeName||"")+" &nbsp; <b>Campus:</b> "+escHtml(o.schoolName||"")+"</p>"
			+ "<p><b>Guardian:</b> "+escHtml(o.guardianName||"")+"</p><hr/>"
			+ "<p>Monthly due: <b>"+o.monthlyDue+"</b></p>"
			+ "<p>Due months: <b>"+o.dueMonths+"</b></p>"
			+ "<p>Previous balance: <b>"+o.previousBalance+"</b></p>"
			+ "<h4>Total payable: <b>"+o.totalDue+"</b></h4>"
			+ "<button class='btn btn-default btn-sm' onclick='window.print()'><span class='glyphicon glyphicon-print'></span> Print</button>"
			+ "</div></div>"
		).show();
		$("#flLedgerWrap").hide();
	}).fail(function(){ alert("Could not load voucher"); });
}

function loadFL(){
	var en = $("#fvEnroll").val();
	if(!en){ return alert("Enter a student enroll no"); }
	$.get(serverContext + "loadFL?enrollNo=" + encodeURIComponent(en), function(res){
		var $b = $("#flBody").empty();
		if(!res || res.status !== "SUCCESS" || !res.collection){
			$("#flLedgerWrap").hide();
			return alert(res && res.message ? res.message : "No fee records");
		}
		var h = res.object || {};
		$("#flHeader").text((h.studentName||"") + " (" + (h.enrollNo||"") + ") — " + (h.gradeName||"")
			+ "  |  Paid: " + (h.totalPaid||0) + " / Fee: " + (h.totalFee||0) + "  |  Balance: " + (h.balance||0));
		res.collection.forEach(function(r){
			$b.append("<tr><td>"+escHtml(r.paymentDateStr||"")+"</td><td>"+r.fee+"</td><td>"+r.discount
				+"</td><td>"+r.otherDues+"</td><td>"+r.dueAmount+"</td><td>"+r.feePaid+"</td><td>"+r.balance
				+"</td><td>"+escHtml(r.payee||"")+"</td><td>"+escHtml(r.receivedBy||"")+"</td><td>"+escHtml(r.receivedIn||"")+"</td></tr>");
		});
		$("#fvVoucher").hide();
		$("#flLedgerWrap").show();
	}).fail(function(){ alert("Could not load ledger"); });
}

// ===== Slice 15: Student CSV import =====
function downloadStudentTemplate(){
	var csv = "enrollNo,name,gradeName,gender,guardianName,mobile,status\n"
		+ "ENR-100,John Doe,Grade 1,Male,Mr Khan,03001234567,ACTIVE\n";
	var blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
	var url = URL.createObjectURL(blob);
	var a = document.createElement("a");
	a.href = url; a.download = "students-template.csv";
	document.body.appendChild(a); a.click();
	document.body.removeChild(a); URL.revokeObjectURL(url);
}

function importStudents(){
	var input = document.getElementById("impStudentsFile");
	if (!input || !input.files || input.files.length === 0) { return alert("Choose a CSV file first"); }
	var fd = new FormData();
	fd.append("file", input.files[0]);
	$("#impStudentsSummary").html("<span class='text-muted'>Importing…</span>");
	$.ajax({
		url: serverContext + "impStudents",
		type: "POST",
		data: fd,
		processData: false,
		contentType: false,
		success: function(res){
			if (!res || res.status !== "SUCCESS" || !res.object) {
				$("#impStudentsSummary").html("<span class='text-danger'>" + escHtml(res && res.message ? res.message : "Import failed") + "</span>");
				return;
			}
			var o = res.object;
			var html = "<div class='alert alert-success' style='margin-bottom:8px'>Created: <b>" + (o.created||0)
				+ "</b> &nbsp; Skipped: <b>" + (o.skipped||0) + "</b></div>";
			if (o.errors && o.errors.length) {
				html += "<ul class='text-danger'>";
				o.errors.forEach(function(e){ html += "<li>" + escHtml(e) + "</li>"; });
				html += "</ul>";
			}
			$("#impStudentsSummary").html(html);
		},
		error: function(){ $("#impStudentsSummary").html("<span class='text-danger'>Import failed</span>"); }
	});
}

function loadFeeSetting(){
	$.get(serverContext + "getFeeSetting", function(res){
		if(!res || res.status !== "SUCCESS" || !res.object) return;
		var o = res.object;
		$("#fsPaymentMode").val(o.paymentMode || "BOTH");
		$("#fsAutoRegister").prop("checked", !!o.autoRegisterDues);
		$("#fsAging").prop("checked", !!o.agingEnabled);
		$("#fsFeeBranchScoped").prop("checked", !!o.feeCollectionBranchScoped);
		$("#fsDueDay").val(o.dueDay != null ? o.dueDay : 10);
	});
}

function saveFeeSetting(){
	$.post(serverContext + "saveFeeSetting", {
		paymentMode: $("#fsPaymentMode").val(),
		autoRegisterDues: $("#fsAutoRegister").is(":checked"),
		agingEnabled: $("#fsAging").is(":checked"),
		dueDay: $("#fsDueDay").val(),
		feeCollectionBranchScoped: $("#fsFeeBranchScoped").is(":checked")
	}, function(res){
		alert(res && res.message ? res.message : (res && res.status === "SUCCESS" ? "Saved" : "Save failed"));
	}).fail(function(){ alert("Save failed"); });
}

// ===== Owner Configuration (generic per-tenant settings) =====
// The screen renders itself from the education-service catalog (/getConfig): each row is one configurable
// policy grouped by section. A toggle saves immediately (/saveConfig key=&value=). Adding a new setting is a
// catalog entry in the service — no change here.
function showConfig(){
	$('.formDiv').hide();
	$('#ConfigDiv').show();
	$('#configMsg').hide();
	loadConfig();
}

function loadConfig(){
	// Shared renderer — see /js/common/settings-form.js (was a per-module copy of the same code).
	renderSettingsForm({
		container:  '#configBody',
		loadUrl:    'getConfig',
		onChangeFn: 'saveConfigToggle',
		fieldPrefix:'cfg'
	});
}

function saveConfigToggle(el){
	var key = el.getAttribute('data-key');
	var value = el.checked ? 'true' : 'false';
	$.post(serverContext + 'saveConfig', { key: key, value: value }, function(res){
		var ok = res && (res.status === 'SUCCESS');
		$('#configMsg').removeClass('alert-success alert-danger')
			.addClass(ok ? 'alert-success' : 'alert-danger')
			.text(ok ? 'Saved.' : ((res && res.message) || 'Save failed')).show();
		if(!ok){ el.checked = !el.checked; }   // revert the toggle if the save failed
	}).fail(function(){
		el.checked = !el.checked;
		$('#configMsg').removeClass('alert-success').addClass('alert-danger').text('Save failed').show();
	});
}

// ===== Slice 16: Alerts module =====
// Tables (#tableAlerts / #tablePA) render through the shared loadDataTable() path
// (getAll "Alerts" / "PA"); these handlers own create/delete/send/import only.
function submitAlert(e){
	if (e) e.preventDefault();
	$.post(serverContext + "addAlerts", $("#Alerts").serialize(), function(res){
		alert(res && res.message ? res.message : "Saved");
		loadDataTable();
	}).fail(function(){ alert("Could not save alert"); });
}

function deleteSelectedAlerts(e){
	if (e) e.preventDefault();
	var ids = $("#tableAlerts input[type='checkbox']:checked").map(function(){ return $(this).val(); }).get().join(",");
	if (!ids) { return alert("Select alert(s) to delete"); }
	$.post(serverContext + "deleteAlerts", { checked: ids }, function(){ loadDataTable(); })
		.fail(function(){ alert("Could not delete"); });
}

function sendAlert(e){
	if (e) e.preventDefault();
	$.post(serverContext + "sendAlerts", $("#Alerts").serialize(), function(res){
		alert(res && res.message ? res.message : "Sent");
	}).fail(function(){ alert("Could not send alert"); });
}

// Public-alert contacts CSV import (bound to the PADiv Import button onclick="return paImportContacts()").
// Renamed from checkfile() to avoid a name collision with main.js's checkfile(file) (DRY / no shadowing).
function paImportContacts(){
	var input = document.getElementById("csvFile");
	if (!input || !input.files || input.files.length === 0) { alert("Choose a CSV file first"); return false; }
	var fd = new FormData();
	fd.append("file", input.files[0]);
	$.ajax({
		url: serverContext + "importCSV", type: "POST", data: fd, processData: false, contentType: false,
		success: function(res){
			alert(res && res.message ? res.message : "Imported");
			loadDataTable();
		},
		error: function(){ alert("Import failed"); }
	});
	return false;
}

function sendPublicAlert(e){
	if (e) e.preventDefault();
	$.post(serverContext + "sendPA", $("#PA").serialize(), function(res){
		alert(res && res.message ? res.message : "Sent");
	}).fail(function(){ alert("Could not send"); });
}

// ─── Fee receivables (slice 0.2a): arrears aging + student statement ──────────────────────────────
// Read-only views over the SHARED subledger engines (AgingCalculator / StatementBuilder) — the same code that
// produces POS receivables aging and customer statements. Nothing here computes money; it renders what the
// service returns, so a school's arrears and a shop's can never disagree.

function showFeeAging(){
	$('.formDiv').hide();
	$('#FeeAgingDiv').show();
	if (typeof revealSection === 'function') revealSection('FeeAgingDiv');
	loadFeeAging();
}

function loadFeeAging(){
	$.get(serverContext + 'getFeeAging', function(res){
		var list = (res && (res.collection || res.object)) || [];
		var $b = $('#feeAgingBody').empty();
		$('#feeAgingEmpty').toggle(list.length === 0);
		list.forEach(function(r){
			var tr = $('<tr>');
			tr.append($('<td>').text(r.partyName || ''));
			tr.append($('<td>').text(r.b0_30));
			tr.append($('<td>').text(r.b31_60));
			tr.append($('<td>').text(r.b61_90));
			// 90+ is the column a bursar acts on, so make it visually findable rather than one number among five.
			tr.append($('<td>').html('<strong>' + escHtml(String(r.b90plus)) + '</strong>'));
			tr.append($('<td>').text(r.total));
			// The enroll no is embedded in the party label as "Name (EN)" — pull it back out to jump to the
			// statement, so a bursar can go from "who owes" straight to "what for" without retyping.
			var m = /\(([^)]+)\)\s*$/.exec(r.partyName || '');
			var en = m ? m[1] : '';
			tr.append($('<td>').html(en
				? "<button class='btn btn-xs btn-default' onclick=\"openFeeStatement('" + escHtml(en) + "')\">"
					+ t('ui.js.statement') + "</button>"
				: ''));
			$b.append(tr);
		});
	}, 'json').fail(function(){ showFormError(t('ui.js.couldNotLoadArrears')); });
}

function showFeeStatement(){
	$('.formDiv').hide();
	$('#FeeStatementDiv').show();
	if (typeof revealSection === 'function') revealSection('FeeStatementDiv');
}

/** Jump straight from an arrears row to that student's statement. */
function openFeeStatement(enrollNo){
	showFeeStatement();
	$('#stmtEnrollNo').val(enrollNo);
	loadFeeStatement();
}

function loadFeeStatement(){
	var en = ($('#stmtEnrollNo').val() || '').trim();
	if(!en){ showFormError(t('ui.js.enterEnrollNo')); return; }
	$.get(serverContext + 'getFeeStatement?enrollNo=' + encodeURIComponent(en), function(res){
		var list = (res && (res.collection || res.object)) || [];
		var $b = $('#feeStatementBody').empty();
		$('#feeStatementEmpty').toggle(list.length === 0);
		list.forEach(function(l){
			var tr = $('<tr>');
			tr.append($('<td>').text(String(l.date || '').substring(0, 10)));
			tr.append($('<td>').text(l.docNo || ''));
			tr.append($('<td>').text(l.type || ''));
			// A charge and a payment are opposite movements; showing a blank rather than 0 keeps the column
			// scannable — the eye follows the non-empty side down the page.
			tr.append($('<td>').text(Number(l.debit) ? l.debit : ''));
			tr.append($('<td>').text(Number(l.credit) ? l.credit : ''));
			tr.append($('<td>').html('<strong>' + escHtml(String(l.balance)) + '</strong>'));
			$b.append(tr);
		});
	}, 'json').fail(function(){ showFormError(t('ui.js.couldNotLoadStatement')); });
}

/* ══ Slice 1.1 — Academic Year & Term ═════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-1.1-academic-year-term.md
 *
 * The owner defines the years and terms the school actually runs; "current" is DERIVED from today's
 * date by the service (D3), so nothing here computes it — the banner just displays what the server
 * resolved. Pinning is the one explicit override.
 *
 * Loading is hooked to the #registrationType change event rather than to the sidebar link, because
 * snavGo() sets that select and fires change — one hook covers both navigation paths (DRY).
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'AcademicYearDiv') loadAcademicYears();
});

function ayNotify(msg) {
	if (typeof uiAlert === 'function') { uiAlert(msg); return; }
	alert(msg);
}

function loadAcademicYears() {
	$.get(serverContext + 'getAcademicYears', function (res) {
		var years = (res && res.collection) || [];
		var $body = $('#tableAcademicYear tbody').empty();
		var $picker = $('#ayTermYear').empty();

		$('#ayEmpty').toggle(years.length === 0);
		years.forEach(function (y) {
			$picker.append($('<option>').val(y.id).text(y.name));
			var terms = y.terms || [];
			if (!terms.length) {
				// A year with no terms yet is still worth showing — otherwise "Add Year" looks like it failed.
				$body.append($('<tr>')
					.append($('<td>').text(y.name))
					.append($('<td colspan="5">').addClass('text-muted').text('—')));
				return;
			}
			terms.forEach(function (t) {
				var $pin = $('<button type="button">')
					.addClass(t.pinnedCurrent ? 'btn btn-xs btn-warning' : 'btn btn-xs btn-default')
					.text(t.pinnedCurrent ? 'Unpin' : 'Pin as current')
					.on('click', function () { pinTerm(t.id, !t.pinnedCurrent); });
				$body.append($('<tr>')
					.append($('<td>').text(y.name))
					.append($('<td>').text(t.name))
					.append($('<td>').text(t.startDateStr || ''))
					.append($('<td>').text(t.endDateStr || ''))
					.append($('<td>').text(t.pinnedCurrent ? 'Pinned' : ''))
					.append($('<td>').append($pin)));
			});
		});
		// .text() escapes, so no escHtml() is needed here — the values never touch innerHTML.
		if (typeof $picker.selectpicker === 'function') { try { $picker.selectpicker('refresh'); } catch (e) {} }
		loadCurrentTermBanner();
	});
}

/** Shows what the SERVER resolved as current — never recomputed here, so there is one rule (D3). */
function loadCurrentTermBanner() {
	$.get(serverContext + 'getCurrentTerm', function (res) {
		var t = res && res.object;
		var $b = $('#ayCurrentTerm');
		if (!t) {
			// A school with no terms is a permanently valid state, not an error.
			$b.removeClass('alert-info').addClass('alert-warning')
			  .text('No current term — attendance and fees are being recorded without one.').show();
			return;
		}
		$b.removeClass('alert-warning').addClass('alert-info')
		  .text('Current term: ' + t.name + (t.pinnedCurrent ? ' (pinned)' : '') +
		        (t.startDateStr ? ' · ' + t.startDateStr + ' – ' + (t.endDateStr || '') : '')).show();
	});
}

function saveAcademicYear() {
	var name = $.trim($('#ayName').val());
	if (!name) { ayNotify('Year name is required'); return; }
	$.post(serverContext + 'addAcademicYear', {
		name: name,
		startDateStr: $('#ayStart').val(),
		endDateStr: $('#ayEnd').val()
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			$('#ayName, #ayStart, #ayEnd').val('');
			loadAcademicYears();
		} else {
			ayNotify((res && res.message) || 'Could not save the academic year');
		}
	});
}

function saveTerm() {
	var yearId = $('#ayTermYear').val();
	var name = $.trim($('#ayTermName').val());
	if (!yearId) { ayNotify('Create an academic year first'); return; }
	if (!name) { ayNotify('Term name is required'); return; }
	$.post(serverContext + 'addTerm', {
		academicYearId: yearId,
		name: name,
		sequence: $('#ayTermSeq').val(),
		startDateStr: $('#ayTermStart').val(),
		endDateStr: $('#ayTermEnd').val()
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			$('#ayTermName, #ayTermStart, #ayTermEnd').val('');
			loadAcademicYears();
		} else {
			ayNotify((res && res.message) || 'Could not save the term');
		}
	});
}

/** Pinning is exclusive per tenant — the server unpins the others, so no client-side bookkeeping. */
function pinTerm(id, pin) {
	$.post(serverContext + 'pinCurrentTerm', { id: id, pinned: pin ? 'true' : 'false' }, function (res) {
		if (res && res.status === 'SUCCESS') loadAcademicYears();
		else ayNotify((res && res.message) || 'Could not change the pinned term');
	});
}

/* ══ Slice 1.2 — Examinations ═════════════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-1.2-examinations.md
 *
 * Two levels: the exam (term + weight) and its papers (subject, out of N, on a date). The class shown
 * per paper is DERIVED server-side from the subject (D2) — nothing here stores or guesses it.
 *
 * The lock (D5) is enforced by the SERVICE; this only reflects it, so the UI can never be the thing
 * that grants an edit. Same hook style as 1.1: one #registrationType change listener serves both the
 * dropdown and the sidebar, because snavGo() sets that select and fires change.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'ExamDiv') loadExams();
});

function loadExams() {
	// Terms come from 1.1. With none defined an exam cannot exist (D3), so say that up front rather
	// than letting the user fill the form and hit a server refusal.
	$.get(serverContext + 'getAcademicYears', function (res) {
		var years = (res && res.collection) || [];
		var $term = $('#exTerm').empty();
		var any = false;
		years.forEach(function (y) {
			(y.terms || []).forEach(function (t) {
				any = true;
				$term.append($('<option>').val(t.id).text(y.name + ' · ' + t.name));
			});
		});
		$('#exNoTerms').toggle(!any);
		$('#exAdd').prop('disabled', !any);
		if (typeof $term.selectpicker === 'function') { try { $term.selectpicker('refresh'); } catch (e) {} }
	});

	$.get(serverContext + 'getUserSubjects', function (data) {
		// getUserSubjects answers with <option> markup, the same contract the other pickers use.
		var $s = $('#exPaperSubject').empty().append(data);
		if (typeof $s.selectpicker === 'function') { try { $s.selectpicker('refresh'); } catch (e) {} }
	});

	$.get(serverContext + 'getExams', function (res) {
		var exams = (res && res.collection) || [];
		var $body = $('#tableExam tbody').empty();
		var $picker = $('#exPaperExam').empty();
		$('#exEmpty').toggle(exams.length === 0);

		exams.forEach(function (e) {
			$picker.append($('<option>').val(e.id).text(e.name));
			var papers = e.papers || [];
			var $status = $('<span>').text(e.status || 'DRAFT');
			if (e.locked) $status.addClass('label label-warning');

			if (!papers.length) {
				$body.append($('<tr>')
					.append($('<td>').text(e.name))
					.append($('<td>').text(e.termId == null ? '' : String(e.termId)))
					.append($('<td colspan="4">').addClass('text-muted').text('—'))
					.append($('<td>').append($status))
					.append($('<td>').append(examActions(e))));
				return;
			}
			papers.forEach(function (p, i) {
				$body.append($('<tr>')
					.append($('<td>').text(i === 0 ? e.name : ''))
					.append($('<td>').text(i === 0 && e.termId != null ? String(e.termId) : ''))
					.append($('<td>').text(p.subjectName || ''))
					.append($('<td>').text(p.gradeName || ''))     // derived, D2
					.append($('<td>').text(p.maxMarks == null ? '' : p.maxMarks))
					.append($('<td>').text(p.examDateStr || ''))
					.append($('<td>').append(i === 0 ? $status : $()))
					.append($('<td>').append(i === 0 ? examActions(e) : $())));
			});
		});
		if (typeof $picker.selectpicker === 'function') { try { $picker.selectpicker('refresh'); } catch (e) {} }
	});
}

/** Publish / lock / unlock. Unlocking re-opens results to restatement, so it is confirmed. */
function examActions(e) {
	var $wrap = $('<span>');
	if (e.status === 'DRAFT') {
		$wrap.append($('<button type="button" class="btn btn-xs btn-default">')
			.text('Publish').on('click', function () { setExamStatus(e.id, 'PUBLISHED'); }));
	} else if (e.status === 'PUBLISHED') {
		$wrap.append($('<button type="button" class="btn btn-xs btn-default">')
			.text('Lock').on('click', function () { setExamStatus(e.id, 'LOCKED'); }));
	} else if (e.status === 'LOCKED') {
		$wrap.append($('<button type="button" class="btn btn-xs btn-warning">')
			.text('Unlock').on('click', function () {
				var msg = 'Unlocking lets the exam definition change, which restates marks already entered. Continue?';
				if (typeof uiConfirm === 'function') { uiConfirm(msg, function () { setExamStatus(e.id, 'PUBLISHED'); }); return; }
				setExamStatus(e.id, 'PUBLISHED');
			}));
	}
	return $wrap;
}

function setExamStatus(id, status) {
	$.post(serverContext + 'setExamStatus', { id: id, status: status }, function (res) {
		if (res && res.status === 'SUCCESS') loadExams();
		else ayNotify((res && res.message) || 'Could not change the exam status');
	});
}

function saveExam() {
	var name = $.trim($('#exName').val());
	var termId = $('#exTerm').val();
	if (!name) { ayNotify('Exam name is required'); return; }
	if (!termId) { ayNotify('Create an academic year and at least one term first'); return; }
	$.post(serverContext + 'addExam', {
		name: name,
		type: $('#exType').val(),
		termId: termId,
		weightPercent: $('#exWeight').val()
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			$('#exName, #exType, #exWeight').val('');
			// The weight-total notice rides on the SUCCESS message (D4: a warning, never a block).
			if (res.message && res.message.indexOf('total') > -1) ayNotify(res.message);
			loadExams();
		} else {
			ayNotify((res && res.message) || 'Could not save the exam');
		}
	});
}

function saveExamPaper() {
	var examId = $('#exPaperExam').val();
	var subjectId = $('#exPaperSubject').val();
	if (!examId) { ayNotify('Add an exam first'); return; }
	if (!subjectId) { ayNotify('Select a subject'); return; }
	$.post(serverContext + 'addExamPaper', {
		examId: examId,
		subjectId: subjectId,
		maxMarks: $('#exMaxMarks').val(),
		passMarks: $('#exPassMarks').val(),
		examDateStr: $('#exPaperDate').val()
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			$('#exPaperDate').val('');
			loadExams();
		} else {
			// A locked exam answers FAILED with a message naming the fix — show it verbatim.
			ayNotify((res && res.message) || 'Could not save the paper');
		}
	});
}

/* ══ Slice 1.3 — Marks Entry ══════════════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-1.3-marks-entry.md
 *
 * The grid is the unit of work: a teacher marks one paper for one class and saves once, exactly like
 * the attendance roster. Absent is a CHECKBOX, never a zero (D2) — they are different facts.
 *
 * Per-row failures (D3) are shown next to the count, and a partial save is never rendered as a clean
 * success: the server answers PARTIAL for that case precisely so the UI cannot round it up.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'MarksDiv') loadMarksExams();
});

/** Papers are nested inside their exam, so one /getExams call populates both dropdowns. */
var mkExamCache = [];

function loadMarksExams() {
	$.get(serverContext + 'getExams', function (res) {
		mkExamCache = (res && res.collection) || [];
		var $ex = $('#mkExam').empty();
		mkExamCache.forEach(function (e) {
			// A DRAFT exam cannot take marks (D4) — leave it out rather than fail on save.
			if (e.status === 'DRAFT') return;
			$ex.append($('<option>').val(e.id).text(e.name + (e.locked ? ' (locked)' : '')));
		});
		if (typeof $ex.selectpicker === 'function') { try { $ex.selectpicker('refresh'); } catch (err) {} }
		loadMarksPapers();
	});
}

$(document).on('change', '#mkExam', loadMarksPapers);

function loadMarksPapers() {
	var examId = $('#mkExam').val();
	var exam = mkExamCache.filter(function (e) { return String(e.id) === String(examId); })[0];
	var $p = $('#mkPaper').empty();
	((exam && exam.papers) || []).forEach(function (p) {
		var label = (p.subjectName || 'Paper') + (p.gradeName ? ' · ' + p.gradeName : '')
			+ (p.maxMarks != null ? ' (max ' + p.maxMarks + ')' : '');
		$p.append($('<option>').val(p.id).text(label));
	});
	if (typeof $p.selectpicker === 'function') { try { $p.selectpicker('refresh'); } catch (err) {} }
}

function loadMarksSheet() {
	var paperId = $('#mkPaper').val();
	if (!paperId) { ayNotify('Select an exam and paper first'); return; }
	$.get(serverContext + 'getMarksSheet', { examPaperId: paperId }, function (res) {
		if (!res || res.status !== 'SUCCESS' || !res.object) {
			ayNotify((res && res.message) || 'Could not load the marksheet');
			return;
		}
		var sheet = res.object;
		var rows = sheet.rows || [];
		var $body = $('#tableMarks tbody').empty();
		$('#mkEmpty').toggle(rows.length === 0);
		$('#mkErrors').hide().empty();
		$('#mkInfo').show().text(
			(sheet.examName || '') + ' · ' + (sheet.subjectName || '') +
			(sheet.gradeName ? ' · ' + sheet.gradeName : '') +
			' · max ' + (sheet.maxMarks == null ? '—' : sheet.maxMarks) +
			(sheet.passMarks == null ? '' : ', pass ' + sheet.passMarks));

		rows.forEach(function (r) {
			var $marks = $('<input type="number" class="form-control mkMark">')
				.attr('min', 0).attr('data-enroll', r.enrollNo);
			if (sheet.maxMarks != null) $marks.attr('max', sheet.maxMarks);
			if (r.marksObtained != null) $marks.val(r.marksObtained);

			var $absent = $('<input type="checkbox" class="mkAbsent">')
				.attr('data-enroll', r.enrollNo).prop('checked', !!r.absent);
			// Absent and a mark are contradictory input, so the UI makes them mutually exclusive
			// rather than letting the server reject the row later.
			$absent.on('change', function () {
				if (this.checked) $marks.val('').prop('disabled', true);
				else $marks.prop('disabled', false);
			});
			if (r.absent) $marks.prop('disabled', true);

			$body.append($('<tr>')
				.append($('<td>').text(r.enrollNo))
				.append($('<td>').text(r.name || ''))
				.append($('<td>').append($marks))
				.append($('<td>').append($absent))
				.append($('<td>').append($('<input type="text" class="form-control mkRemark">')
					.attr('data-enroll', r.enrollNo).val(r.remarks || ''))));
		});
	});
}

function saveMarks() {
	var paperId = $('#mkPaper').val();
	if (!paperId) { ayNotify('Select an exam and paper first'); return; }

	var rows = [];
	$('#tableMarks tbody tr').each(function () {
		var $tr = $(this);
		var enroll = $tr.find('.mkMark').attr('data-enroll');
		if (!enroll) return;
		var raw = $.trim($tr.find('.mkMark').val());
		rows.push({
			enrollNo: enroll,
			// '' must travel as null, not 0 — a blank row is "not marked yet", and 0 is a real score.
			marksObtained: raw === '' ? null : parseInt(raw, 10),
			absent: $tr.find('.mkAbsent').is(':checked'),
			remarks: $tr.find('.mkRemark').val()
		});
	});
	if (!rows.length) { ayNotify('Load a sheet first'); return; }

	$.ajax({
		url: serverContext + 'saveMarksBulk',
		type: 'POST',
		contentType: 'application/json',
		data: JSON.stringify({ examPaperId: parseInt(paperId, 10), rows: rows }),
		success: function (res) {
			var $err = $('#mkErrors');
			if (res && (res.status === 'SUCCESS' || res.status === 'PARTIAL')) {
				var errs = (res.object && res.object.errors) || [];
				if (errs.length) {
					// D3: valid rows WERE saved. Say both halves — "saved" alone would hide the rejects,
					// and "failed" alone would imply the good rows were lost.
					$err.show().empty().append($('<strong>').text(res.message));
				} else {
					$err.hide().empty();
				}
				loadMarksSheet();
				loadMarksExams();   // the first mark may have LOCKED the exam (D4)
			} else {
				ayNotify((res && res.message) || 'Could not save the marks');
			}
		},
		error: function (xhr) {
			ayNotify('Could not save the marks: ' + (xhr.responseText || xhr.status));
		}
	});
}

/* ══ Slice 1.4 — Grading Scale ════════════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-1.4-grading-scales.md
 *
 * Bands are an entity (a table); the two policies live in Configuration with the other per-org
 * settings. Validation is server-side and whole-scale — a band is only correct relative to its
 * neighbours — so this shows the refusal verbatim rather than pre-judging it.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'GradingDiv') loadGradingScale();
});

function loadGradingScale() {
	$.get(serverContext + 'getGradingScale', function (res) {
		var data = (res && res.object) || {};
		var bands = data.bands || [];
		var $body = $('#tableGrading tbody').empty();
		// An empty scale is legitimate (D2), so say what it means and offer the preset rather than
		// showing a bare empty table.
		$('#grEmpty').toggle(!bands.length);

		bands.forEach(function (b) {
			var $del = $('<button type="button" class="btn btn-xs btn-danger">')
				.text('Delete')
				.on('click', function () { deleteGradeBand(b.id, b.name); });
			$body.append($('<tr>')
				.append($('<td>').text(b.name))
				.append($('<td>').text(b.minPercent))
				.append($('<td>').text(b.maxPercent))
				.append($('<td>').text(b.gpaPoints == null ? '' : b.gpaPoints))
				.append($('<td>').append($del)));
		});
	});
}

function saveGradeBand() {
	var name = $.trim($('#grName').val());
	if (!name) { ayNotify('Band name is required'); return; }
	$.post(serverContext + 'saveGradeBand', {
		name: name,
		minPercent: $('#grMin').val(),
		maxPercent: $('#grMax').val(),
		gpaPoints: $('#grGpa').val()
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			$('#grName, #grMin, #grMax, #grGpa').val('');
			loadGradingScale();
		} else {
			// The server names the overlapping pair or the uncovered range — show it as-is.
			ayNotify((res && res.message) || 'Could not save the band');
		}
	});
}

function deleteGradeBand(id, name) {
	var msg = 'Delete the "' + name + '" band? Marks in its range will show a percentage with no grade '
		+ 'until another band covers them.';
	var go = function () {
		$.post(serverContext + 'deleteGradeBand', { checked: id }, function (res) {
			if (res && res.status === 'SUCCESS') loadGradingScale();
			else ayNotify((res && res.message) || 'Could not delete the band');
		});
	};
	if (typeof uiConfirm === 'function') { uiConfirm(msg, go); return; }
	go();
}

function applyGradingPreset() {
	$.post(serverContext + 'applyGradingPreset', {}, function (res) {
		if (res && res.status === 'SUCCESS') loadGradingScale();
		else ayNotify((res && res.message) || 'Could not apply the preset');
	});
}

/* ══ Slice 2.2 — Substitution ═════════════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-2.2-substitution.md
 *
 * The 07:50 screen. One question: who is out, and who covers their lessons?
 *
 * The free-teacher list comes from the SERVER already filtered (teaching / absent / covering elsewhere)
 * and ranked — this file renders it and never re-judges it. Refusals are shown verbatim: the server names
 * the class a teacher is already committed to, which is the only useful thing to say.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'SubstitutionDiv') loadSubstitutionScreen();
});

function loadSubstitutionScreen() {
	// Default to today — this screen is opened on the morning it is used.
	if (!$('#sbDate').val()) $('#sbDate').val(new Date().toISOString().slice(0, 10));

	$.get(serverContext + 'getAcademicYears', function (res) {
		var years = (res && res.collection) || [];
		var $t = $('#sbTerm').empty().append($('<option>').val('').text(t('ui.js.ttNoTerm')));
		years.forEach(function (y) {
			(y.terms || []).forEach(function (tm) {
				$t.append($('<option>').val(tm.id).text(y.name + ' · ' + tm.name));
			});
		});
		if (typeof $t.selectpicker === 'function') { try { $t.selectpicker('refresh'); } catch (e) {} }
		loadSubstitutionDay();
	});

	$.get(serverContext + 'getUserStaffs', function (data) {
		var $s = $('#sbAbsentStaff').empty().append(data);
		if (typeof $s.selectpicker === 'function') { try { $s.selectpicker('refresh'); } catch (e) {} }
	});
}

function sbMessage(msg, cls) {
	var $m = $('#sbMsg');
	if (!msg) { $m.hide().empty(); return; }
	$m.attr('class', 'alert ' + (cls || 'alert-info')).text(msg).show();
}

function loadSubstitutionDay() {
	var params = { date: $('#sbDate').val() };
	if ($('#sbTerm').val()) params.termId = $('#sbTerm').val();

	$.get(serverContext + 'getSubstitutionDay', params, function (res) {
		if (!res || res.status !== 'SUCCESS' || !res.object) {
			sbMessage((res && res.message) || t('ui.js.sbCouldNotLoad'), 'alert-danger');
			return;
		}
		var day = res.object;

		// An unsupervised class is the whole point of this screen — lead with the count (D5).
		var uncovered = day.uncovered || 0;
		$('#sbUncovered').toggle(uncovered > 0)
			.text(t('ui.js.sbUncoveredCount').replace('{n}', uncovered));

		var $abs = $('#tableAbsences tbody').empty();
		(day.absences || []).forEach(function (a) {
			var $clear = $('<button type="button" class="btn btn-xs btn-default">')
				.text(t('ui.js.sbBackIn'))
				.on('click', function () { clearStaffAbsence(a.id, a.staffName); });
			$abs.append($('<tr>')
				.append($('<td>').text(a.staffName || ''))
				.append($('<td>').text(a.reason || ''))
				.append($('<td>').append($clear)));
		});
		if (!(day.absences || []).length) {
			$abs.append($('<tr>').append($('<td colspan="3">').addClass('text-muted')
				.text(t('ui.js.sbNobodyOut'))));
		}

		var $body = $('#tableSubstitution tbody').empty();
		(day.lessons || []).forEach(function (l) {
			var $tr = $('<tr>')
				.append($('<td>').text(l.periodName || ''))
				.append($('<td>').text(l.gradeName || ''))
				.append($('<td>').text(l.subjectName || ''))
				.append($('<td>').text(l.absentStaffName || ''));

			var $cell = $('<td>');
			if (l.status === 'ASSIGNED') {
				$cell.append($('<span>').text(l.coverStaffName || ''));
				$cell.append(' ');
				$cell.append($('<button type="button" class="btn btn-xs btn-link">')
					.text(t('ui.js.sbRemoveCover'))
					.on('click', function () { clearSubstitute(l.substitutionId); }));
			} else {
				// The server already excluded anyone teaching, absent, or covering elsewhere this period.
				var $sel = $('<select class="form-control input-sm">')
					.append($('<option>').val('').text(t('ui.js.sbChooseCover')));
				(l.freeTeachers || []).forEach(function (c) {
					var label = c.staffName
						+ (c.teachesThisSubject ? ' · ' + t('ui.js.sbTeachesSubject') : '')
						+ (c.coversToday ? ' · ' + t('ui.js.sbCoversToday').replace('{n}', c.coversToday) : '');
					$sel.append($('<option>').val(c.staffId).text(label));
				});
				$sel.on('change', function () {
					if (this.value) assignSubstitute(l.timetableEntryId, this.value);
				});
				if (!(l.freeTeachers || []).length) {
					$sel.empty().append($('<option>').text(t('ui.js.sbNobodyFree')));
				}
				$cell.append($sel);
				$tr.addClass('danger');
			}
			$body.append($tr.append($cell));
		});
		if (!(day.lessons || []).length) {
			$body.append($('<tr>').append($('<td colspan="5">').addClass('text-muted')
				.text(t('ui.js.sbNothingToCover'))));
		}
	});
}

function markStaffAbsent() {
	var staffId = $('#sbAbsentStaff').val();
	if (!staffId) { ayNotify(t('ui.js.sbPickTeacher')); return; }
	var payload = { staffId: staffId, date: $('#sbDate').val() };
	if ($('#sbTerm').val()) payload.termId = $('#sbTerm').val();
	$.post(serverContext + 'markStaffAbsent', payload, function (res) {
		if (res && res.status === 'SUCCESS') {
			sbMessage(res.message, 'alert-warning');
			loadSubstitutionDay();
		} else {
			sbMessage((res && res.message) || t('ui.js.sbCouldNotMarkAbsent'), 'alert-danger');
		}
	});
}

function clearStaffAbsence(id, name) {
	uiConfirmOrRun(t('ui.js.sbConfirmBackIn').replace('{name}', name || ''), function () {
		$.post(serverContext + 'clearStaffAbsence', { id: id }, function (res) {
			if (res && res.status === 'SUCCESS') {
				sbMessage(res.message, 'alert-success');
				loadSubstitutionDay();
			} else {
				sbMessage((res && res.message) || t('ui.js.sbCouldNotClear'), 'alert-danger');
			}
		});
	});
}

function assignSubstitute(timetableEntryId, coverStaffId) {
	$.post(serverContext + 'assignSubstitute', {
		timetableEntryId: timetableEntryId,
		coverStaffId: coverStaffId,
		date: $('#sbDate').val()
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			sbMessage(res.message, 'alert-success');
		} else {
			// The refusal names the class the teacher is already committed to — show it verbatim.
			sbMessage((res && res.message) || t('ui.js.sbCouldNotAssign'), 'alert-danger');
		}
		loadSubstitutionDay();
	});
}

function clearSubstitute(id) {
	$.post(serverContext + 'clearSubstitute', { id: id }, function (res) {
		if (res && res.status === 'SUCCESS') sbMessage(res.message, 'alert-warning');
		else sbMessage((res && res.message) || t('ui.js.sbCouldNotClearCover'), 'alert-danger');
		loadSubstitutionDay();
	});
}

/* ══ Slice 2.1 — Timetable ════════════════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-2.1-timetable.md
 *
 * Periods define the school day; the grid places a subject + teacher in each slot. Clash detection is
 * SERVER-side and comes back in two flavours: a refusal (FAILED, nothing written) or a warning shipped
 * alongside a SUCCESS. The UI shows both verbatim — it never re-judges what the server decided.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'TimetableDiv') loadTimetableScreen();
});

var TT_DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

function loadTimetableScreen() {
	loadPeriods();

	$.get(serverContext + 'getAcademicYears', function (res) {
		var years = (res && res.collection) || [];
		var $t = $('#ttTerm').empty().append($('<option>').val('').text(t('ui.js.ttNoTerm')));
		years.forEach(function (y) {
			(y.terms || []).forEach(function (tm) {
				$t.append($('<option>').val(tm.id).text(y.name + ' · ' + tm.name));
			});
		});
		if (typeof $t.selectpicker === 'function') { try { $t.selectpicker('refresh'); } catch (e) {} }
		loadTimetable();
	});

	$.get(serverContext + 'getUserGrades', function (data) {
		var $g = $('#ttGrade').empty().append(data);
		if (typeof $g.selectpicker === 'function') { try { $g.selectpicker('refresh'); } catch (e) {} }
	});
	$.get(serverContext + 'getUserSubjects', function (data) {
		var $s = $('#ttSubject').empty().append(data);
		if (typeof $s.selectpicker === 'function') { try { $s.selectpicker('refresh'); } catch (e) {} }
	});
	$.get(serverContext + 'getUserStaffs', function (data) {
		var $s = $('#ttStaff').empty().append($('<option>').val('').text(t('ui.js.ttNoTeacher'))).append(data);
		var $v = $('#ttStaffView').empty().append($('<option>').val('').text(t('ui.js.ttWholeClass'))).append(data);
		[$s, $v].forEach(function ($x) {
			if (typeof $x.selectpicker === 'function') { try { $x.selectpicker('refresh'); } catch (e) {} }
		});
	});

	var $d = $('#ttDay').empty();
	TT_DAYS.forEach(function (d) { $d.append($('<option>').val(d).text(t('ui.js.ttDay' + d))); });
	if (typeof $d.selectpicker === 'function') { try { $d.selectpicker('refresh'); } catch (e) {} }
}

function ttMessage(msg, cls) {
	var $m = $('#ttMsg');
	if (!msg) { $m.hide().empty(); return; }
	$m.attr('class', 'alert ' + (cls || 'alert-info')).text(msg).show();
}

function loadPeriods() {
	$.get(serverContext + 'getPeriods', function (res) {
		var periods = (res && res.collection) || [];
		$('#ttNoPeriods').toggle(periods.length === 0);

		var $body = $('#tablePeriods tbody').empty();
		var $sel = $('#ttPeriod').empty();
		periods.forEach(function (p) {
			var $del = $('<button type="button" class="btn btn-xs btn-danger">')
				.text(t('ui.js.ttDelete'))
				.on('click', function () { deletePeriod(p.id, p.name); });
			$body.append($('<tr>')
				.append($('<td>').text(p.name))
				.append($('<td>').text(p.sequence == null ? '' : p.sequence))
				.append($('<td>').text(p.startTime || ''))
				.append($('<td>').text(p.endTime || ''))
				.append($('<td>').text(p.teaching ? t('ui.js.ttYes') : t('ui.js.ttNo')))
				.append($('<td>').append($del)));
			// Only teaching periods can hold a lesson — the server refuses the rest, so don't offer them.
			if (p.teaching) $sel.append($('<option>').val(p.id).text(p.name));
		});
		if (typeof $sel.selectpicker === 'function') { try { $sel.selectpicker('refresh'); } catch (e) {} }
	});
}

function savePeriod() {
	var name = $.trim($('#pdName').val());
	if (!name) { ayNotify(t('ui.js.ttPeriodNameRequired')); return; }
	$.post(serverContext + 'savePeriod', {
		name: name,
		sequence: $('#pdSeq').val(),
		startTime: $('#pdStart').val(),
		endTime: $('#pdEnd').val(),
		teaching: $('#pdTeaching').is(':checked') ? 'true' : 'false'
	}, function (res) {
		if (res && res.status === 'SUCCESS') {
			$('#pdName, #pdSeq, #pdStart, #pdEnd').val('');
			$('#pdTeaching').prop('checked', true);
			loadPeriods();
			loadTimetable();
		} else {
			ttMessage((res && res.message) || t('ui.js.ttCouldNotSavePeriod'), 'alert-danger');
		}
	});
}

function deletePeriod(id, name) {
	uiConfirmOrRun(t('ui.js.ttConfirmDeletePeriod').replace('{name}', name), function () {
		$.post(serverContext + 'deletePeriod', { id: id }, function (res) {
			if (res && res.status === 'SUCCESS') { loadPeriods(); loadTimetable(); }
			// The server refuses while lessons still use it — show that reason verbatim.
			else ttMessage((res && res.message) || t('ui.js.ttCouldNotDeletePeriod'), 'alert-warning');
		});
	});
}

function loadTimetable() {
	var params = {};
	if ($('#ttTerm').val()) params.termId = $('#ttTerm').val();
	if ($('#ttGrade').val()) params.gradeId = $('#ttGrade').val();
	$('#ttStaffView').val('');
	renderTimetable(params, false);
}

function loadTimetableByTeacher() {
	var staffId = $('#ttStaffView').val();
	if (!staffId) { loadTimetable(); return; }
	var params = { staffId: staffId };
	if ($('#ttTerm').val()) params.termId = $('#ttTerm').val();
	renderTimetable(params, true);
}

function renderTimetable(params, teacherView) {
	$.get(serverContext + 'getTimetable', params, function (res) {
		if (!res || res.status !== 'SUCCESS' || !res.object) {
			ttMessage((res && res.message) || t('ui.js.ttCouldNotLoad'), 'alert-danger');
			return;
		}
		var data = res.object;
		var periods = data.periods || [];
		var entries = data.entries || [];
		$('#ttNoPeriods').toggle(!data.configured);
		// The editor is pointless without periods, and without a class to place lessons into.
		$('#ttEditor').toggle(!!data.configured && !teacherView && !!$('#ttGrade').val());
		ttMessage('');

		var byCell = {};
		entries.forEach(function (e) { byCell[e.dayOfWeek + '|' + e.periodId] = e; });

		var $head = $('#tableTimetable thead').empty();
		var $hr = $('<tr>').append($('<th>').text(t('ui.js.ttPeriodCol')));
		TT_DAYS.forEach(function (d) { $hr.append($('<th>').text(t('ui.js.ttDay' + d))); });
		$head.append($hr);

		var $body = $('#tableTimetable tbody').empty();
		periods.forEach(function (p) {
			var label = p.name + (p.startTime ? ' (' + p.startTime + '–' + (p.endTime || '') + ')' : '');
			var $tr = $('<tr>').append($('<td>').text(label));
			if (!p.teaching) {
				// A break spans the week as one labelled band rather than six empty cells.
				$tr.append($('<td colspan="' + TT_DAYS.length + '">').addClass('text-muted').text(p.name));
				$body.append($tr);
				return;
			}
			TT_DAYS.forEach(function (d) {
				var e = byCell[d + '|' + p.id];
				var $td = $('<td>');
				if (e) {
					$td.append($('<div>').text(e.subjectName || ''));
					if (teacherView) $td.append($('<div>').addClass('text-muted').text(e.gradeName || ''));
					else if (e.staffName) $td.append($('<div>').addClass('text-muted').text(e.staffName));
					if (e.room) $td.append($('<div>').addClass('text-muted').text(t('ui.js.ttRoom') + ' ' + e.room));
					if (!teacherView) {
						$td.append($('<button type="button" class="btn btn-xs btn-link">')
							.text(t('ui.js.ttRemove'))
							.on('click', function () { deleteTimetableEntry(e.id); }));
					}
				}
				$tr.append($td);
			});
			$body.append($tr);
		});
	});
}

function saveTimetableEntry() {
	var subjectId = $('#ttSubject').val();
	var periodId = $('#ttPeriod').val();
	if (!subjectId || !periodId) { ayNotify(t('ui.js.ttPickSubjectPeriod')); return; }
	var payload = {
		dayOfWeek: $('#ttDay').val(),
		periodId: periodId,
		subjectId: subjectId,
		room: $.trim($('#ttRoom').val())
	};
	if ($('#ttTerm').val()) payload.termId = $('#ttTerm').val();
	if ($('#ttStaff').val()) payload.staffId = $('#ttStaff').val();

	$.post(serverContext + 'saveTimetableEntry', payload, function (res) {
		if (res && res.status === 'SUCCESS') {
			// SUCCESS may still carry a warning (a shared room, an out-of-hours period) — show it.
			var warned = (res.message || '').indexOf('—') > -1;
			ttMessage(res.message, warned ? 'alert-warning' : 'alert-success');
			loadTimetable();
		} else {
			// A refusal names the teacher or class that already holds the slot; show it verbatim.
			ttMessage((res && res.message) || t('ui.js.ttCouldNotSchedule'), 'alert-danger');
		}
	});
}

function deleteTimetableEntry(id) {
	uiConfirmOrRun(t('ui.js.ttConfirmRemoveLesson'), function () {
		$.post(serverContext + 'deleteTimetableEntry', { id: id }, function (res) {
			if (res && res.status === 'SUCCESS') loadTimetable();
			else ttMessage((res && res.message) || t('ui.js.ttCouldNotRemove'), 'alert-danger');
		});
	});
}

function copyTimetable() {
	var fromTermId = $('#ttTerm').val();
	var target = window.prompt(t('ui.js.ttCopyPrompt'));
	if (!target) return;
	$.post(serverContext + 'copyTimetable', { fromTermId: fromTermId, toTermId: target }, function (res) {
		if (res && res.status === 'SUCCESS') {
			ttMessage(res.message, 'alert-success');
		} else {
			// Refused outright when the target term already has a timetable — the reason is the message.
			ttMessage((res && res.message) || t('ui.js.ttCouldNotCopy'), 'alert-warning');
		}
	});
}

/* ══ Slice 1.6 — Promotion ════════════════════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-1.6-promotion.md
 *
 * Plan, then apply. Nothing is stored until Apply, and Apply stays DISABLED until a plan is on screen —
 * this rewrites the class of every child in a roster, so the UI never offers it as a one-click action.
 *
 * UNDECIDED rows are shown but never sent: a student with no issued report card is a decision the school
 * must make, not one a default should make for them.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'PromotionDiv') loadPromotionScreen();
});

function loadPromotionScreen() {
	$('#prRun').prop('disabled', true);
	$('#tablePromotion tbody').empty();
	$('#prSummary').hide();
	prMessage('');

	$.get(serverContext + 'getAcademicYears', function (res) {
		var years = (res && res.collection) || [];
		var $y = $('#prYear').empty();
		years.forEach(function (y) { $y.append($('<option>').val(y.id).text(y.name)); });
		if (!years.length) prMessage(t('ui.js.prNoYears'), 'alert-warning');
		if (typeof $y.selectpicker === 'function') { try { $y.selectpicker('refresh'); } catch (e) {} }
	});

	$.get(serverContext + 'getUserGrades', function (data) {
		// getUserGrades answers with <option> markup, the contract the other class pickers use.
		var $from = $('#prFromGrade').empty().append(data);
		// The target list carries a blank first entry: no target = the final class, whose students
		// graduate (D5). That is a legitimate choice, so it is offered rather than hidden.
		var $to = $('#prToGrade').empty()
			.append($('<option>').val('').text(t('ui.js.prNoTargetGraduate')))
			.append(data);
		[$from, $to].forEach(function ($s) {
			if (typeof $s.selectpicker === 'function') { try { $s.selectpicker('refresh'); } catch (e) {} }
		});
	});
}

function prMessage(msg, cls) {
	var $m = $('#prMsg');
	if (!msg) { $m.hide().empty(); return; }
	$m.attr('class', 'alert ' + (cls || 'alert-info')).text(msg).show();
}

function loadPromotionPlan() {
	var yearId = $('#prYear').val();
	if (!yearId) { ayNotify(t('ui.js.prPickYear')); return; }
	var params = { academicYearId: yearId };
	if ($('#prFromGrade').val()) params.fromGradeId = $('#prFromGrade').val();
	if ($('#prToGrade').val()) params.toGradeId = $('#prToGrade').val();

	$.get(serverContext + 'getPromotionPlan', params, function (res) {
		if (!res || res.status !== 'SUCCESS' || !res.object) {
			prMessage((res && res.message) || t('ui.js.prCouldNotPlan'), 'alert-danger');
			return;
		}
		var plan = res.object;
		var rows = plan.rows || [];
		var $body = $('#tablePromotion tbody').empty();
		$('#prRun').prop('disabled', rows.length === 0);

		if (!rows.length) {
			prMessage(t('ui.js.prNoStudents'), 'alert-warning');
			$('#prSummary').hide();
			return;
		}
		prMessage('');

		rows.forEach(function (r) {
			// Each row is editable: the proposal is a suggestion, and an override is recorded as such.
			var $sel = $('<select class="form-control prDecision">')
				.attr('data-enroll', r.enrollNo)
				.append($('<option>').val('').text(t('ui.js.prUndecided')))
				.append($('<option>').val('PROMOTED').text(t('ui.js.prPromoted')))
				.append($('<option>').val('RETAINED').text(t('ui.js.prRetained')))
				.append($('<option>').val('GRADUATED').text(t('ui.js.prGraduated')));
			$sel.val(r.proposed || '');

			var $tr = $('<tr>')
				.append($('<td>').text(r.enrollNo || ''))
				.append($('<td>').text(r.name || ''))
				.append($('<td>').text(r.yearPercent == null ? '—' : r.yearPercent + '%'))
				.append($('<td>').append($sel))
				.append($('<td>').text(r.reason || ''));

			if (r.alreadyDecided) {
				// Already decided this year: shown for context, but not re-sent. The DB constraint would
				// refuse it anyway (D6); disabling it here explains why rather than producing an error.
				$sel.prop('disabled', true).val('');
				$tr.addClass('text-muted');
			} else if (r.undecided) {
				$tr.addClass('warning');
			}
			$body.append($tr);
		});

		var summary = t('ui.js.prPlanSummary')
			.replace('{total}', plan.total)
			.replace('{undecided}', plan.undecided)
			.replace('{decided}', plan.alreadyDecided);
		if (plan.requirePass) {
			summary += ' · ' + t('ui.js.prPassMark').replace('{min}', plan.minPercent);
		}
		if (plan.graduating) summary += ' · ' + t('ui.js.prGraduatingBatch');
		$('#prSummary').show().text(summary);
	});
}

function runPromotion() {
	var yearId = $('#prYear').val();
	if (!yearId) { ayNotify(t('ui.js.prPickYear')); return; }

	var rows = [];
	$('#tablePromotion tbody .prDecision').each(function () {
		if (this.disabled) return;              // already decided this year
		if (!this.value) return;                // UNDECIDED is never sent — the school must choose
		rows.push({ enrollNo: this.getAttribute('data-enroll'), outcome: this.value });
	});
	if (!rows.length) { ayNotify(t('ui.js.prNothingToApply')); return; }

	var payload = {
		academicYearId: Number(yearId),
		fromGradeId: $('#prFromGrade').val() ? Number($('#prFromGrade').val()) : null,
		toGradeId: $('#prToGrade').val() ? Number($('#prToGrade').val()) : null,
		rows: rows
	};

	var go = function () {
		$.ajax({
			url: serverContext + 'runPromotion',
			method: 'POST',
			contentType: 'application/json',
			data: JSON.stringify(payload),
			success: function (res) {
				// PARTIAL is not SUCCESS: if anything was skipped the message says so rather than
				// letting the screen imply a clean run.
				var cls = res && res.status === 'SUCCESS' ? 'alert-success' : 'alert-warning';
				prMessage((res && res.message) || '', cls);
				var problems = res && res.object && res.object.problems;
				if (problems && problems.length) {
					prMessage(((res && res.message) || '') + ' — ' + problems.join('; '), 'alert-warning');
				}
				loadPromotionPlan();
				loadPromotionHistory();
			},
			error: function (xhr) {
				prMessage(t('ui.js.prCouldNotApply') + ': ' + (xhr.responseText || xhr.status), 'alert-danger');
			}
		});
	};
	uiConfirmOrRun(t('ui.js.prConfirmApply').replace('{n}', rows.length), go);
}

/** The shared confirm dialog, with the plain fallback every other education action uses. */
function uiConfirmOrRun(msg, go) {
	if (typeof uiConfirm === 'function') { uiConfirm(msg, go); return; }
	go();
}

function loadPromotionHistory() {
	var yearId = $('#prYear').val();
	if (!yearId) return;
	$.get(serverContext + 'getPromotionHistory', { academicYearId: yearId }, function (res) {
		var found = (res && res.collection) || [];
		var $body = $('#tablePromotionHistory tbody').empty();
		found.forEach(function (p) {
			var $undo = $('<button type="button" class="btn btn-xs btn-default">')
				.text(t('ui.js.prUndo'))
				.prop('disabled', p.status !== 'APPLIED')
				.on('click', function () { undoPromotion(p.id); });
			var $tr = $('<tr>')
				.append($('<td>').text((p.enrollNo || '') + ' · ' + (p.studentName || '')))
				// The STORED names, so a class renamed since still reads as it did (D3).
				.append($('<td>').text(p.fromGradeName || ''))
				.append($('<td>').text(p.toGradeName || '—'))
				.append($('<td>').text((p.outcome || '') + (p.overridden ? ' *' : '')))
				.append($('<td>').text(p.reason || ''))
				.append($('<td>').append($undo));
			if (p.status === 'REVERSED') $tr.addClass('text-muted');
			$body.append($tr);
		});
	});
}

function undoPromotion(id) {
	uiConfirmOrRun(t('ui.js.prConfirmUndo'), function () {
		$.post(serverContext + 'undoPromotion', { id: id }, function (res) {
			if (res && res.status === 'SUCCESS') {
				prMessage(res.message, 'alert-success');
				loadPromotionPlan();
				loadPromotionHistory();
			} else {
				prMessage((res && res.message) || t('ui.js.prCouldNotUndo'), 'alert-danger');
			}
		});
	});
}

/* ══ Slice 1.5 — Report Cards & Transcript ════════════════════════════════════════════════════════
 * Design: microservices/docs/slices/edu-1.5-report-cards.md
 *
 * Preview is DERIVED from live marks; Publish SNAPSHOTS (D1). That distinction is the whole slice, so
 * the UI never hides which one it is showing — an issued card carries its version and issue date, a
 * preview does not.
 *
 * DOM is built with .text() and jQuery construction rather than string concatenation, so student and
 * subject names cannot inject markup.
 */
$(document).on('change', '#registrationType', function () {
	if (this.value === 'ReportCardDiv') loadReportCardTerms();
});

function loadReportCardTerms() {
	$.get(serverContext + 'getAcademicYears', function (res) {
		var years = (res && res.collection) || [];
		var $term = $('#rcTerm').empty();
		var any = false;
		years.forEach(function (y) {
			(y.terms || []).forEach(function (tm) {
				any = true;
				$term.append($('<option>').val(tm.id).text(y.name + ' · ' + tm.name));
			});
		});
		if (!any) {
			// 1.1's "a null term is permanently valid" meets a hard requirement here, as it did for exams.
			rcMessage(t('ui.js.rcNoTerms'), 'alert-warning');
		}
		if (typeof $term.selectpicker === 'function') { try { $term.selectpicker('refresh'); } catch (e) {} }
		loadReportCardStudents();
	});
}

function loadReportCardStudents() {
	$.get(serverContext + 'getUserStudent', function (res) {
		var students = (res && res.collection) || [];
		var $s = $('#rcStudent').empty();
		students.forEach(function (st) {
			if (!st.enrollNo) return;
			$s.append($('<option>').val(st.enrollNo).text(st.enrollNo + ' · ' + (st.name || '')));
		});
		if (typeof $s.selectpicker === 'function') { try { $s.selectpicker('refresh'); } catch (e) {} }
	});
}

function rcMessage(msg, cls) {
	var $m = $('#rcMsg');
	if (!msg) { $m.hide().empty(); return; }
	$m.attr('class', 'alert ' + (cls || 'alert-info')).text(msg).show();
}

function previewReportCard() {
	var enrollNo = $('#rcStudent').val();
	var termId = $('#rcTerm').val();
	if (!enrollNo || !termId) { ayNotify(t('ui.js.rcPickStudentTerm')); return; }
	$.get(serverContext + 'getReportCardPreview', { enrollNo: enrollNo, termId: termId }, function (res) {
		if (!res || res.status !== 'SUCCESS' || !res.object) {
			rcMessage((res && res.message) || t('ui.js.rcCouldNotPreview'), 'alert-danger');
			$('#rcCard').hide();
			return;
		}
		renderReportCard(res.object);
		// Shown verbatim: the server names the total AND the exams that make it up, because "70%" with
		// no explanation reads as a bug rather than as a setup step still to finish (D2).
		var warn = res.object.weightWarning;
		$('#rcWeightWarning').toggle(!!warn).text(warn || '');
		$('#rcPublish').prop('disabled', res.object.publishable === false);
	});
}

function renderReportCard(card) {
	$('#rcTranscript').hide();
	$('#rcCard').show();
	rcMessage('');

	var header = (card.studentName || '') + ' · ' + (card.enrollNo || '')
		+ (card.gradeName ? ' · ' + card.gradeName : '')
		+ ' · ' + (card.termName || '');
	if (card.issued) {
		// An issued card says so, with its version — a preview must never be mistaken for a record.
		header += ' — ' + t('ui.js.rcIssued') + ' ' + (card.issuedOn || '')
			+ ' (v' + card.version
			+ (card.status && card.status !== 'PUBLISHED' ? ', ' + card.status : '') + ')';
	}
	$('#rcHeader').text(header);
	$('#rcCard').removeClass('rc-superseded rc-withdrawn');
	if (card.status === 'SUPERSEDED') $('#rcCard').addClass('rc-superseded');
	if (card.status === 'WITHDRAWN') $('#rcCard').addClass('rc-withdrawn');

	var $body = $('#tableReportCard tbody').empty();
	(card.rows || []).forEach(function (r) {
		$body.append($('<tr>')
			.append($('<td>').text(r.examName || ''))
			.append($('<td>').text(r.subjectName || ''))
			// Absent prints as a word, never as 0 — 1.3 D2's distinction has to survive onto the paper.
			.append($('<td>').text(r.absent ? t('ui.js.rcAbsent')
				: (r.marksObtained == null ? '' : r.marksObtained)))
			.append($('<td>').text(r.maxMarks == null ? '' : r.maxMarks))
			.append($('<td>').text(r.percent == null ? '' : r.percent + '%'))
			.append($('<td>').text(r.grade || '')));
	});

	var totals = t('ui.js.rcTermTotal') + ': '
		+ (card.termPercent == null ? '—' : card.termPercent + '%')
		+ (card.termGradeName ? ' · ' + card.termGradeName : '')
		+ (card.termGpa == null ? '' : ' · GPA ' + card.termGpa);
	if (card.classRank != null) {
		totals += ' · ' + t('ui.js.rcRank') + ' ' + card.classRank
			+ (card.classSize ? '/' + card.classSize : '');
	}
	if (card.attendanceTotal != null) {
		totals += ' · ' + t('ui.js.rcAttendance') + ' '
			+ (card.attendancePresent == null ? 0 : card.attendancePresent) + '/' + card.attendanceTotal;
	}
	$('#rcTotals').text(totals);
}

function publishReportCard() {
	var enrollNo = $('#rcStudent').val();
	var termId = $('#rcTerm').val();
	if (!enrollNo || !termId) { ayNotify(t('ui.js.rcPickStudentTerm')); return; }
	var go = function () {
		$.post(serverContext + 'publishReportCard', { enrollNo: enrollNo, termId: termId }, function (res) {
			if (res && res.status === 'SUCCESS') {
				rcMessage(res.message, 'alert-success');
				// Re-read from the SNAPSHOT rather than keeping the preview on screen: what was stored
				// is what the parent gets, and showing anything else here would hide a mismatch.
				$.get(serverContext + 'getReportCard', { enrollNo: enrollNo, termId: termId }, function (r2) {
					if (r2 && r2.status === 'SUCCESS' && r2.object) renderReportCard(r2.object);
				});
			} else {
				rcMessage((res && res.message) || t('ui.js.rcCouldNotPublish'), 'alert-danger');
			}
		});
	};
	if (typeof uiConfirm === 'function') { uiConfirm(t('ui.js.rcConfirmPublish'), go); return; }
	go();
}

function loadTranscript() {
	var enrollNo = $('#rcStudent').val();
	if (!enrollNo) { ayNotify(t('ui.js.rcPickStudentTerm')); return; }
	$.get(serverContext + 'getTranscript', { enrollNo: enrollNo }, function (res) {
		var cards = (res && res.collection) || [];
		$('#rcCard').hide();
		$('#rcTranscript').show();
		var $body = $('#tableTranscript tbody').empty();
		if (!cards.length) {
			// A term with no published card is ABSENT from the transcript, not zero (D6) — say so, or an
			// empty table reads as "this student failed everything".
			$body.append($('<tr>').append($('<td colspan="5">').addClass('text-muted')
				.text(t('ui.js.rcNoneIssued'))));
			return;
		}
		cards.forEach(function (c) {
			var $open = $('<button type="button" class="btn btn-xs btn-default">')
				.text(t('ui.js.rcOpen'))
				.on('click', function () { renderReportCard(c); });
			$body.append($('<tr>')
				.append($('<td>').text(c.termName || ''))
				.append($('<td>').text(c.termPercent == null ? '' : c.termPercent + '%'))
				.append($('<td>').text(c.termGradeName || ''))
				.append($('<td>').text(c.issuedOn || ''))
				.append($('<td>').text('v' + c.version).append(' ').append($open)));
		});
	});
}
