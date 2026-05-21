package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.persistence.model.business.enums.CustomerType;

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

	@Column(name = "email", unique = true, nullable = true)
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

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}