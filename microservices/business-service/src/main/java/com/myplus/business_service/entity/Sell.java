package com.myplus.business_service.entity;
import java.math.BigDecimal;

import java.io.Serializable;
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

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
 @Entity
@Table(name = "sell", uniqueConstraints = { @UniqueConstraint(columnNames = "sell_id") })

public class Sell implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "sell_gen", sequenceName = "sell_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "sell_gen")	
	@Column(name = "sell_id", unique = true, nullable = false)
	private Long sellId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	@Column(name = "organization_id")
	private Long organizationId;       // tenant scope (from gateway X-Org-Id); user_id kept as audit

	// @OneToOne(fetch = FetchType.LAZY, optional = true)
	// @NotFound(action = NotFoundAction.IGNORE)
	// @JoinColumn(name = "item_id")
	// private Item item;


//	@OneToOne(fetch = FetchType.EAGER, optional = false)
//	@JoinColumn(name = "item_type_id")
//	private ItemType ItemType;
//
//	@OneToOne(fetch = FetchType.EAGER, optional = false)
//	@JoinColumn(name = "item_unit_id")
//	private ItemUnit itemUnit;

	private Float quantity;

	@Column(name = "sell_rate", precision = 19, scale = 2)
	private BigDecimal sellRate;

	@Column(precision = 19, scale = 2)
	private BigDecimal discount;

	@Column(name = "total_amount", precision = 19, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "net_amount", precision = 19, scale = 2)
	private BigDecimal netAmount;

	@Column(name = "sell_return_profit", precision = 19, scale = 2)
	private BigDecimal srp;

	@Column(name = "discount_type")
	private String dt;

	private String description;

	@Column(updatable=false)
	private LocalDateTime dated;

	private LocalDateTime updated;
	
	@Column(name = "return_reason")
	private Float re;

	@ManyToOne(fetch = FetchType.EAGER, cascade = CascadeType.MERGE)
	@JoinColumn(name = "stock_id")
	private Stock stock;

	// Saga sells (slice 33, U3) reference the catalog product directly; the local Stock FK is null then.
	@Column(name = "product_id")
	private Long productId;

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.MERGE)
    @JoinColumn(name = "customer_history_id", nullable = true)
    private CustomerHistory  customerHistory;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}