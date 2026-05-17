package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


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
	private Long id;

	@Column(updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	@ManyToOne(fetch = javax.persistence.FetchType.LAZY, cascade = CascadeType.PERSIST)
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