package com.persistence.model.pharmacy;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "sell",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "id")
        }
)
public class Sell implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	@Column(name="id")
	private Long id;

	private String dated;

	private Float discount;
	
	@Column(name="unit_purchase_price")
	private Float unitPurchasePrice;
	
	private Float quantity;
	
	private Float weight;
	
	@Column(name="unit_sell_price")
	private Float unitSellPrice;
	
	@Column(name="in_stock")
	private Double inStock;
	
	private Float profit;
	
	@Column(name="other_expense")
	private Float otherExpense;
	
	@Column(name="net_profit")
	private Float netProfit;
	

	//bi-directional many-to-one association to ItemDTO
	@ManyToOne
	@JoinColumn(name="FK_item_id")
	private Item item;

	//bi-directional many-to-one association to CompanyDTO
	@ManyToOne
	@JoinColumn(name="FK_company_id")
	private Company company;
	
	//bi-directional many-to-one association to VenderDTO
	@ManyToOne
	@JoinColumn(name="FK_vender_id")
	private Vender vender;

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
	 * @return the unitPurchasePrice
	 */
	public Float getUnitPurchasePrice() {
		return unitPurchasePrice;
	}

	/**
	 * @param unitPurchasePrice the unitPurchasePrice to set
	 */
	public void setUnitPurchasePrice(Float unitPurchasePrice) {
		this.unitPurchasePrice = unitPurchasePrice;
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
	 * @return the weight
	 */
	public Float getWeight() {
		return weight;
	}

	/**
	 * @param weight the weight to set
	 */
	public void setWeight(Float weight) {
		this.weight = weight;
	}

	/**
	 * @return the unitSellPrice
	 */
	public Float getUnitSellPrice() {
		return unitSellPrice;
	}

	/**
	 * @param unitSellPrice the unitSellPrice to set
	 */
	public void setUnitSellPrice(Float unitSellPrice) {
		this.unitSellPrice = unitSellPrice;
	}

	/**
	 * @return the inStock
	 */
	public Double getInStock() {
		return inStock;
	}

	/**
	 * @param inStock the inStock to set
	 */
	public void setInStock(Double inStock) {
		this.inStock = inStock;
	}

	/**
	 * @return the profit
	 */
	public Float getProfit() {
		return profit;
	}

	/**
	 * @param profit the profit to set
	 */
	public void setProfit(Float profit) {
		this.profit = profit;
	}

	/**
	 * @return the otherExpense
	 */
	public Float getOtherExpense() {
		return otherExpense;
	}

	/**
	 * @param otherExpense the otherExpense to set
	 */
	public void setOtherExpense(Float otherExpense) {
		this.otherExpense = otherExpense;
	}

	/**
	 * @return the netProfit
	 */
	public Float getNetProfit() {
		return netProfit;
	}

	/**
	 * @param netProfit the netProfit to set
	 */
	public void setNetProfit(Float netProfit) {
		this.netProfit = netProfit;
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
	 * @return the vender
	 */
	public Vender getVender() {
		return vender;
	}

	/**
	 * @param vender the vender to set
	 */
	public void setVender(Vender vender) {
		this.vender = vender;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Purchase [id=" + id + ", dated=" + dated + ", discount=" + discount + ", unitPurchasePrice=" + unitPurchasePrice + ", quantity=" + quantity
				+ ", weight=" + weight + ", unitSellPrice=" + unitSellPrice + ", inStock=" + inStock + ", profit="
				+ profit + ", otherExpense=" + otherExpense + ", netProfit=" + netProfit + ", item=" + item
				+ ", company=" + company + ", vender=" + vender + "]";
	}
	
}