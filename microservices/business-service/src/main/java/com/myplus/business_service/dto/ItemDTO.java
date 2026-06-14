package com.myplus.business_service.dto;

import java.io.Serializable;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */

 @Data
public class ItemDTO implements Serializable {
	
	private static final long serialVersionUID = 1L;

	private Long id;

	private String icode;

	private Long userId;

	private String userType;

	private String iname;

//	@Getter@Setter
//	private Float purchaseAmount;
//
//	@Getter@Setter
//	private Float sellAmount;
//
//	@Getter@Setter
//	private Float discount=0.0F;
//	
//	@Getter@Setter
//	private String discountType = "%";
//
//	@Getter@Setter
//	private Float net;
//
//	@Getter@Setter
//	private String expDateStr;
//
//	@Getter@Setter
//	private Float stock=0.0F;
	
	private Long companyId;

	private String companyName;

	private Long venderId;

	private String venderName;

//	@Getter@Setter
//	private Set<Long> itemUnitIds;
//	
//	@Getter@Setter
//	private Long itemUnitId;
//
//	@Getter@Setter
//	private Set<String> itemUnitNames;
//	
//	@Getter@Setter
//	private String itemUnitName;
//
//	@Getter@Setter
//	private Set<Long> itemTypeIds;
//
//	@Getter@Setter
//	private Long itemTypeId;
//
//	@Getter@Setter
//	private String itemTypeName;
//
//	@Getter@Setter
//	private Set<String> itemTypeNames;

	private String dated;

	private String updated;
	
	private String idesc;

	private StockDTO stock;
//	@Getter@Setter
//	private String bn;
	
	public static long getSerialversionuid() {
		return serialVersionUID;
	}
}