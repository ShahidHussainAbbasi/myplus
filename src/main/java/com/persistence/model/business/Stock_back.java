/*package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

*//**
 * The persistent class for the doctor database table.
 * 
 *//*
@Entity
@Table(name = "stock", uniqueConstraints = { @UniqueConstraint(columnNames = "stock_id") })

public class Stock_back implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "stock_gen", sequenceName = "stock_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "stock_gen")	
	@Column(name = "stock_id", unique = true, nullable = false)
	@Getter@Setter
	private Long id;

	@Column(name = "user_id")
	@Getter@Setter
	private Long userId;

	@Column(name = "user_type")
	@Getter@Setter
	private String userType;

//	@OneToOne(optional = false)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "item_id")
//	private Item item;

	@Column(name = "stock_itemId")
	@Getter@Setter
	private Long sitemId;

	@Column(name = "stock_batchId")
	@Getter@Setter
	private String batchNo;

	@Getter@Setter
	private Float purchased;

	@Getter@Setter
	private Float sold;

	@Getter@Setter
	private Float balance;

	@Getter@Setter
	private LocalDateTime dated;

	*//**
	 * @return the serialversionuid
	 *//*
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}*/