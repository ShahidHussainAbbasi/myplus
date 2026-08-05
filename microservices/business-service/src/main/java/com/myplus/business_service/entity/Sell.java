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

	@Column(name = "store_id")
	private Long storeId;              // multi-location: the store this sale occurred at (null = single-store/legacy)

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

	// The catalog master price snapshot at the moment this line was sold (reports: catalog price vs sold rate).
	@Column(name = "catalog_price", precision = 19, scale = 2)
	private BigDecimal catalogPrice;

	// B2B-P2 (#10): WHY this line was priced as it was — "Wholesale price −12%", "Contract price".
	// A SNAPSHOT of the human reason, not the rule id: a rule can be edited or deleted later, and an invoice
	// must still explain itself years afterwards. NULL = priced at catalog (every legacy row).
	@Column(name = "price_reason", length = 64)
	private String priceReason;

	// SF-10: unit COST (COGS) snapshot at sale time — the product's latest purchase rate — so reports can show
	// true per-line margin = netAmount − costPrice×quantity. Null for legacy sells / never-purchased products.
	@Column(name = "cost_price", precision = 19, scale = 2)
	private BigDecimal costPrice;

	@Column(precision = 19, scale = 2)
	private BigDecimal discount;

	/**
	 * B2B-P3g (V35): free goods issued with this line — the "Bon." column on a distribution invoice, where
	 * 20 units are billed and 2 given free. A quantity, so it follows {@link #quantity}'s {@code Float}.
	 *
	 * <p><b>Presentation only (decision D-2, open).</b> Bonus stock does NOT decrement inventory: making it
	 * do so has to run through the sell↔stock saga and post to the GL at zero revenue, which is materially
	 * more than a column and is not smuggled in behind one.
	 */
	@Column(name = "bonus_quantity")
	private Float bonusQuantity;

	@Column(name = "total_amount", precision = 19, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "net_amount", precision = 19, scale = 2)
	private BigDecimal netAmount;

	@Column(name = "sell_return_profit", precision = 19, scale = 2)
	private BigDecimal srp;

	// G3 (slice 35): applied tax on this line. taxRate is the % used; taxAmount is the money. Null for legacy sells.
	@Column(name = "tax_rate", precision = 19, scale = 2)
	private BigDecimal taxRate;

	@Column(name = "tax_amount", precision = 19, scale = 2)
	private BigDecimal taxAmount;

	@Column(name = "discount_type")
	private String dt;

	private String description;

	@Column(updatable=false)
	private LocalDateTime dated;

	private LocalDateTime updated;
	
	@Column(name = "return_reason")
	private Float re;

	// M3c.4f (slice 88): the Sell→local-Stock FK was removed; the sell carries productId (saga writes it directly).

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