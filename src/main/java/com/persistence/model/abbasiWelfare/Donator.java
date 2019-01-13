/**
 * 
 */
package com.persistence.model.abbasiWelfare;

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
@Table(name = "donator")
public class Donator {

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	private Long id;
	private String name=null;
	
	private Long userId = null;
	private String userType = null;
	private String mobile = null;
	private String fName = null;
	private String address = null;
	private Double amount = null;
	private String receivedBy = null;
	private String dated = null;
	private Boolean showMe = null;

	public Donator(Long id2, String userType2, String name2) {
		this.userId = id2;
		this.userType=userType2;
		this.name=name2;
	}
	
	public Donator() {
	}

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
	 * @return the showMe
	 */
	public Boolean isShowMe() {
		return showMe;
	}
	/**
	 * @param showMe the showMe to set
	 */
	public void setShowMe(Boolean showMe) {
		this.showMe = showMe;
	}

}
