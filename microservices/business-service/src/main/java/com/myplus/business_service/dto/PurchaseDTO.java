package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.io.Serializable;

import com.myplus.business_service.validation.ValidateEmpty;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
public class PurchaseDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long purchaseId;

	private Long userId;

	private String userType;

	// M4e.d (slice 106): productId-native purchase — the form submits productId directly (Item + itemId bridge retired).
	private Long productId;

	private Long pstockId;

	private String iname;

	private String icode;

//	@Getter@Setter
//	private Long companyId;
//
//	@Getter@Setter
//	private String companyName;
//
//	@Getter@Setter
//	private Long venderId;
//
//	@Getter@Setter
//	private String venderName;
//
//	@Getter@Setter
//	private Long itemTypeId;
//
//	@Getter@Setter
//	private Long itemUnitId;
//
	
//	@Getter@Setter
//	private String pdiscountType = "%";
//	
//	@Getter@Setter
//	private Float pdiscount=0F;

	private BigDecimal totalAmount;

	private BigDecimal netAmount;

	// Tax register Phase B: input-tax rate (%) entered on the purchase form when "Purchase tax" is enabled.
	private BigDecimal taxRate;

	// The input tax money on this bill (0 unless the org captures purchase tax). Gross bill = totalAmount + taxAmount.
	private BigDecimal taxAmount;

	// Audit #3: ACTIVE | VOID (null == ACTIVE). The list read needs it so the UI shows a VOID badge (not Return/Void
	// buttons), greys the row, and the hide-voided filter can catch it.
	private String status;

	// F1 (AP): the vendor the purchase is billed from + how much was paid at purchase time (defaults to net = cash).
	private Long venderId;
	private String venderName;
	private BigDecimal paidAmount;

	// Audit #5: client-supplied key to dedup a double-submit of this purchase (blank = no dedup).
	private String idempotencyKey;

	/**
	 * B2B-P1 (#9): the operator has seen the supplier credit-limit warning and chosen to continue.
	 * Inbound only, and NOT persisted — it describes this submission, not the bill.
	 */
	private Boolean creditAcknowledged;

	private Float purchaseExpense;

	private String purchaseExpenseDesc;

	private String description;

	private String dated;

	private String updated;

	@ValidateEmpty
	private Float quantity;

	private StockDTO stock;

	private String purchaseInvoiceNo;

	// private ItemDTO item;
	
//	@Getter@Setter
//	private String batchId;
//
//	@Getter@Setter
//	private String batchNo;
//
//	@Getter@Setter
//	private Float bpurchaseRate;
//	
//	@Getter@Setter
//	private Long bsaleRate;
//	
//	@Getter@Setter
//	private String bpurchaseDiscountType;
//	
//	@Getter@Setter
//	private String bsaleDiscountType;
//	
//	@Getter@Setter
//	private Long bpurchaseDiscount;
//	
//	@Getter@Setter
//	private Long bsaleDiscount;
//
//	@Getter@Setter
//	private LocalDate bmfgDate;
//	
//	@Getter@Setter
//	private LocalDate bexpDate;


	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}