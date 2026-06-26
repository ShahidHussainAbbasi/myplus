package com.myplus.business_service.entity;
import java.math.BigDecimal;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;


import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
@Entity
@Table(name = "customer_history", uniqueConstraints = {
		@UniqueConstraint(columnNames = "customer_history_id"),
		@UniqueConstraint(name = "uq_ch_org_invoice_seq", columnNames = {"organization_id", "invoice_seq"}) })  // per-org invoice series
public class CustomerHistory implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "cust_gen", sequenceName = "cust_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "cust_gen")	
	@Column(name = "customer_history_id", unique = true, nullable = false)
	private Long customer_history_id;

	@Column(updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	@Column(name = "organization_id")
	private Long organizationId;       // tenant scope (from gateway X-Org-Id); user_id kept as audit

	@Column(name = "invoice_seq")
	private Long invoiceSeq;           // per-org running number (1,2,3…); ordering/uniqueness key

	@Column(name = "invoice_no")
	private String invoiceNo;          // display form, e.g. INV-000123

	@ManyToOne(fetch = jakarta.persistence.FetchType.EAGER, cascade = CascadeType.MERGE)
	@JoinColumn(name = "customer_id", referencedColumnName = "customer_id", nullable = true)
	private Customer customer;


    @Column(name = "paid_amount", precision = 19, scale = 2)
    private BigDecimal paidAmount;


    @Column(name = "due_amount", precision = 19, scale = 2)
    private BigDecimal dueAmount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    // G3 (slice 35): invoice money summary. subTotal = Σ line net; taxTotal = Σ line tax; grandTotal = subTotal+tax.
    // Null for legacy invoices. Settlement (paid/due vs grandTotal) is finalised in G5 (payments).
    @Column(name = "sub_total", precision = 19, scale = 2)
    private BigDecimal subTotal;

    @Column(name = "tax_total", precision = 19, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", precision = 19, scale = 2)
    private BigDecimal grandTotal;

    // G5 (slice 37): payment summary. paymentMode = single method name | SPLIT | null (legacy/unpaid). The per-
    // tender breakdown lives in the Payment table (linked by customer_history_id). dueAmount above settles to grand_total.
    @Column(name = "payment_mode")
    private String paymentMode;

    @Column(name = "tendered_amount", precision = 19, scale = 2)
    private BigDecimal tenderedAmount;

    @Column(name = "change_amount", precision = 19, scale = 2)
    private BigDecimal changeAmount;

    // POS day-close (slice 39): the cashier shift this sale belongs to (null if no shift was open). Drives the
    // X/Z report aggregation.
    @Column(name = "shift_id")
    private Long shiftId;

    // Sell↔stock saga state (slice 33, U3). Null for legacy local-Stock sells. UD1 = invoice-as-saga-state:
    // a stuck PENDING (with reservationId) is re-driven to CONFIRMED by the scheduled recovery relay (U3c).
    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "saga_status")
    private String sagaStatus;            // PENDING | CONFIRMED | FAILED

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}


}