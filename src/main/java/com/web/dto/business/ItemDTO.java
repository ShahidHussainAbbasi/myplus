package com.web.dto.business;

import java.io.Serializable;
import java.util.Set;

import com.validation.ValidateEmpty;

/**
 * The persistent class for the doctor database table.
 * 
 */

public class ItemDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private Long itemCode;

	private Long userId;

	private String userType;

	@ValidateEmpty
	private String name;

	@ValidateEmpty
	private Float purchaseAmount;

	@ValidateEmpty
	private Float sellAmount;

	private Float discount;

	@ValidateEmpty
	private Float net;

	private String description;

	private Long companyId;

	private String companyName;

	private Set<Long> venderIds;

	private Set<String> venderNames;

	@ValidateEmpty
	private Set<Long> itemUnitIds;

	private Set<String> itemUnitNames;

	@ValidateEmpty
	private Set<Long> itemTypeIds;

	private Set<String> itemTypeNames;

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
	 * @return the itemCode
	 */
	public Long getItemCode() {
		return itemCode;
	}

	/**
	 * @param itemCode the itemCode to set
	 */
	public void setItemCode(Long itemCode) {
		this.itemCode = itemCode;
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
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the purchaseAmount
	 */
	public Float getPurchaseAmount() {
		return purchaseAmount;
	}

	/**
	 * @param purchaseAmount the purchaseAmount to set
	 */
	public void setPurchaseAmount(Float purchaseAmount) {
		this.purchaseAmount = purchaseAmount;
	}

	/**
	 * @return the sellAmount
	 */
	public Float getSellAmount() {
		return sellAmount;
	}

	/**
	 * @param sellAmount the sellAmount to set
	 */
	public void setSellAmount(Float sellAmount) {
		this.sellAmount = sellAmount;
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
	 * @return the net
	 */
	public Float getNet() {
		return net;
	}

	/**
	 * @param net the net to set
	 */
	public void setNet(Float net) {
		this.net = net;
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
	 * @return the venderIds
	 */
	public Set<Long> getVenderIds() {
		return venderIds;
	}

	/**
	 * @param venderIds the venderIds to set
	 */
	public void setVenderIds(Set<Long> venderIds) {
		this.venderIds = venderIds;
	}

	/**
	 * @return the venderNames
	 */
	public Set<String> getVenderNames() {
		return venderNames;
	}

	/**
	 * @param venderNames the venderNames to set
	 */
	public void setVenderNames(Set<String> venderNames) {
		this.venderNames = venderNames;
	}

	/**
	 * @return the itemUnitIds
	 */
	public Set<Long> getItemUnitIds() {
		return itemUnitIds;
	}

	/**
	 * @param itemUnitIds the itemUnitIds to set
	 */
	public void setItemUnitIds(Set<Long> itemUnitIds) {
		this.itemUnitIds = itemUnitIds;
	}

	/**
	 * @return the itemUnitNames
	 */
	public Set<String> getItemUnitNames() {
		return itemUnitNames;
	}

	/**
	 * @param itemUnitNames the itemUnitNames to set
	 */
	public void setItemUnitNames(Set<String> itemUnitNames) {
		this.itemUnitNames = itemUnitNames;
	}

	/**
	 * @return the itemTypeIds
	 */
	public Set<Long> getItemTypeIds() {
		return itemTypeIds;
	}

	/**
	 * @param itemTypeIds the itemTypeIds to set
	 */
	public void setItemTypeIds(Set<Long> itemTypeIds) {
		this.itemTypeIds = itemTypeIds;
	}

	/**
	 * @return the itemTypeNames
	 */
	public Set<String> getItemTypeNames() {
		return itemTypeNames;
	}

	/**
	 * @param itemTypeNames the itemTypeNames to set
	 */
	public void setItemTypeNames(Set<String> itemTypeNames) {
		this.itemTypeNames = itemTypeNames;
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