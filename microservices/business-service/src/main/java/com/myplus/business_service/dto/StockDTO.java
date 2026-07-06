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
	
	// Sell discount type: "0"/absent = flat amount, "1"/"%" = percent. Default is AMOUNT so a line that
	// reaches the backend WITHOUT an explicit type (e.g. the select had no matching option → FormData omits it)
	// is treated as a flat amount, not silently as a percent (which turned a flat 10 on a 120 line into 12).
	private String bsellDiscountType="0";
	
	private BigDecimal bpurchaseDiscount = BigDecimal.ZERO;
	
	private BigDecimal bsellDiscount = BigDecimal.ZERO;

	private String bmfgDate;
	
	private String bexpDate;

	private String dated;

	private String updated;
	
	private String iDesc;

	private Long itemId;

	/** FEFO batches (batch/expiry + sellable qty) for the dispense/sell screen (slice 54, P10). */
	private java.util.List<com.myplus.commerce.contracts.dto.StockBatch> batches;


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