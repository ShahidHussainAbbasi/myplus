/**
 * 
 */
package com.web.dto.abbasiWelfare;

import javax.validation.constraints.Digits;

import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

/**
 * @author Shahid
 *
 */
public class DonationDTO {

	private Long donatorId =null;
	private Long userId = null;
	private String userType = null;
	
	private Long id=null;
	private Double amount = null;
	private String receivedBy = null;
	private String dated = null;
	/**
	 * @return the donatorId
	 */
	public Long getDonatorId() {
		return donatorId;
	}
	/**
	 * @param donatorId the donatorId to set
	 */
	public void setDonatorId(Long donatorId) {
		this.donatorId = donatorId;
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
	 * @return the amount
	 */
	public Double getAmount() {
		return amount;
	}
	/**
	 * @param amount the amount to set
	 */
	public void setAmount(Double amount) {
		this.amount = amount;
	}
	/**
	 * @return the receivedBy
	 */
	public String getReceivedBy() {
		return receivedBy;
	}
	/**
	 * @param receivedBy the receivedBy to set
	 */
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
