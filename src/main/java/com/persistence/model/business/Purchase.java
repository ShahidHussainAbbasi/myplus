package com.persistence.model.business;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "purchase",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "purchase_id")
        }
)
public class Purchase implements Serializable {
	private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="purchase_id", unique = true, nullable = false)
	private Long id;

	@Column(name="user_id")
	private Long userId;

	@Column(name="user_type")
	private String userType;
	
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name="purchase_id")
    private Collection<Item> purchaseItems = new ArrayList<>();
	
    @Column(name = "company_name")
	private String companyName;

	@Column(name = "vender_name")
	private String venderName;
	
	private Float quantity;
	
	@Column(name="purchase_rate")
 	private Float purchaseRate;
	
	@Column(name="sell_rate")
	private Float sellRate;
	
	private Float discount  = null;
	
	@Column(name="total_amount")
	private Float totalAmount;

	@Column(name="net_amount")
	private Float netAmount = null;

	private Float stock;
	@Column(name="other_expense")

	private String mobile;
	
	private String dated;
	
	private String description;

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
	 * @return the purchaseItems
	 */
	public Collection<Item> getPurchaseItems() {
		return purchaseItems;
	}

	/**
	 * @param purchaseItems the purchaseItems to set
	 */
	public void setPurchaseItems(Collection<Item> purchaseItems) {
		this.purchaseItems = purchaseItems;
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
	 * @return the net
	 */
	public Float getNetAmount() {
		return netAmount;
	}

	/**
	 * @param net the net to set
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
	 * @return the mobile
	 */
	public String getMobile() {
		return mobile;
	}

	/**
	 * @param mobile the mobile to set
	 */
	public void setMobile(String mobile) {
		this.mobile = mobile;
	}

	/**
	 * @return the dated
	 */
	public String getDated() {
		return dated;
	}

	/**
	 * @param dated the dated to set
	 */
	public void setDated(String dated) {
		this.dated = dated;
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
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}
	
	

}