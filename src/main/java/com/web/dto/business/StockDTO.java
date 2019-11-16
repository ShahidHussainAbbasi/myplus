package com.web.dto.business;

import java.io.Serializable;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
public class StockDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long stockId;

	private String batchNo;

	private Long userId;

	private String userType;	

	private Float stock=0.0F;

	private Float bpurchaseRate=0.0F;
	
	private Float bsellRate=0.0F;
	
	private String bpurchaseDiscountType="%";
	
	private String bsellDiscountType="%";
	
	private Float bpurchaseDiscount=0.0F;
	
	private Float bsellDiscount=0.0F;

	private String bmfgDate;
	
	private String bexpDate;

	private String dated;

	private String updated;
	
	private String iDesc;


	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Stock [stockId=" + stockId + ", userId=" + userId + ", userType=" + userType + ", batchNo=" + batchNo
				+ ", bpurchaseRate=" + bpurchaseRate + ", bsellRate=" + bsellRate
				+ ", bpurchaseDiscountType=" + bpurchaseDiscountType + ", bsellDiscountType=" + bsellDiscountType
				+ ", bpurchaseDiscount=" + bpurchaseDiscount + ", bsellDiscount=" + bsellDiscount + ", bmfgDate="
				+ bmfgDate + ", bexpDate=" + bexpDate + ", dated=" + dated + ", updated=" + updated + "]";
	}
}