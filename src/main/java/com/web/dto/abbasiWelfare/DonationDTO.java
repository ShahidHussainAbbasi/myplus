/**
 * 
 */
package com.web.dto.abbasiWelfare;

/**
 * @author Shahid
 *
 */
public class DonationDTO {

	private Long id=null;
	private Long userId = null;
	private String userType = null;
	private String name=null;
	private Double amount = null;
	private String receivedBy = null;
	private String dated = null;


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
	 * @return the name
	 */
	public String getName() {
		return name;
	}
	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
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
