package com.myplus.business_service.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import com.myplus.business_service.entity.Company;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
@Entity
@Table(name = "item")
public class Item implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id	
	@SequenceGenerator(name = "item_gen", sequenceName = "item_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "item_gen")	
	@Column(name = "item_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	private String iname;

	private String icode;

	@Column(name = "description")
	private String idesc;

	private String unit;

	private String category;

	// @Column(name = "purchase_amount")
	// private Float purchaseAmount;

	// @Column(name = "sell_amount")
	// private Float sellAmount;

	// private Float discount;

	// private String discountType;

	// private Float net;

	// private LocalDate expDate;

	// private Float stock;

//	@OneToMany(cascade= CascadeType.REFRESH)
//	@NotFound(action = NotFoundAction.IGNORE)
	// @ElementCollection
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

	@OneToOne(fetch = FetchType.LAZY)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "company_id")
	private Company company;

	// @OneToOne(fetch = FetchType.LAZY)
	// @NotFound(action = NotFoundAction.IGNORE)
	// @JoinColumn(name = "sell_id")
	// @ElementCollection
	// private List<Sell> sales = new ArrayList<>();	

	// @OneToOne(fetch = FetchType.LAZY)
	// @NotFound(action = NotFoundAction.IGNORE)
	// @JoinColumn(name = "purchase_id")

	// @ElementCollection
	// private List<Purchase> purchases = new ArrayList<>();;

	@OneToOne(fetch = FetchType.EAGER)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "stock_id")
	private Stock stock;


//	@OneToOne(fetch = FetchType.LAZY)
//	@NotFound(action = NotFoundAction.IGNORE)
//	@JoinColumn(name = "vender_id")
//	@MapsId
//	@OneToOne(fetch = FetchType.LAZY)
//	@MapsId
	// @Getter@Setter
	private Long venderId;

	@Column(updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;
	
	@JoinColumn(name = "batch_number")
	private String bn;


	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}