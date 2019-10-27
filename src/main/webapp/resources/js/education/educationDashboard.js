$(document).ready(function() {
	getDashboardData();
});

function getDashboardData(){
    $.ajax({
    	type: "GET",
        url: serverContext+"getDashboardData"
    }).then(function(data) {
    	if(data.status == "SUCCESS"){
        	var obj = data.object;
            $("#studentDiv").text(obj.freshStudent+" / "+obj.allStudent);
            var percent = obj.freshStudent > 0?(obj.freshStudent*ONE / obj.allStudent*ONE * 100):0;
            $("#percentDiv").text(Math.round(percent).toFixed(2)+"%");
//            $("#allStudentDiv").text(obj.allStudent);
    	}else{
        	alert(data.message);
    	}
    }).fail(function(data) {
    	alert(data.message);
    });
}
