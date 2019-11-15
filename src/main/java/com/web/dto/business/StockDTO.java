package com.web.dto.business;

import java.io.Serializable;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class StockDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	@Getter@Setter
	private Long stockId;

	@Getter@Setter
	private String batchNo;

	@Getter@Setter
	private Long userId;

	@Getter@Setter
	private String userType;	

	@Getter@Setter
	private Float stock=0.0F;

	@Getter@Setter
	private Float bpurchaseRate=0.0F;
	
	@Getter@Setter
	private Float bsellRate=0.0F;
	
	@Getter@Setter
	private String bpurchaseDiscountType="%";
	
	@Getter@Setter
	private String bsellDiscountType="%";
	
	@Getter@Setter
	private Float bpurchaseDiscount=0.0F;
	
	@Getter@Setter
	private Float bsellDiscount=0.0F;

	@Getter@Setter
	private String bmfgDate;
	
	@Getter@Setter
	private String bexpDate;

	private String dated;

	private String updated;


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