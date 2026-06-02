package com.myplus.business_service.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import lombok.Data;

// import lombok.Getter;
// import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
@Entity(name="purchase")
@Table(name = "purchase", uniqueConstraints = { @UniqueConstraint(columnNames = "purchase_id") })
public class Purchase implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "purch_gen", sequenceName = "purch_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "purch_gen")	
	@Column(name = "purchase_id", unique = true, nullable = false)
	private Long purchaseId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "stock_id", nullable=true,unique=false)
	private Stock stock;
	
	// @OneToOne(fetch = FetchType.EAGER, optional = true)
	// @NotFound(action = NotFoundAction.IGNORE)
	// @JoinColumn(name="item_id")
	// @Getter@Setter
	// private Item item;
	

//	@Column(name = "stock_batchNo")
//	@Getter@Setter
//	private String sbatchNO;
	
//	@OneToOne(fetch = FetchType.LAZY, optional = false)
//	@JoinColumn(name = "company_id")
//	private Company Company;
//
//	@OneToOne(fetch = FetchType.LAZY, optional = false)
//	@JoinColumn(name = "vender_id")
//	private Vender vender;

//	@OneToOne(fetch = FetchType.LAZY, optional = false)
//	@JoinColumn(name = "item_type_id")
//	private ItemType ItemType;
//
//	@OneToOne(fetch = FetchType.LAZY, optional = false)
//	@JoinColumn(name = "item_unit_id")
//	private ItemUnit itemUnit;

	private Float quantity;

//	@Column(name = "purchase_rate")
//	@Getter@Setter
//	private Float purchaseRate;
//
//	@Column(name = "sell_rate")
//	private Float sellRate;

//	private Float discount;

//	@Getter@Setter
//	@Column(name = "disc_type")
//	private String discountType;

	@Column(name = "total_amount")
	private Float totalAmount;

	@Column(name = "net_amount")
	private Float netAmount = null;

	@Column(name = "purchase_expense")
	private Float purchaseExpense;

	@Column(name = "purchase_expense_desc")
	private String purchaseExpenseDesc;

	private String description;

	@Column(updatable=false)
	private LocalDateTime dated;

	private LocalDate updated;

	@Column(name = "purchase_invoice_no")
	private String purchaseInvoiceNo;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}