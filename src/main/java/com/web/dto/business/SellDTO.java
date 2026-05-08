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

	private Long sellId = 0L;

	private Long userId = 0L;

	private String userType;

	private Long itemId = 0L;

	private String itemName;
	
	private String itemCode;	

	// private String customerName;

	private Float quantity=1F;

	private Float totalAmount=0.0F;

	private Float netAmount=0.0F;

	private Float srp=0.0F;
	
	private Float stock=0.0F;

	private String description;

	private String dated;

	private String updated;

	private String cc="";
	
	private String cn="";
	
	private Float re=0.0F;
	
	private String sd;
	
	private String ed;

	private Integer rp;
	
	//StockDTO table
	private StockDTO stockDTO;
	
	private Long sellSId = 0L;

	private Integer due_days = 0;

	private CustomerDTO customerDTO;

	private CustomerHistoryDTO customerHistoryDTO;
	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}