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

	// private ItemDTO item;
	private Long itemId = 0L;

	// M4b (slice 91): productId-native sale line. Carried through the /addSell proxy to business-service so the saga
	// uses productId directly (no itemId→ItemCatalogMap translation) — the path toward retiring Item (M4e).
	private Long productId;

	private String itemName;
	
	private String itemCode;	

	private String description;

	// private String customerName;

	private Float quantity=1F;

	/**
	 * The rate this line actually SOLD at — the cashier's price, which may override the catalog price.
	 * <p>This field was missing, and the omission was silent: the browser sent it, this DTO dropped it on the
	 * relay, and business-service then fell back to the catalog price. A line sold at 850 off a 1000 catalog
	 * price was stored as {@code sell_rate=1000, total_amount=850} — internally contradictory, and it inflated
	 * every margin report (cost 800 → "profit 200" instead of 50). BigDecimal, not Float: this is money.
	 */
	private java.math.BigDecimal sellRate;

	private Float totalAmount=0.0F;

	private Float netAmount=0.0F;

	private Float srp=0.0F;
	
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