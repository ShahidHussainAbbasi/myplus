package com.myplus.business_service.entity;

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
@Table(name = "customer_history", uniqueConstraints = { @UniqueConstraint(columnNames = "customer_history_id") })
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

	@ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, cascade = CascadeType.PERSIST)
	@JoinColumn(name = "customer_id", referencedColumnName = "customer_id", nullable = true)
	private Customer customer;


    @Column(name = "paid_amount")
    private Float paidAmount;


    @Column(name = "due_amount")
    private Float dueAmount;

    @Column(name = "due_date")
    private LocalDate dueDate;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}


}