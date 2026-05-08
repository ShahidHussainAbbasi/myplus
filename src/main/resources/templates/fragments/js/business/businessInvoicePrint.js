var invGarmtsLogoL = "resources/img/logos/gll.jpg";
var invGarmtsLogoR = "resources/img/exangeOfGoods.jpg";

function pGarmtsInv(data){
	if(userId*ONE != 37)//519)
		return false;
	
	var doc = new jsPDF('p', 'pt', 'a4');
	var L = 200; var T = 25;
	toDataURL(invGarmtsLogoR, function(dataUrl) {
		doc.setFont("Cambria");
		doc.setFontSize(30);
		doc.text("Haider Garments", L, T);//Company receipt name
		L=L-10;//240
		T=T+18;
		doc.addFont()
		doc.setFontType("bold");
		doc.setFontSize(16);
		doc.text("Link road model town A Khanpur", L, T);//company address
		L=L+70;//270
		T=T+18;
		doc.text("03053939495", L, T);//company contact number
	
		T=T+30;
		doc.text("Sales Invoice", L, T);
		L=L-25;//250
	
		T=T+10;
		doc.line(0, T, 650, T)
		L=L-180;//10
		T=T+15;
		doc.text("Dated: "+currentFormattedDateTime(), L, T);
		L=L+480;//510
		T=T+5;
		doc.line(0, T, 650, T)
		L=L-480;//10
		T=T+17;
		doc.text("Customer: ", L, T);
		L=L+80;
		doc.text($("#sellCN").val()+" "+$("#sellCC").val(), L, T);//doc.fromHTML($("#sellCN").val(), L, T);
		L=L-80;
		T=T+15;
		
		doc.text("Sale's Person", L, T);
		L=L+430;
		doc.text("", L, T);//doc.fromHTML($("#sellCC").val(), L, T);
		L=L-430;
		T=T+50;
	
		doc.setFontSize(18);
		doc.text("Item Name", L, T);
		L=L+280;
		doc.text("Qty", L, T);
		L=L+55;
		doc.text("Price", L, T);
		L=L+55;
		doc.text("Disc", L, T);
		L=L+60;
		doc.text("Amount", L, T);
	
		L=L-450;
		var items=0,qtys=0,prices=0,discs=0,amounts=0;
		T=T+30;
		doc.setFontSize(16);
		data.forEach(function(o,i){
			var total = 0;
			var dis = 0;
			if(o.dt=="%"){
				dis = o.totalAmount*ONE * o.discount/100;
			}else{
				dis = o.discount*ONE;
			}
			doc.text(o.name, L, T);
			L=L+280;
			doc.text(o.quantity+"", L, T);
			L=L+55;
			doc.text(o.sellRate+"", L, T);
			L=L+55;
			doc.text(dis+"", L, T);
			L=L+60;
			doc.text(o.totalAmount+"", L, T);
			
			T=T+20;
			qtys+=o.quantity*ONE;
			prices+=o.sellRate*ONE;
			discs+=dis;
			amounts+=o.totalAmount*ONE;
			L=L-450;
		});
		T=T+10;
		doc.line(0, T, 650, T)
		T=T+25;
		doc.text("Totals", L, T);
		L=L+280;
		doc.text(qtys+"", L, T);
		L=L+55;
		doc.text((Math.round(prices)).toFixed(2)+"", L, T);
		L=L+55;
		doc.text((Math.round(discs)).toFixed(2)+"", L, T);
		L=L+60;
		doc.text((Math.round(amounts)).toFixed(2)+"", L, T);
		L=L-170;
		T=T+35;
		if(discs*ONE>0){
			doc.text("Special discount", L, T);
			L=L+170;
			doc.text(discs.toFixed(2)+"", L, T);
			L=L-170;
		}
		T=T+20;
		doc.text("Receivings: ", L, T);
		L=L+170;
		doc.text(Math.round((amounts - discs)).toFixed(2)+"", L, T);
		L=L-170;
		T=T+20;
		doc.text("Received: ", L, T);
		L=L+170;
		doc.text(Math.round(($("#sellRec").val())).toFixed(2)+"", L, T);
		T=T+30;
		L=L-170;
		doc.text("Change", L, T);
		L=L+170;
		doc.text(Math.round(($("#sellCh").val())).toFixed(2), L, T);
		L=L-170;
	
		L=L-270;
		T=T+60;
//	    doc.setFontSize(14);
	    doc.addImage(dataUrl, "JPG", L, T, 1000, 300);
		T=T+15;
		doc.addFont()
	    doc.setFontSize(12);
	   // doc.text("Please bring receipt for exchange of goods ", L, T);
		T=T+10;
	    doc.text("THANKS FOR SHOPPING WITH US ", L, T);
		T=T+20;
	    doc.text("Abbasi Soft Engineering. +92 03114499660", L, T);
		T=T+10;
	    doc.text("https://maxtheservice.com/login ", L, T);
	    //doc.text("Please bring receipt for exchange of goodsسامان کی تبادلہ کے لئے براہ مہربانی رسید لائیں ", 10, doc.internal.pageSize.height - 10);
	    
		doc.autoPrint({variant: "non-confirm"});
//		doc.autoPrint();
		window.open(doc.output('bloburl'), '_blank');
//		window.print();
	});
//	$('input').val('');
	
}