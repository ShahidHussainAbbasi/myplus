package com.myplus.business_service.entity;
import java.math.BigDecimal;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.NaturalId;

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

	@Column(name = "organization_id")
	@Getter@Setter
	private Long organizationId;       // tenant scope (from gateway X-Org-Id); user_id kept as audit

	@Column(name = "user_type")
	@Getter@Setter
	private String userType;

	@NaturalId
	@Column(name = "item_id", unique = true, nullable = false)
	@Getter@Setter
	private Long itemId;

	@Getter@Setter
	@Column(name = "stock")
	private Float stock;

	@Getter@Setter
	@Column(name = "batch_purchase_rate", precision = 19, scale = 2)
	private BigDecimal bpurchaseRate;
	
	@Getter@Setter
	@Column(name = "batch_sale_rate", precision = 19, scale = 2)
	private BigDecimal bsellRate;
	
	@Getter@Setter
	@Column(name = "batch_purchaseDiscountType")
	private String bpurchaseDiscountType;
	
	@Getter@Setter
	@Column(name = "batch_saleDiscountType")
	private String bsellDiscountType;
	
	@Getter@Setter
	@Column(name = "batch_purchaseDiscount", precision = 19, scale = 2)
	private BigDecimal bpurchaseDiscount;
	
	@Getter@Setter
	@Column(name = "batch_saleDiscount", precision = 19, scale = 2)
	private BigDecimal bsellDiscount;

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
	
	@OneToMany(mappedBy="stock", fetch = jakarta.persistence.FetchType.EAGER)
	private List<Purchase> purchases;
	
	

}