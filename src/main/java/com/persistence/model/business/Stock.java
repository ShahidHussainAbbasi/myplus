package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "stock", uniqueConstraints = { @UniqueConstraint(columnNames = "stock_id") })
public class Stock implements Serializable {

	public Stock() {
	}

	private static final long serialVersionUID = 1L;


	
	@Id
	@SequenceGenerator(name = "stock_gen", sequenceName = "stock_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "stock_gen")	
	@Column(name = "stock_id", unique = true, nullable = false)
	@Getter@Setter
	private Long stockId;

	@Column(name = "batch_no")
	@Getter@Setter
	private String batchNo;

	@Column(name = "user_id", nullable = false)
	@Getter@Setter
	private Long userId;

	@Column(name = "user_type")
	@Getter@Setter
	private String userType;

	@Column(name = "item_id", nullable = false)
	@Getter@Setter
	private Long itemId;

	@Getter@Setter
	@Column(name = "stock")
	private Float stock;

	@Getter@Setter
	@Column(name = "batch_purchase_rate")
	private Float bpurchaseRate;
	
	@Getter@Setter
	@Column(name = "batch_sale_rate")
	private Long bsellRate;
	
	@Getter@Setter
	@Column(name = "batch_purchaseDiscountType")
	private String bpurchaseDiscountType;
	
	@Getter@Setter
	@Column(name = "batch_saleDiscountType")
	private String bsellDiscountType;
	
	@Getter@Setter
	@Column(name = "batch_purchaseDiscount")
	private Float bpurchaseDiscount;
	
	@Getter@Setter
	@Column(name = "batch_saleDiscount")
	private Float bsellDiscount;

	@Getter@Setter
	@Column(name = "bmfg_date")
	private LocalDate bmfgDate;
	
	@Getter@Setter
	@Column(name = "bexp_date")
	private LocalDate bexpDate;

	@Getter@Setter
	private LocalDate dated;

	@Getter@Setter
	private LocalDate updated;
	
	@OneToMany(mappedBy="stock")
	private List<Purchase> purchases;
	
	
	@OneToMany(mappedBy="stock")
//	@JoinColumn(name = "sell_id", referencedColumnName = "sell_id")
	private List<Sell> sales = new ArrayList<>();	

}