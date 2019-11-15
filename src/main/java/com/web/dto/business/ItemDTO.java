package com.web.dto.business;

import java.io.Serializable;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */

public class ItemDTO implements Serializable {
	
	private static final long serialVersionUID = 1L;

	@Getter@Setter
	private Long id;

	@Getter@Setter
	private String icode;

	@Getter@Setter
	private Long userId;

	@Getter@Setter
	private String userType;

	@Getter@Setter
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
	
	@Getter@Setter
	private Long companyId;

	@Getter@Setter
	private String companyName;

	@Getter@Setter
	private Long venderId;

	@Getter@Setter
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

	@Getter@Setter
	private String dated;

	@Getter@Setter
	private String updated;
	
	@Getter@Setter
	private String idesc;
	
//	@Getter@Setter
//	private String bn;
	
	public static long getSerialversionuid() {
		return serialVersionUID;
	}
}