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

	/*
	 * U5 - buying in BOXES. Design: docs/slices/u5-buying-in-boxes.md
	 *
	 * A shop buys a box of 10 packs for 1000. The form asks for a quantity and a rate, so the buyer types
	 * 10 and 1000 - and the system believes a pack costs 1000 instead of 100. A TENFOLD error in the number
	 * COGS, the margin guard and every profit report read from.
	 *
	 * These two are INPUT ONLY. `purchaseUnit=BOX` plus `packsPerBox` are converted to packs and a per-pack
	 * cost on the way in, and NOTHING downstream ever hears the word "box": not the purchase row, not the
	 * stock entry, not the product. That is what keeps this an input aid rather than a second unit of
	 * measure - the design (parent 4) rejects a UoM engine, and this does not become one.
	 *
	 * packsPerBox is deliberately NOT stored on the product: box sizes vary by SHIPMENT, and a stale default
	 * would be silently wrong for this delivery, with the confidence of a pre-filled field behind it. The
	 * slice exists to prevent a unit mistake; it must not institutionalise one.
	 */
	private String purchaseUnit;

	private Integer packsPerBox;

	/**
	 * SER-2 — the serial / IMEI of each unit received on this line.
	 *
	 * <p>One per LINE in a single string, not a list, and that is forced by the transport: the monolith's
	 * purchase proxy collapses repeated parameters ({@code params.put(k, v[0])}), so {@code serials=A&serials=B}
	 * would arrive as A alone and a shop receiving ten handsets would register one — silently. Splitting here
	 * keeps the whole list intact without changing a proxy every purchase field flows through.
	 *
	 * <p>Empty or absent for the ordinary case, which is most products in most shops: a charger has no serial
	 * and never will.
	 *
	 * <p><b>Not mapped onto {@link com.myplus.business_service.entity.Purchase}.</b> A purchase row describes a
	 * DELIVERY; the units it brought in are their own records with their own lifecycle (in stock → sold →
	 * returned). Flattening them onto the purchase is what {@code InstallmentPlan.assetRef} did, and it could
	 * hold exactly one serial for exactly one financed handset.
	 */
	private String serials;

	/**
	 * SER-4 — the condition of the units received: NEW, USED or REFURBISHED.
	 *
	 * <p>Per LINE rather than per unit, because a delivery is normally graded as a lot. A mixed intake is
	 * entered as two lines, which is also how it is priced.
	 */
	private String conditionGrade;

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