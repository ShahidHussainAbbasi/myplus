package com.web.dto.business;

import java.io.Serializable;

import com.validation.ValidateEmpty;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class PurchaseDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private Long userId;

	private String userType;

	@ValidateEmpty
	private Long itemId;

	private String itemName;

	private Long companyId;

	private String companyName;

	private Long venderId;

	private String venderName;

	private Long itemTypeId;

	private Long itemUnitId;

	@ValidateEmpty
	private Float quantity;

	private Float purchaseRate;

	private Float sellRate;

	private Float discount=0F;

	private Float totalAmount;

	private Float netAmount;

	private Float stock=0.0F;

	private Float purchaseExpense;

	private String purchaseExpenseDesc;

	private String description;

	private String datedStr;

	private String updatedStr;

	
	/**
	 * @return the id
	 */
	public Long getId() {
		return id;
	}


	/**
	 * @param id the id to set
	 */
	public void setId(Long id) {
		this.id = id;
	}


	/**
	 * @return the userId
	 */
	public Long getUserId() {
		return userId;
	}


	/**
	 * @param userId the userId to set
	 */
	public void setUserId(Long userId) {
		this.userId = userId;
	}


	/**
	 * @return the userType
	 */
	public String getUserType() {
		return userType;
	}


	/**
	 * @param userType the userType to set
	 */
	public void setUserType(String userType) {
		this.userType = userType;
	}


	/**
	 * @return the itemId
	 */
	public Long getItemId() {
		return itemId;
	}


	/**
	 * @param itemId the itemId to set
	 */
	public void setItemId(Long itemId) {
		this.itemId = itemId;
	}


	/**
	 * @return the itemName
	 */
	public String getItemName() {
		return itemName;
	}


	/**
	 * @param itemName the itemName to set
	 */
	public void setItemName(String itemName) {
		this.itemName = itemName;
	}


	/**
	 * @return the companyId
	 */
	public Long getCompanyId() {
		return companyId;
	}


	/**
	 * @param companyId the companyId to set
	 */
	public void setCompanyId(Long companyId) {
		this.companyId = companyId;
	}


	/**
	 * @return the companyName
	 */
	public String getCompanyName() {
		return companyName;
	}


	/**
	 * @param companyName the companyName to set
	 */
	public void setCompanyName(String companyName) {
		this.companyName = companyName;
	}


	/**
	 * @return the venderId
	 */
	public Long getVenderId() {
		return venderId;
	}


	/**
	 * @param venderId the venderId to set
	 */
	public void setVenderId(Long venderId) {
		this.venderId = venderId;
	}


	/**
	 * @return the venderName
	 */
	public String getVenderName() {
		return venderName;
	}


	/**
	 * @param venderName the venderName to set
	 */
	public void setVenderName(String venderName) {
		this.venderName = venderName;
	}


	/**
	 * @return the itemTypeId
	 */
	public Long getItemTypeId() {
		return itemTypeId;
	}


	/**
	 * @param itemTypeId the itemTypeId to set
	 */
	public void setItemTypeId(Long itemTypeId) {
		this.itemTypeId = itemTypeId;
	}


	/**
	 * @return the itemUnitId
	 */
	public Long getItemUnitId() {
		return itemUnitId;
	}


	/**
	 * @param itemUnitId the itemUnitId to set
	 */
	public void setItemUnitId(Long itemUnitId) {
		this.itemUnitId = itemUnitId;
	}


	/**
	 * @return the quantity
	 */
	public Float getQuantity() {
		return quantity;
	}


	/**
	 * @param quantity the quantity to set
	 */
	public void setQuantity(Float quantity) {
		this.quantity = quantity;
	}


	/**
	 * @return the purchaseRate
	 */
	public Float getPurchaseRate() {
		return purchaseRate;
	}


	/**
	 * @param purchaseRate the purchaseRate to set
	 */
	public void setPurchaseRate(Float purchaseRate) {
		this.purchaseRate = purchaseRate;
	}


	/**
	 * @return the sellRate
	 */
	public Float getSellRate() {
		return sellRate;
	}


	/**
	 * @param sellRate the sellRate to set
	 */
	public void setSellRate(Float sellRate) {
		this.sellRate = sellRate;
	}


	/**
	 * @return the discount
	 */
	public Float getDiscount() {
		return discount;
	}


	/**
	 * @param discount the discount to set
	 */
	public void setDiscount(Float discount) {
		this.discount = discount;
	}


	/**
	 * @return the totalAmount
	 */
	public Float getTotalAmount() {
		return totalAmount;
	}


	/**
	 * @param totalAmount the totalAmount to set
	 */
	public void setTotalAmount(Float totalAmount) {
		this.totalAmount = totalAmount;
	}


	/**
	 * @return the netAmount
	 */
	public Float getNetAmount() {
		return netAmount;
	}


	/**
	 * @param netAmount the netAmount to set
	 */
	public void setNetAmount(Float netAmount) {
		this.netAmount = netAmount;
	}


	/**
	 * @return the stock
	 */
	public Float getStock() {
		return stock;
	}


	/**
	 * @param stock the stock to set
	 */
	public void setStock(Float stock) {
		this.stock = stock;
	}


	/**
	 * @return the purchaseExpense
	 */
	public Float getPurchaseExpense() {
		return purchaseExpense;
	}


	/**
	 * @param purchaseExpense the purchaseExpense to set
	 */
	public void setPurchaseExpense(Float purchaseExpense) {
		this.purchaseExpense = purchaseExpense;
	}


	/**
	 * @return the purchaseExpenseDesc
	 */
	public String getPurchaseExpenseDesc() {
		return purchaseExpenseDesc;
	}


	/**
	 * @param purchaseExpenseDesc the purchaseExpenseDesc to set
	 */
	public void setPurchaseExpenseDesc(String purchaseExpenseDesc) {
		this.purchaseExpenseDesc = purchaseExpenseDesc;
	}


	/**
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}


	/**
	 * @param description the description to set
	 */
	public void setDescription(String description) {
		this.description = description;
	}


	/**
	 * @return the datedStr
	 */
	public String getDatedStr() {
		return datedStr;
	}


	/**
	 * @param datedStr the datedStr to set
	 */
	public void setDatedStr(String datedStr) {
		this.datedStr = datedStr;
	}


	/**
	 * @return the updatedStr
	 */
	public String getUpdatedStr() {
		return updatedStr;
	}


	/**
	 * @param updatedStr the updatedStr to set
	 */
	public void setUpdatedStr(String updatedStr) {
		this.updatedStr = updatedStr;
	}


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}