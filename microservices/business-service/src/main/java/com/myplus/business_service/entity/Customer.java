package com.myplus.business_service.entity;

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

	// @Enumerated(EnumType.STRING) 
	// @Column(name = "customer_type")
	// private CustomerType customerType;	

	@Column(name = "contact", unique = true, nullable = false)
	private String contact;

	@Column(name = "email")
	private String email;

	private String address;

	// @Column(name = "paid_amount")
	// private Float paidAmount;

    @Column(name = "due_amount")
    private Float dueAmount;

    @Column(name = "due_date")
    private LocalDate dueDate;
	
	@Column(name = "dated", updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;

    // @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    // private List<CustomerHistory> customerHistory = new ArrayList<>();

	@Column(name = "user_id", nullable = false)
	private Long userId;

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