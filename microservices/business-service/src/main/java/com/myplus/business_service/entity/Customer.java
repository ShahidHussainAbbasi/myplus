package com.myplus.business_service.entity;
import java.math.BigDecimal;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.myplus.business_service.entity.enums.CustomerType;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
@Entity
@Table(name = "customer", uniqueConstraints = { @UniqueConstraint(columnNames = "customer_id") })
public class Customer implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "cust_gen", sequenceName = "cust_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "cust_gen")	
	@Column(name = "customer_id", unique = true, nullable = false)
	private Long customerId;

	@Column(name = "name", nullable = false)
	private String name;

	/**
	 * How the shop classifies this customer; the B2B/B2C channel is DERIVED from it
	 * ({@link CustomerType#isB2B()}), so there is one field to set and no second column to disagree with.
	 * Null on rows written before V29 and by older clients — always read it through
	 * {@link CustomerType#orDefault}, which yields WALK_IN (today's behaviour).
	 * VARCHAR-backed on purpose: adding a value later must not need an ALTER … MODIFY.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "customer_type", length = 16)
	private CustomerType customerType;

	@Column(name = "contact", unique = true, nullable = false)
	private String contact;

	@Column(name = "email")
	private String email;

	private String address;

	/**
	 * B2B-P3g (V35): trade-buyer identity printed on an invoice.
	 *
	 * <p>{@code city} is captured rather than parsed out of {@link #address}, which is one free-text line
	 * with no reliable way to recover a city from it afterwards.
	 *
	 * <p>{@code licenseNo}/{@code licenseExpiry} are the BUYER's licence: a pharmaceutical distributor may
	 * only supply a licensed reseller, and the invoice prints it as evidence. The SELLER's own licence is a
	 * per-org setting ({@code pos.document.licenseNo}), because a business has one licence, not one per
	 * customer.
	 */
	@Column(name = "city", length = 80)
	private String city;

	@Column(name = "cnic", length = 20)
	private String cnic;

	@Column(name = "license_no", length = 60)
	private String licenseNo;

	@Column(name = "license_expiry")
	private LocalDate licenseExpiry;

	// @Column(name = "paid_amount")
	// private Float paidAmount;

    @Column(name = "due_amount", precision = 19, scale = 2)
    private BigDecimal dueAmount;

    /** Store credit (SF-5 Model B): redeemable credit the customer holds (a liability). Cached — summed from
     *  store_credit_txn by recomputeCredit. Null == 0. */
    @Column(name = "credit_balance", precision = 19, scale = 2)
    private BigDecimal creditBalance;

    /** Party bridge (P1): the shared party/contact master id (party-service). Stamped best-effort on write; null
     *  until bridged. Lets a POS customer, a vendor and (later) a pharmacy patient resolve to ONE identity. */
    @Column(name = "party_id")
    private Long partyId;

    /**
     * B2B P1 (#9): the most this customer may owe. NULL = no limit, which is every customer until an owner
     * sets one — so the guard is inert by default and nothing changes for an existing shop.
     */
    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    /**
     * B2B Phase 4a — the customer row whose limit and pooled balance govern this account's credit.
     *
     * <p><b>Self</b> for a standalone customer or a company head; the <b>company's</b> customer id for a branch.
     * Under the shared-pool rule a company sets ONE limit and its branches all draw on it, so exposure is
     * Σ(dueAmount) across everyone pointing here, measured against the limit on the row pointed to.
     *
     * <p>STAMPED when the hierarchy is edited, never resolved per sale. The credit check runs on the sell path,
     * and the hierarchy lives in party-service — deriving it at sale time would put a cross-service hop on the
     * hottest path in the POS. Same rule as the product last-rates: write it when the source changes.
     *
     * <p>Never null after V36 (which backfills {@code id → id}), so a standalone customer's group is exactly
     * itself and the arithmetic is unchanged for every customer that predates the hierarchy.
     */
    @Column(name = "credit_account_customer_id")
    private Long creditAccountCustomerId;

    /**
     * B2B P1 (#9): net payment terms in days (Net 30/60). NULL = no terms; the invoice due date stays
     * hand-entered as it is today. Feeds the EXISTING ageing report, whose buckets are only as good as the
     * due dates behind them.
     */
    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays;

    @Column(name = "due_date")
    private LocalDate dueDate;
	
	@Column(name = "dated", updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;

    // @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    // private List<CustomerHistory> customerHistory = new ArrayList<>();

	@Column(name = "user_id", nullable = false)
	private Long userId;

	/**
	 * OMS O7 D2d — the field rep who covers this outlet (V38). The TERRITORY assignment.
	 *
	 * <p>Distinct from {@link #userId}, which is <b>audit</b> — who created the row. Those are the same person
	 * in a shop and different people in field sales, where the company creates the outlet and a rep sells to
	 * it; conflating them is what made the booking screen's picker come back empty for the one role that needs
	 * it.
	 *
	 * <p><b>Null means unassigned, and unassigned means visible to every rep in the org</b> — the same rule
	 * the platform already applies to location grants, so a distributor who has configured no territories works
	 * exactly as before. Assignment is set in D6 (beat plan); the picker honours this field from day one, so
	 * nothing has to be rewritten when it does.
	 */
	@Column(name = "assigned_rep_user_id")
	private Long assignedRepUserId;

	@Column(name = "user_type")
	private String userType;

	@Column(name = "organization_id")
	private Long organizationId;       // tenant scope (from gateway X-Org-Id); user_id kept as audit

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}