package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
@JsonIgnoreProperties(ignoreUnknown=true)
public class SellDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long sellId = 0L;

	private Long userId = 0L;

	private String userType;

	// Catalog product id — the picker submits this directly (M4e.d: the Item/itemId bridge is retired).
	private Long productId;

	private String itemName;
	
	private String itemCode;	

	private String description;

	// private String customerName;

	private Float quantity=1F;

	private BigDecimal totalAmount = BigDecimal.ZERO;

	private BigDecimal netAmount = BigDecimal.ZERO;

	private BigDecimal srp = BigDecimal.ZERO;

	// The rate this line was actually SOLD at — the cashier may override the catalog price on the sell screen.
	private BigDecimal sellRate;

	// The catalog master price at the moment of sale (snapshot) — lets reports compare catalog price vs sold rate.
	private BigDecimal catalogPrice;

	// G3 (slice 35): applied tax on this line, for the read/receipt path.
	private BigDecimal taxRate;

	private BigDecimal taxAmount;

	private Float itemStock=0.0F;

	private String dated;

	private String updated;

	private String cc="";
	
	private String cn="";
	
	private Float re=0.0F;
	
	private String sd;
	
	private String ed;

	private Integer rp;
	
	//StockDTO table
	private StockDTO stock;
	
	private Long sellSId = 0L;

	private Integer due_days = 0;

	private CustomerDTO customer;

	private CustomerHistoryDTO customerHistory;
	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}