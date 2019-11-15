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

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "sell", uniqueConstraints = { @UniqueConstraint(columnNames = "sell_id") })
@Data
public class Sell implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "sell_gen", sequenceName = "sell_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "sell_gen")	
	@Column(name = "sell_id", unique = true, nullable = false)
	private Long sellId;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	private Long itemId;

	private Long customerId;

//	@OneToOne(fetch = FetchType.EAGER, optional = false)
//	@JoinColumn(name = "item_type_id")
//	private ItemType ItemType;
//
//	@OneToOne(fetch = FetchType.EAGER, optional = false)
//	@JoinColumn(name = "item_unit_id")
//	private ItemUnit itemUnit;

	private Float quantity;

	@Column(name = "purchase_rate")
	private Float purchaseRate;

	@Column(name = "sell_rate")
	private Float sellRate;

	private Float discount;

	@Column(name = "total_amount")
	private Float totalAmount;

	@Column(name = "net_amount")
	private Float netAmount;

	@Column(name = "sell_return_profit")
	private Float srp;

	@Column(name = "discount_type")
	private String dt;

	private String description;

	@Column(updatable=false)
	private LocalDateTime dated;

	private LocalDateTime updated;

//	@Column(name = "received")
//	@Getter@Setter
//	private String R;
//	
//	@Column(name = "balance")
//	@Getter@Setter
//	private String B;
	
	@Column(name = "customer_name")
	@Getter@Setter
	private String cn;
	
	@Column(name = "customer_contact")
	@Getter@Setter
	private String cc;
	
	@Column(name = "return_reason")
	@Getter@Setter
	private Float re;

//	@ManyToOne(fetch = FetchType.EAGER,cascade=CascadeType.ALL)
//	@JoinColumn(name = "stock_id")
//	private Stock stock;
	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name="stock_id")
	@Getter@Setter
	private Stock stock;	

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}