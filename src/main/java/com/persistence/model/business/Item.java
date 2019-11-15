package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import com.persistence.model.Company;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "item")
public class Item implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id	
	@SequenceGenerator(name = "item_gen", sequenceName = "item_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "item_gen")	
	@Column(name = "item_id", unique = true, nullable = false)
	@Getter@Setter
	private Long id;

	@Column(name = "user_id")
	@Getter@Setter
	private Long userId;

	@Column(name = "user_type")
	@Getter@Setter
	private String userType;

	@Getter@Setter
	private String iname;

	@Getter@Setter
	private String icode;

	@Column(name = "description")
	@Getter@Setter
	private String idesc;

	@Column(name = "purchase_amount")
	@Getter@Setter
	private Float purchaseAmount;

	@Column(name = "sell_amount")
	@Getter@Setter
	private Float sellAmount;

	@Getter@Setter
	private Float discount;

	@Getter@Setter
	private String discountType;

	@Getter@Setter
	private Float net;

	@Getter@Setter
	private LocalDate expDate;

	@Getter@Setter
	private Float stock;

//	@OneToMany(cascade= CascadeType.REFRESH)
//	@NotFound(action = NotFoundAction.IGNORE)
	@ElementCollection
//	@CollectionTable(name ="tracks" , joinColumns=@JoinColumn(name="playlist_id"))
//	@Column(name="track")
//	private Set<Long> itemUnitIds = new HashSet<>();
//	@OneToOne(fetch = FetchType.EAGER)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "item_type_id")
//	private ItemType itemType;

//	@OneToMany(orphanRemoval = false)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@ElementCollection
//	private Set<Long> itemTypeIds = new HashSet<>();

//	@OneToOne(fetch = FetchType.EAGER)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "item_unit_id")
//	private ItemUnit itemUnit;

	@OneToOne(fetch = FetchType.EAGER)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "company_id")
	@Getter@Setter
	private Company company;

//	@OneToOne(fetch = FetchType.LAZY)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "vender_id")
//	@MapsId
//	@OneToOne(fetch = FetchType.LAZY)
//	@MapsId
	@Getter@Setter
	private Long venderId;

	@Column(updatable = false)
	@Getter@Setter
	private LocalDateTime dated;

	@Getter@Setter
	private LocalDateTime updated;
	
	@Getter@Setter
	@JoinColumn(name = "batch_number")
	private String bn;


	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}