/**
 * 
 */
package com.persistence.model.abbasiWelfare;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

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
	@Column(name="donator_name")
	private String name =null;
	private Float amount = null;
	private String receivedBy = null;
	private String dated = null;
	
	public Long getId() {
		return id;
	}
	public void setId(Long id) {
		this.id = id;
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
	 * @return the dated
	 */
	public String getDated() {
		return dated;
	}
	/**
	 * @param dated the dated to set
	 */
	public void setDated(String dated) {
		this.dated = dated;
	}


}
