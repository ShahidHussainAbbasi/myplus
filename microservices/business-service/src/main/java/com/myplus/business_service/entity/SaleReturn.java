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

	@Column(name = "organization_id")
	private Long organizationId;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "dated")
	private LocalDateTime dated;
}
