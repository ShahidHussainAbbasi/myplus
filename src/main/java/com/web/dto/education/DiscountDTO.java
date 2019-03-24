package com.web.dto.education;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

import com.validation.ValidateEmpty;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "discount")

public class DiscountDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@ValidateEmpty
	private String name;

	@ValidateEmpty
	private String di;;// percent/amount

	private Float amount;

	private Date startDate;

	private Date endDate;

	private String description;

	private String referenceName;

	private String referenceMobile;

//	private Long studentid;
//
//	private String studentName;
//
	private String status = "Active";

//	private String datedStr;
//	
//	private String updatedStr;
	
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
	public Float getAmount() {
		return amount;
	}

	/**
	 * @param amount the amount to set
	 */
	public void setAmount(Float amount) {
		this.amount = amount;
	}

	/**
	 * @return the di
	 */
	public String getDi() {
		return di;
	}

	/**
	 * @param di the di to set
	 */
	public void setDi(String di) {
		this.di = di;
	}

	/**
	 * @return the startDate
	 */
	public Date getStartDate() {
		return startDate;
	}

	/**
	 * @param startDate the startDate to set
	 */
	public void setStartDate(Date startDate) {
		this.startDate = startDate;
	}

	/**
	 * @return the endDate
	 */
	public Date getEndDate() {
		return endDate;
	}

	/**
	 * @param endDate the endDate to set
	 */
	public void setEndDate(Date endDate) {
		this.endDate = endDate;
	}

	/**
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * @param description the description to set
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * @return the referenceName
	 */
	public String getReferenceName() {
		return referenceName;
	}

	/**
	 * @param referenceName the referenceName to set
	 */
	public void setReferenceName(String referenceName) {
		this.referenceName = referenceName;
	}

	/**
	 * @return the referenceMobile
	 */
	public String getReferenceMobile() {
		return referenceMobile;
	}

	/**
	 * @param referenceMobile the referenceMobile to set
	 */
	public void setReferenceMobile(String referenceMobile) {
		this.referenceMobile = referenceMobile;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/**
	 * @return the status
	 */
	public String getStatus() {
		return status;
	}

	/**
	 * @param status the status to set
	 */
	public void setStatus(String status) {
		this.status = status;
	}


}