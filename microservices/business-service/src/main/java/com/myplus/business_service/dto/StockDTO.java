package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.io.Serializable;

import lombok.Data;

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

	private BigDecimal bpurchaseRate = BigDecimal.ZERO;
	
	private BigDecimal bsellRate = BigDecimal.ZERO;
	
	private String bpurchaseDiscountType="%";
	
	private String bsellDiscountType="%";
	
	private BigDecimal bpurchaseDiscount = BigDecimal.ZERO;
	
	private BigDecimal bsellDiscount = BigDecimal.ZERO;

	private String bmfgDate;
	
	private String bexpDate;

	private String dated;

	private String updated;
	
	private String iDesc;

	private Long itemId;


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