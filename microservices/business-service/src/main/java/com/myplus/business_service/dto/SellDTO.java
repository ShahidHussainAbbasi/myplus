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

	/**
	 * B2B-P2 (#10): WHY this line is priced the way it is — e.g. "Wholesale price −12%" or "Contract price".
	 * Server-populated on the way out; ignored on the way in.
	 *
	 * <p>The reason is the point of the slice, not decoration: today a trade customer's price is a number a
	 * cashier typed and nothing records why, which is exactly what makes a disputed invoice unanswerable.
	 */
	private String priceReason;

	private BigDecimal totalAmount = BigDecimal.ZERO;

	private BigDecimal netAmount = BigDecimal.ZERO;

	// Line discount amount + type ("%" / amount). Null for modern saga sells (discount is folded into
	// net at cart time); kept for legacy lines and the report's Discount column.
	private BigDecimal discount;

	private String dt;

	private BigDecimal srp = BigDecimal.ZERO;

	// The rate this line was actually SOLD at — the cashier may override the catalog price on the sell screen.
	private BigDecimal sellRate;

	// The catalog master price at the moment of sale (snapshot) — lets reports compare catalog price vs sold rate.
	private BigDecimal catalogPrice;

	// SF-10: unit cost (COGS) snapshot — carried to the Sale Detail Report so the UI can show per-line margin.
	private BigDecimal costPrice;

	// G3 (slice 35): applied tax on this line, for the read/receipt path.
	private BigDecimal taxRate;

	private BigDecimal taxAmount;

	private Float itemStock=0.0F;

	private String dated;

	private String updated;

	private String cc="";

	private String cn="";

	// Sale report: invoice + settlement context pulled from the line's CustomerHistory (invoice) and Customer.
	private String invoiceNo;

	private String paymentMode;

	private BigDecimal dueAmount;      // per-invoice balance (negative = customer still owes)

	private String dueDate;

	private BigDecimal grandTotal;     // invoice tax-inclusive total (repeats per line of the same invoice)

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