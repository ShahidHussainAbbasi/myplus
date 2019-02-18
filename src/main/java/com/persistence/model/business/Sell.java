package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
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

	@OneToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "item_id")
	private Item item;

	@OneToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "customer_id")
	private Customer customer;

	@OneToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "item_type_id")
	private ItemType ItemType;

	@OneToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "item_unit_id")
	private ItemUnit itemUnit;

	private Float quantity;

	@Column(name = "purchase_rate")
	private Float purchaseRate;

	@Column(name = "sell_rate")
	private Float sellRate;

	private Float discount = null;

	@Column(name = "total_amount")
	private Float totalAmount;

	@Column(name = "net_amount")
	private Float netAmount = null;

	private Float stock;

	@Column(name = "sell_expense")
	private Float sellExpense;

	@Column(name = "sell_expense_desc")
	private String sellExpenseDesc;

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
	 * @return the item
	 */
	public Item getItem() {
		return item;
	}


	/**
	 * @param item the item to set
	 */
	public void setItem(Item item) {
		this.item = item;
	}


	/**
	 * @return the customer
	 */
	public Customer getCustomer() {
		return customer;
	}


	/**
	 * @param customer the customer to set
	 */
	public void setCustomer(Customer customer) {
		this.customer = customer;
	}


	/**
	 * @return the itemType
	 */
	public ItemType getItemType() {
		return ItemType;
	}


	/**
	 * @param itemType the itemType to set
	 */
	public void setItemType(ItemType itemType) {
		ItemType = itemType;
	}


	/**
	 * @return the itemUnit
	 */
	public ItemUnit getItemUnit() {
		return itemUnit;
	}


	/**
	 * @param itemUnit the itemUnit to set
	 */
	public void setItemUnit(ItemUnit itemUnit) {
		this.itemUnit = itemUnit;
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
	 * @return the sellExpense
	 */
	public Float getSellExpense() {
		return sellExpense;
	}


	/**
	 * @param sellExpense the sellExpense to set
	 */
	public void setSellExpense(Float sellExpense) {
		this.sellExpense = sellExpense;
	}


	/**
	 * @return the sellExpenseDesc
	 */
	public String getSellExpenseDesc() {
		return sellExpenseDesc;
	}


	/**
	 * @param sellExpenseDesc the sellExpenseDesc to set
	 */
	public void setSellExpenseDesc(String sellExpenseDesc) {
		this.sellExpenseDesc = sellExpenseDesc;
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
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}