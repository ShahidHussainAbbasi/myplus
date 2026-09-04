package com.myplus.business_service.entity;
import java.math.BigDecimal;

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

	@Column(name = "organization_id")
	private Long organizationId;       // tenant scope (from gateway X-Org-Id); user_id kept as audit

	@Column(name = "store_id")
	private Long storeId;              // multi-location: the store this purchase was recorded at (null = single-store/legacy)

	// M3c.4f (slice 88): the Purchase→local-Stock FK was removed; the purchase is self-describing (M3b) + dual-writes to inventory.

	// M3b (slice 75): the purchase is self-describing — its batch/rate snapshot lives here, not on a local Stock row.
	// M4e.d (slice 106): the legacy item_id column is retired (Item entity gone); productId is the catalog identity.
	@Column(name = "product_id")
	private Long productId;
	@Column(name = "batch_no")
	private String batchNo;
	@Column(name = "bpurchase_rate", precision = 19, scale = 2)
	private BigDecimal bpurchaseRate;
	@Column(name = "bsell_rate", precision = 19, scale = 2)
	private BigDecimal bsellRate;
	@Column(name = "bpurchase_discount", precision = 19, scale = 2)
	private BigDecimal bpurchaseDiscount;
	@Column(name = "bsell_discount", precision = 19, scale = 2)
	private BigDecimal bsellDiscount;
	@Column(name = "bpurchase_discount_type")
	private String bpurchaseDiscountType;
	@Column(name = "bsell_discount_type")
	private String bsellDiscountType;
	@Column(name = "bexp_date")
	private LocalDate bexpDate;
	
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

	/**
	 * #17 P2 — FREE units received on top of the billed quantity ("buy 10, get 1").
	 *
	 * <p>A fact of THIS delivery, stamped at write, not a re-computation of the supplier's current offer: a
	 * scheme can be edited or expire tomorrow, and what physically arrived today must not change when it does.
	 *
	 * <p>Null on every purchase that carried no bonus, which is most of them.
	 */
	@Column(name = "bonus_quantity")
	private Float bonusQuantity;

	/**
	 * #17 P2 — what was actually PAID for this line, so cost can be allocated across the units RECEIVED.
	 *
	 * <p>Stored rather than derived because the derivation loses money: 5,000 over 11 units is 454.54 a unit,
	 * and 454.54 x 11 is 4,999.94. Keeping the total lets consumption allocate exactly instead of rounding a
	 * per-unit figure and hoping the pieces add up.
	 *
	 * <p>Null on historical rows, where it means "rate x quantity" — which is precisely what those rows have
	 * always meant.
	 */
	@Column(name = "paid_total", precision = 19, scale = 2)
	private java.math.BigDecimal paidTotal;

	/** #17 P2 — the scheme that produced the bonus, for traceability. Opaque: bonus_scheme lives in catalog. */
	@Column(name = "bonus_scheme_code", length = 64)
	private String bonusSchemeCode;

	/**
	 * Units that actually entered stock = billed + free. The single definition, so goods-in, the return
	 * clawback and any report cannot each compute it differently.
	 */
	public float receivedQuantity() {
		return (quantity != null ? quantity : 0f) + (bonusQuantity != null ? bonusQuantity : 0f);
	}

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

	@Column(name = "total_amount", precision = 19, scale = 2)
	private BigDecimal totalAmount;

	// B2B-P3f: the bill AS ISSUED, GROSS (goods + input tax) -- the basis dueAmount settles on, and the basis
	// purchase_return.amount is recorded in, so the statement's bill and its debit notes net exactly. Captured
	// once, at the first return; back-filled for history by V34 (reconstructable here, unlike the sale side).
	// ONLY the vendor statement reads this; totalAmount keeps its current meaning for every other reader.
	@Column(name = "issued_total", precision = 19, scale = 2)
	private BigDecimal issuedTotal;

	@Column(name = "net_amount", precision = 19, scale = 2)
	private BigDecimal netAmount = null;

	// Tax register Phase B: input tax on this bill (captured only when the org's "Purchase tax" toggle is on).
	// totalAmount = the goods/net value; the vendor bill you owe = totalAmount + taxAmount.
	@Column(name = "tax_rate", precision = 19, scale = 2)
	private BigDecimal taxRate;
	@Column(name = "tax_amount", precision = 19, scale = 2)
	private BigDecimal taxAmount;

	// F1 (AP): the vendor this purchase is billed from, and how much has been paid to them for THIS bill.
	// dueAmount = paidAmount − netAmount (negative while we still owe the vendor); the vendor's running payable
	// = −Σ(due). A null vendor / fully-paid purchase carries no payable (cash purchase or legacy row).
	@Column(name = "vender_id")
	private Long venderId;
	@Column(name = "paid_amount", precision = 19, scale = 2)
	private BigDecimal paidAmount;
	@Column(name = "due_amount", precision = 19, scale = 2)
	private BigDecimal dueAmount;

	/**
	 * OB-1 — {@code SALE} for every bill the shop recorded, {@code OPENING} for what it already owed a
	 * supplier at cutover. The mirror of {@code CustomerHistory.docType}; the reasoning is there.
	 */
	@Column(name = "doc_type", nullable = false, length = 16)
	private String docType = com.myplus.business_service.service.OpeningBalanceService.DOC_SALE;

	@Column(name = "purchase_expense")
	private Float purchaseExpense;

	@Column(name = "purchase_expense_desc")
	private String purchaseExpenseDesc;

	private String description;

	@Column(updatable=false)
	private LocalDateTime dated;

	private LocalDateTime updated;

	@Column(name = "purchase_invoice_no")
	private String purchaseInvoiceNo;

	// Audit #3 (void/cancel): bill lifecycle. ACTIVE by default; a void reverses stock/AP/GL and stamps who/when/why.
	@Column(name = "status")
	private String status;                // ACTIVE | VOID  (null == ACTIVE for legacy rows)
	@Column(name = "voided_by")
	private Long voidedBy;
	@Column(name = "voided_at")
	private LocalDateTime voidedAt;
	@Column(name = "void_reason")
	private String voidReason;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}