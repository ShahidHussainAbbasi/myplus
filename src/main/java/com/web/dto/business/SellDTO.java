package com.web.dto.business;

import java.io.Serializable;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class SellDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private Long userId;

	private String userType;

	private Long itemId;

	private String itemName;

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

	private Float purchaseRate;

	private Float sellRate;

	private Float discount=0.0F;

	private Float totalAmount;

	private Float netAmount;

	private Float stock;

//	private Float sellExpense;
//
//	private String sellExpenseDesc;

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
	 * @return the customerId
	 */
	public Long getCustomerId() {
		return customerId;
	}

	/**
	 * @param customerId the customerId to set
	 */
	public void setCustomerId(Long customerId) {
		this.customerId = customerId;
	}

	/**
	 * @return the customerName
	 */
	public String getCustomerName() {
		return customerName;
	}

	/**
	 * @param customerName the customerName to set
	 */
	public void setCustomerName(String customerName) {
		this.customerName = customerName;
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