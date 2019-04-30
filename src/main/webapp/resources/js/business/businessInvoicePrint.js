var invGarmtsLogoL = "resources/img/logos/gll.jpg";
var invGarmtsLogoR = "resources/img/logos/glr.jpg";

function pGarmtsInv(data){
	if(userId*ONE != 519)
		return false;
	
	var doc = new jsPDF("p", "pt", "letter");
	var L = 230; var T = 30;
	//toDataURL(invGarmtsLogoL, function(dataUrl) {//logo 1
	//	toDataURL(invGarmtsLogoR, function(dataUrl2) {//logo 2
			//doc.addImage(dataUrl2, "JPEG", 455, 5, 140, 85);
			
			//doc.addImage(dataUrl, "JPEG", 5, 2, 140, 90);
			doc.setFont("Arial");
			doc.setFontType("bold");
			doc.setFontSize(20);
			doc.text("Haider Garments", L, T);//Company receipt name
			L=L+10;//240
			T=T+15;
			doc.addFont()
			doc.setFontSize(9);
			doc.text("Link road model town A Khanpur", L, T);//company address
			doc.setFontSize(12);
			L=L+30;//270
			T=T+15;
			doc.setFontType("bold");
			doc.text("03053939495", L, T);//company contact number
	
			L=L-20;//250
			T=T+30;
			doc.setFontSize(20);
			doc.text("Sales Invoice", L, T);
	
			T=T+10;
			//doc.line(150, T, 150, 0);
			//doc.line(450, T, 450, 0);
			doc.line(0, T, 650, T)
			L=L-230;//10
			T=T+15;
			doc.addFont()
			doc.setFontSize(12);
			doc.setFontType("helvetica");
			doc.text("Dated "+currentFormattedDateTime(), L, T);
			L=L+500;//510
			//doc.text("INV # "+4, L, T);
			
			T=T+5;
			doc.line(0, T, 650, T)
			
			L=L-500;//10
			T=T+15;
			
			doc.setFontType("helvetica");
			doc.text("Customer name ", L, T);
			L=L+450;
			doc.text($("#sellCN").val()+" "+$("#sellCC").val(), L, T);//doc.fromHTML($("#sellCN").val(), L, T);
			L=L-450;
			T=T+20;
			
			doc.text("Sale's Person", L, T);
			L=L+450;
			doc.text("", L, T);//doc.fromHTML($("#sellCC").val(), L, T);
			L=L-450;
			T=T+20;

			doc.setFontSize(14);
			doc.setFont("courier");
			var head = [["Item", "Qty", "Price","Disc","Amount"]];
			var body = [];
			var Y = T;
			var items=0,qtys=0,prices=0,discs=0,amounts=0;
			data.forEach(function(o,i){
				var total = 0;
				var dis = 0;
				if(o.dt=="%"){
					dis = o.totalAmount*ONE * o.discount/100;
					//total =o.totalAmount*ONE - dis;
				}else{
					dis = o.discount*ONE;
					//total = o.totalAmount*ONE - dis;
				}
				body.push([o.name, o.quantity, o.sellRate,dis,o.totalAmount*ONE]);
				T=T+22;
				qtys+=o.quantity*ONE;
				prices+=o.sellRate*ONE;
				discs+=dis;
				amounts+=o.totalAmount*ONE;
			});
			T=T+30;
			console.log(T,Y)
			doc.autoTable({head: head, body: body, startY: Y});
			doc.line(0, T, 650, T)
			T=T+15;
			doc.text("Totals ", L, T);
			doc.text(qtys+"", 237, T);
			doc.text(discs+"", 385, T);
			doc.text(amounts+"" , 460, T);
			if(discs*ONE>0){
				T=T+15;
				doc.text("Special discount ", L, T);
				doc.text("- "+discs , 445, T);
				T=T+10;
				doc.line(0, T, 650, T)
			}
			T=T+20;
			doc.setFontType("Arial");
			doc.text("Receivings ", L, T);
			doc.text("= "+(amounts - discs) , 445, T);
			T=T+20;
			doc.text("Received ", L, T);
			doc.text("- "+$("#sellRec").val() , 445, T);
			T=T+30;
			doc.text("Change ", L, T);
			doc.text("= "+$("#sellCh").val()  , 445, T);
			
/*			doc.fromHTML(document.getElementById("iDiv").innerHTML, L, T);
			L=L-450;
			T=T+20;
			doc.text("Total "+$("#sellCC").val(), L, T);
*/			
			doc.setFont("Arial");
			doc.setFontType("bold");
	        doc.setFontSize(17);
	        doc.text("THANKS FOR SHOPPING WITH US ", 10, doc.internal.pageSize.height - 10);
			doc.autoPrint({variant: "non-confirm"});
			doc.save("receipt.pdf");
			$('input').val('');
		//})
	//})
	
}