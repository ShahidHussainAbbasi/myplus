package com.myplus.business_service.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/**
 * SF-11: an audit record (credit-note stub) for a sale return. Sale returns previously mutated the invoice in
 * place with no trace; this captures who/what/why/how-much so returns have an audit trail and a basis for a
 * printable credit note later. Tenant-scoped (organization_id + user_id). One row per return action.
 */
@Data
@Entity
@Table(name = "sale_return")
public class SaleReturn implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	/**
	 * B2B-P3c (#1): per-org credit-note sequence. UNIQUE(organization_id, credit_note_seq) is what makes the
	 * MAX+1 allocation safe under concurrency — the same guarantee invoice_seq has used since slice 22.
	 */
	@Column(name = "credit_note_seq")
	private Long creditNoteSeq;

	/** B2B-P3c (#1): this document's OWN number, e.g. {@code CRN-000007}. Null on returns taken before 3c. */
	@Column(name = "credit_note_no", length = 32)
	private String creditNoteNo;

	/**
	 * The invoice this return REVERSES — a reference, not this document's identity.
	 *
	 * <p>Before 3c this was the only number a return had, which made a credit note indistinguishable from the
	 * invoice it cancelled. It stays, because referencing the reversed document is the accounting rule.
	 */
	@Column(name = "invoice_no")
	private String invoiceNo;

	@Column(name = "sell_id")
	private Long sellId;

	@Column(name = "product_id")
	private Long productId;

	private Float quantity;

	@Column(name = "reason")
	private String reason;

	@Column(name = "refund_amount", precision = 19, scale = 2)
	private BigDecimal refundAmount;

	// B2B-P3f: the credit note's FACE VALUE (returned goods + their tax). Distinct from refundAmount, which is
	// only the CASH handed back and is zero on a credit sale -- so refundAmount could never serve as the
	// document's value. Null means the return predates V34: its value is unrecoverable (a full return deleted
	// the sell row), so the statement omits the line rather than inventing a number for a customer document.
	@Column(name = "credit_amount", precision = 19, scale = 2)
	private BigDecimal creditAmount;

	@Column(name = "organization_id")
	private Long organizationId;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "store_id")
	private Long storeId;              // multi-location: the store this return was taken at (null = single-store/legacy)

	@Column(name = "dated")
	private LocalDateTime dated;

	/*
	 * ── CN-1: the document's own copy of what was returned (V65) ─────────────────────────────────
	 *
	 * A credit note must be reproducible from its own row. `quantity` above is the SHELF figure — 0.075 of a
	 * box — which is the right unit for stock and money and the wrong one for the piece of paper the customer
	 * keeps. The customer's view lives on the Sell line, and a FULL return DELETES that line, so there is
	 * nothing to look up at print time. These five columns are copied at return time for the same reason
	 * Sell.packSizeSnapshot and CustomerHistory.issuedTotal exist.
	 *
	 * All nullable: rows written before V65 have none of it and fall back to the old behaviour.
	 */

	/** LOOSE when the returned line was sold by the piece; null/PACK otherwise. */
	@Column(name = "sold_unit", length = 16)
	private String soldUnit;

	/** PIECES returned (3 tablets) — not the shelf fraction. Float to match {@code Sell.soldQuantity}. */
	@Column(name = "sold_quantity")
	private Float soldQuantity;

	/** Price per PIECE at the time of sale. */
	@Column(name = "sold_rate", precision = 19, scale = 2)
	private BigDecimal soldRate;

	/** The pack size that applied AT THE SALE — a product's pack size can be edited afterwards. */
	@Column(name = "pack_size_snapshot")
	private Integer packSizeSnapshot;

	/**
	 * The SHELF-unit (pack) rate at the time of sale.
	 *
	 * <p>⚠ Not a loose-selling field: this is what makes a FULLY returned credit note printable at all. The
	 * reader resolved the rate from the Sell row, and a full return deletes it, so every fully-returned note
	 * printed with no rate — pack sales included.
	 */
	@Column(name = "unit_rate", precision = 19, scale = 2)
	private BigDecimal unitRate;
}
