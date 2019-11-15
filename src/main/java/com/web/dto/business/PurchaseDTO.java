package com.web.dto.business;

import java.io.Serializable;

import com.validation.ValidateEmpty;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class PurchaseDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	@Getter@Setter
	private Long purchaseId;

	@Getter@Setter
	private Long userId;

	@Getter@Setter
	private String userType;
////Item table
//	@Getter@Setter
//	private ItemDTO itemDTO;

	@ValidateEmpty
	@Getter@Setter
	private Long itemId;
	
	@Getter@Setter
	private Long pstockId;

	@Getter@Setter
	private String iname;

	@Getter@Setter
	private String icode;

//	@Getter@Setter
//	private CompanyDTO companyDTO;
//
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

	@Getter@Setter
	private Float totalAmount;

	@Getter@Setter
	private Float netAmount;

	@Getter@Setter
	private Float purchaseExpense;

	@Getter@Setter
	private String purchaseExpenseDesc;

	@Getter@Setter
	private String description;

	@Getter@Setter
	private String dated;

	@Getter@Setter
	private String updated;

	@ValidateEmpty
	@Getter@Setter
	private Float quantity;

	//StockDTO table
	@Getter@Setter
	private StockDTO stockDTO;
	
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