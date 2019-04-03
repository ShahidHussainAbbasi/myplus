/**
 * 
 */
package com.persistence.model.abbasiWelfare;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * @author Shahid
 *
 */
@Entity
@Table(name = "donation")
public class Donation {

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	private Long id;
	
	@ManyToOne(fetch=FetchType.LAZY,optional=false)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name="donator_id")
	private Donator donator;
	
	private Long userId;
	@Column(name="donator_name")
	private String name =null;
	private Float amount = null;
	private String receivedBy;
	private LocalDateTime dated;
	private LocalDateTime updated;
	
	public Long getId() {
		return id;
	}
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
	 * @return the donatorName
	 */
	public String getName() {
		return name;
	}
	/**
	 * @param donatorName the donatorName to set
	 */
	public void setName(String name) {
		this.name = name;
	}
	
	/**
	 * @return the amount
	 */
	public Float getAmount() {
		return amount;
	}
	/**
	 * @param amount the amount to set
	 */
	public void setAmount(Float amount) {
		this.amount = amount;
	}
	public String getReceivedBy() {
		return receivedBy;
	}
	public void setReceivedBy(String receivedBy) {
		this.receivedBy = receivedBy;
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
	 * @param dated the dated to set
	 */
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
	 * @return the donator
	 */
	public Donator getDonator() {
		return donator;
	}
	/**
	 * @param donator the donator to set
	 */
	public void setDonator(Donator donator) {
		this.donator = donator;
	}


}
