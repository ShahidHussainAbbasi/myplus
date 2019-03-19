package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "sell", uniqueConstraints = { @UniqueConstraint(columnNames = "sell_id") })
public class Sell implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "sell_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	private Long itemId;

	private Long customerId;

//	@OneToOne(fetch = FetchType.EAGER, optional = false)
//	@JoinColumn(name = "item_type_id")
//	private ItemType ItemType;
//
//	@OneToOne(fetch = FetchType.EAGER, optional = false)
//	@JoinColumn(name = "item_unit_id")
//	private ItemUnit itemUnit;

	private Float quantity;

	@Column(name = "purchase_rate")
	private Float purchaseRate;

	@Column(name = "sell_rate")
	private Float sellRate;

	private Float discount;

	@Column(name = "total_amount")
	private Float totalAmount;

	@Column(name = "net_amount")
	private Float netAmount;

	@Column(name = "sell_return_profit")
	private Float srp;

	@Column(name = "discount_type")
	private String dt;

	private Float stock;

	private String description;

	@Column(updatable=false)
	private LocalDateTime dated;

	private LocalDateTime updated;



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
	 * @return the dated
	 */
	public LocalDateTime getDated() {
		return dated;
	}



	/**
	 * @param dated the dated to set
	 */
	public void setDated(LocalDateTime dated) {
		this.dated = dated;
	}



	/**
	 * @return the updated
	 */
	public LocalDateTime getUpdated() {
		return updated;
	}



	/**
	 * @param updated the updated to set
	 */
	public void setUpdated(LocalDateTime updated) {
		this.updated = updated;
	}

	/**
	 * @return the srp
	 */
	public Float getSrp() {
		return srp;
	}

	/**
	 * @param srp the srp to set
	 */
	public void setSrp(Float srp) {
		this.srp = srp;
	}

	/**
	 * @return the dt
	 */
	public String getDt() {
		return dt;
	}

	/**
	 * @param dt the dt to set
	 */
	public void setDt(String dt) {
		this.dt = dt;
	}



	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}