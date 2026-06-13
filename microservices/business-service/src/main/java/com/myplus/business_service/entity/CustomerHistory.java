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

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}


}