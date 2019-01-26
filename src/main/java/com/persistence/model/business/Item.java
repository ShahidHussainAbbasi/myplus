package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "item", uniqueConstraints = { @UniqueConstraint(columnNames = "item_id") })
public class Item implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "item_id", unique = true, nullable = false)
	private Long id;

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

	@OneToMany(orphanRemoval = false)
	@NotFound(action = NotFoundAction.IGNORE)
	private List<ItemUnit> itemUnits;

	@OneToMany(orphanRemoval = false)
	@NotFound(action = NotFoundAction.IGNORE)
	private List<ItemType> itemTypes;

	@OneToOne(optional = false, fetch = FetchType.LAZY)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "company_id")
	private Company company;

	@OneToOne(optional = false)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "vender_id")
	private List<Vender> venders;

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
	 * @return the itemUnits
	 */
	public List<ItemUnit> getItemUnits() {
		return itemUnits;
	}

	/**
	 * @param itemUnits the itemUnits to set
	 */
	public void setItemUnits(List<ItemUnit> itemUnits) {
		this.itemUnits = itemUnits;
	}

	/**
	 * @return the itemTypes
	 */
	public List<ItemType> getItemTypes() {
		return itemTypes;
	}

	/**
	 * @param itemTypes the itemTypes to set
	 */
	public void setItemTypes(List<ItemType> itemTypes) {
		this.itemTypes = itemTypes;
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
	 * @return the venders
	 */
	public List<Vender> getVenders() {
		return venders;
	}

	/**
	 * @param venders the venders to set
	 */
	public void setVenders(List<Vender> venders) {
		this.venders = venders;
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