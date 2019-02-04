package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

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

	private String code;

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

	private Float net;

	private String description;

//	@OneToMany(cascade= CascadeType.REFRESH)
//	@NotFound(action = NotFoundAction.IGNORE)
	@ElementCollection
//	@CollectionTable(name ="tracks" , joinColumns=@JoinColumn(name="playlist_id"))
//	@Column(name="track")
//	private Set<Long> itemUnitIds = new HashSet<>();
	@OneToOne(fetch = FetchType.EAGER)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "item_type_id")
	private ItemType itemType;

//	@OneToMany(orphanRemoval = false)
//	@NotFound(action = NotFoundAction.IGNORE)
	@ElementCollection
//	private Set<Long> itemTypeIds = new HashSet<>();

	@OneToOne(fetch = FetchType.EAGER)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "item_unit_id")
	private ItemUnit itemUnit;

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
	 * @return the code
	 */
	public String getCode() {
		return code;
	}

	/**
	 * @param code the code to set
	 */
	public void setCode(String code) {
		this.code = code;
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
//
//	/**
//	 * @return the itemUnits
//	 */
//	public List<ItemUnit> getItemUnits() {
//		return itemUnits;
//	}
//
//	/**
//	 * @param itemUnits the itemUnits to set
//	 */
//	public void setItemUnits(List<ItemUnit> itemUnits) {
//		this.itemUnits = itemUnits;
//	}
//
//	/**
//	 * @return the itemTypes
//	 */
//	public List<ItemType> getItemTypes() {
//		return itemTypes;
//	}
//
//	/**
//	 * @param itemTypes the itemTypes to set
//	 */
//	public void setItemTypes(List<ItemType> itemTypes) {
//		this.itemTypes = itemTypes;
//	}
//
//	/**
//	 * @return the company
//	 */
//	public Company getCompany() {
//		return company;
//	}
//
//	/**
//	 * @param company the company to set
//	 */
//	public void setCompany(Company company) {
//		this.company = company;
//	}

//	/**
//	 * @return the vender
//	 */
//	public Vender getVender() {
//		return vender;
//	}
//
//	/**
//	 * @param vender the vender to set
//	 */
//	public void setVender(Vender vender) {
//		this.vender = vender;
//	}

	/**
	 * @return the dated
	 */
	public LocalDateTime getDated() {
		return dated;
	}

//	/**
//	 * @return the itemUnitIds
//	 */
//	public Set<Long> getItemUnitIds() {
//		return itemUnitIds;
//	}
//
//	/**
//	 * @param itemUnitIds the itemUnitIds to set
//	 */
//	public void setItemUnitIds(Set<Long> itemUnitIds) {
//		this.itemUnitIds = itemUnitIds;
//	}
//
//	/**
//	 * @return the itemTypeIds
//	 */
//	public Set<Long> getItemTypeIds() {
//		return itemTypeIds;
//	}
//
//	/**
//	 * @param itemTypeIds the itemTypeIds to set
//	 */
//	public void setItemTypeIds(Set<Long> itemTypeIds) {
//		this.itemTypeIds = itemTypeIds;
//	}
//

	/**
	 * @return the itemType
	 */
	public ItemType getItemType() {
		return itemType;
	}

	/**
	 * @param itemType the itemType to set
	 */
	public void setItemType(ItemType itemType) {
		this.itemType = itemType;
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