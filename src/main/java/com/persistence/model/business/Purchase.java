package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity(name="purchase")
@Table(name = "purchase", uniqueConstraints = { @UniqueConstraint(columnNames = "purchase_id") })
public class Purchase implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "purch_gen", sequenceName = "purch_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "purch_gen")	
	@Column(name = "purchase_id", unique = true, nullable = false)
	@Getter@Setter
	private Long purchaseId;

	@Column(name = "user_id")
	@Getter@Setter
	private Long userId;

	@Column(name = "user_type")
	@Getter@Setter
	private String userType;

/*	@ManyToOne(fetch = FetchType.EAGER,cascade=CascadeType.ALL)
//	@JoinColumn(name="COUNTRY_ID", nullable=false) 	
//	@OneToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "stock_id", nullable=true,unique=false)
	@Getter@Setter
	private Stock stock;*/
	
	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name="stock_id")
	@Getter@Setter
	private Stock stock;
	

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

	@Getter@Setter
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
	@Getter@Setter
	private Float totalAmount;

	@Column(name = "net_amount")
	@Getter@Setter
	private Float netAmount = null;

	@Column(name = "purchase_expense")
	@Getter@Setter
	private Float purchaseExpense;

	@Column(name = "purchase_expense_desc")
	@Getter@Setter
	private String purchaseExpenseDesc;

	@Getter@Setter
	private String description;

	@Column(updatable=false)
	@Getter@Setter
	private LocalDateTime dated;

	@Getter@Setter
	private LocalDateTime updated;


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}