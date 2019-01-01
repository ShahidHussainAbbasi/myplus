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
public class DonatorDTO {

	private Long donatorId =null;
	private Long userId = null;
	private String userType = null;
	
	private Long id=null;
	@ValidateEmpty
	private String name=null;
	@ValidMobileNumber
	private String mobile = null;
	private String fName = null;
	private String address = null;
//	@Digits(fraction = 0, integer = 1)
	private Double amount = null;
	private String receivedBy = null;
	private Boolean showMe = false;
	private String dated = null;
	
	public Long getId() {
		return id;
	}
	public void setId(Long id) {
		this.id = id;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getMobile() {
		return mobile;
	}
	public void setMobile(String mobile) {
		this.mobile = mobile;
	}
	public String getfName() {
		return fName;
	}
	public void setfName(String fName) {
		this.fName = fName;
	}
	public String getAddress() {
		return address;
	}
	public void setAddress(String address) {
		this.address = address;
	}
	public Double getAmount() {
		return amount;
	}
	public void setAmount(Double amount) {
		this.amount = amount;
	}
	public String getReceivedBy() {
		return receivedBy;
	}
	public void setReceivedBy(String receivedBy) {
		this.receivedBy = receivedBy;
	}
	public String getDated() {
		return dated;
	}
	public void setDated(String dated) {
		this.dated = dated;
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
	 * @return the showMe
	 */
	public Boolean getShowMe() {
		return showMe;
	}
	/**
	 * @param showMe the showMe to set
	 */
	public void setShowMe(Boolean showMe) {
		this.showMe = showMe;
	}
	@Override
	public String toString() {
		return "DonatorDTO [id=" + id + ", name=" + name + ", mobile=" + mobile + ", fName=" + fName + ", address="
				+ address + ", amount=" + amount + ", receivedBy=" + receivedBy + ", dated=" + dated + "]";
	}

	
}
