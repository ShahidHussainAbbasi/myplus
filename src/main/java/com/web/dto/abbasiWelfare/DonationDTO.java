/**
 * 
 */
package com.web.dto.abbasiWelfare;

/**
 * @author Shahid
 *
 */
public class DonationDTO {

	private Long id = null;
	private Long donatorId;
	private String donatorName;
	private Long userId = null;
	private String userType = null;
	private Double amount = null;
	private String receivedBy = null;
	private String datedStr = null;
	private String updatedStr = null;

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
	 * @return the donatorName
	 */
	public String getDonatorName() {
		return donatorName;
	}

	/**
	 * @param donatorName the donatorName to set
	 */
	public void setDonatorName(String donatorName) {
		this.donatorName = donatorName;
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
	 * @return the datedStr
	 */
	public String getDatedStr() {
		return datedStr;
	}

	/**
	 * @param datedStr the datedStr to set
	 */
	public void setDatedStr(String datedStr) {
		this.datedStr = datedStr;
	}

	/**
	 * @return the updatedStr
	 */
	public String getUpdatedStr() {
		return updatedStr;
	}

	/**
	 * @param updatedStr the updatedStr to set
	 */
	public void setUpdatedStr(String updatedStr) {
		this.updatedStr = updatedStr;
	}

}
