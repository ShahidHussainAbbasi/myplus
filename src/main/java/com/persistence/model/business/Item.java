package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "item")
public class Item implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id	
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "item_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "description")
	@Getter@Setter
	private String desc;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	private String name;

	@Column(name = "purchase_amount")
	private Float purchaseAmount;

	@Column(name = "sell_amount")
	private Float sellAmount;

	private Float discount;

	private String discountType;

	private Float net;

	private LocalDate expDate;

	private Float stock;

//	@OneToMany(cascade= CascadeType.REFRESH)
//	@NotFound(action = NotFoundAction.IGNORE)
	@ElementCollection
//	@CollectionTable(name ="tracks" , joinColumns=@JoinColumn(name="playlist_id"))
//	@Column(name="track")
//	private Set<Long> itemUnitIds = new HashSet<>();
//	@OneToOne(fetch = FetchType.EAGER)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "item_type_id")
//	private ItemType itemType;

//	@OneToMany(orphanRemoval = false)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@ElementCollection
//	private Set<Long> itemTypeIds = new HashSet<>();

//	@OneToOne(fetch = FetchType.EAGER)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "item_unit_id")
//	private ItemUnit itemUnit;

	@OneToOne(fetch = FetchType.EAGER)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "company_id")
	private Company company;

//	@OneToOne(fetch = FetchType.LAZY)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "vender_id")
//	@MapsId
//	@OneToOne(fetch = FetchType.LAZY)
//	@MapsId
	private Long venderId;

	@Column(updatable = false)
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
	 * @return the expDate
	 */
	public LocalDate getExpDate() {
		return expDate;
	}

	/**
	 * @param expDate the expDate to set
	 */
	public void setExpDate(LocalDate expDate) {
		this.expDate = expDate;
	}

	/**
	 * @return the discountType
	 */
	public String getDiscountType() {
		return discountType;
	}

	/**
	 * @param discountType the discountType to set
	 */
	public void setDiscountType(String discountType) {
		this.discountType = discountType;
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

//	/**
//	 * @return the itemType
//	 */
//	public ItemType getItemType() {
//		return itemType;
//	}
//
//	/**
//	 * @param itemType the itemType to set
//	 */
//	public void setItemType(ItemType itemType) {
//		this.itemType = itemType;
//	}
//
//	/**
//	 * @return the itemUnit
//	 */
//	public ItemUnit getItemUnit() {
//		return itemUnit;
//	}
//
//	/**
//	 * @param itemUnit the itemUnit to set
//	 */
//	public void setItemUnit(ItemUnit itemUnit) {
//		this.itemUnit = itemUnit;
//	}

	/**
	 * @return the company
	 */
	public Company getCompany() {
		return company;
	}

	/**
	 * @param company the company to set
	 */
	public void setCompany(Company company) {
		this.company = company;
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