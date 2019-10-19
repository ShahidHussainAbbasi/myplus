package com.persistence.model.business;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "stock", uniqueConstraints = { @UniqueConstraint(columnNames = "stock_id") })

public class Stock implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "stock_gen", sequenceName = "stock_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "stock_gen")	
	@Column(name = "stock_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_type")
	private String userType;

	@OneToOne(optional = false)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "item_id")
	private Item item;

	private Long purchased;

	private Long sold;

	private Long balance;

	private LocalDateTime dated;

	private LocalDateTime updated;

	/**
	 * @return the id
	 */
	public Long getId() {
		return id;
	}

	/**
	 * @param id the id to set
	 */
	public void setId(Long id) {
		this.id = id;
	}

	/**
	 * @return the userId
	 */
	public Long getUserId() {
		return userId;
	}

	/**
	 * @param userId the userId to set
	 */
	public void setUserId(Long userId) {
		this.userId = userId;
	}

	/**
	 * @return the userType
	 */
	public String getUserType() {
		return userType;
	}

	/**
	 * @param userType the userType to set
	 */
	public void setUserType(String userType) {
		this.userType = userType;
	}

	/**
	 * @return the item
	 */
	public Item getItem() {
		return item;
	}

	/**
	 * @param item the item to set
	 */
	public void setItem(Item item) {
		this.item = item;
	}

	/**
	 * @return the purchased
	 */
	public Long getPurchased() {
		return purchased;
	}

	/**
	 * @param purchased the purchased to set
	 */
	public void setPurchased(Long purchased) {
		this.purchased = purchased;
	}

	/**
	 * @return the sold
	 */
	public Long getSold() {
		return sold;
	}

	/**
	 * @param sold the sold to set
	 */
	public void setSold(Long sold) {
		this.sold = sold;
	}

	/**
	 * @return the balance
	 */
	public Long getBalance() {
		return balance;
	}

	/**
	 * @param balance the balance to set
	 */
	public void setBalance(Long balance) {
		this.balance = balance;
	}

	/**
	 * @return the dated
	 */
	public LocalDateTime getDated() {
		return dated;
	}

	/**
	 * @param dated the dated to set
	 */
	public void setDated(LocalDateTime dated) {
		this.dated = dated;
	}

	/**
	 * @return the updated
	 */
	public LocalDateTime getUpdated() {
		return updated;
	}

	/**
	 * @param updated the updated to set
	 */
	public void setUpdated(LocalDateTime updated) {
		this.updated = updated;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}