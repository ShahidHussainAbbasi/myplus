package com.web.dto.business;

import java.io.Serializable;

import com.validation.ValidateEmpty;

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

	@ValidateEmpty
	private Long itemId;
	
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

	private Float totalAmount;

	private Float netAmount;

	private Float purchaseExpense;

	private String purchaseExpenseDesc;

	private String description;

	private String dated;

	private String updated;

	@ValidateEmpty
	private Float quantity;

	private StockDTO stock;

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