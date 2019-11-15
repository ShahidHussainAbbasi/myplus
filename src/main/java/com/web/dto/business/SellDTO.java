package com.web.dto.business;

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

	private Long sellId;

	private Long userId;

	private String userType;

	private Long itemId;

	private String itemName;
	
	private String itemCode;	

	private Long customerId;

	private String customerName;

//	private Long itemTypeId;
//
//	private String itemTypeName;
//
//	private Long itemUnitId;
//
//	private String itemUnitName;

	private Float quantity=1F;

//	private Float purchaseRate=0.0F;

//	private Float sellRate=0.0F;

//	private Float discount=0.0F;

	private Float totalAmount=0.0F;

	private Float netAmount=0.0F;

	private Float srp=0.0F;
	
	private Float stock=0.0F;

//	private Float sellExpense;
//
//	private String sellExpenseDesc;

	private String description;

	private String dated;

	private String updated;

//	private String dt="Rs";
	
//	@Getter@Setter
//	private String R;
//	
//	@Getter@Setter
//	private String B;
	
	private String cc="";
	
	private String cn="";
	
	private Float re=0.0F;
	
	private String sd;
	
	private String ed;

	private Integer rp;
	
	//StockDTO table
	private StockDTO stockDTO;
	
	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}