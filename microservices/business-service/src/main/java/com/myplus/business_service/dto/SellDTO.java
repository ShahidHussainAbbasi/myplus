package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.myplus.business_service.entity.Item;

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

	// private ItemDTO item;
	private Long itemId = 0L;

	// Catalog product id (slice 33, U4.3). The catalog-backed picker submits this directly; when null the
	// saga falls back to translating itemId via ItemCatalogMap (back-compat with the legacy picker).
	private Long productId;

	private String itemName;
	
	private String itemCode;	

	private String description;

	// private String customerName;

	private Float quantity=1F;

	private BigDecimal totalAmount = BigDecimal.ZERO;

	private BigDecimal netAmount = BigDecimal.ZERO;

	private BigDecimal srp = BigDecimal.ZERO;
	
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