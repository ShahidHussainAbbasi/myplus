package com.persistence.model.education;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "student"
)

public class Student implements Serializable {
	private static final long serialVersionUID = 1L;
	
	
	@Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="student_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name="user_id")
	private Long userId;

	@Column(name="enroll_no")
	private String enrollNo;
	
	@Column(name="enroll_date")
	private String enrollDate;
	
	private String email;

	private String mobile;

	private String address;

	@Column(name="date_of_birth")
	private String dateOfBirht = null;
	
	private String gender;
	
	@Column(name="blood_group")
	private String boodGroup;
	
	private Boolean status;
	
	@Column(name="gaurdian_id")
	private Long gaurdianId = null;

	@Column(name="grade_id")
	private Long gradeId;

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
	 * @return the enrollNo
	 */
	public String getEnrollNo() {
		return enrollNo;
	}

	/**
	 * @param enrollNo the enrollNo to set
	 */
	public void setEnrollNo(String enrollNo) {
		this.enrollNo = enrollNo;
	}

	/**
	 * @return the email
	 */
	public String getEmail() {
		return email;
	}

	/**
	 * @param email the email to set
	 */
	public void setEmail(String email) {
		this.email = email;
	}

	/**
	 * @return the mobile
	 */
	public String getMobile() {
		return mobile;
	}

	/**
	 * @param mobile the mobile to set
	 */
	public void setMobile(String mobile) {
		this.mobile = mobile;
	}

	/**
	 * @return the address
	 */
	public String getAddress() {
		return address;
	}

	/**
	 * @param address the address to set
	 */
	public void setAddress(String address) {
		this.address = address;
	}

	/**
	 * @return the gender
	 */
	public String getGender() {
		return gender;
	}

	/**
	 * @param gender the gender to set
	 */
	public void setGender(String gender) {
		this.gender = gender;
	}

	/**
	 * @return the boodGroup
	 */
	public String getBoodGroup() {
		return boodGroup;
	}

	/**
	 * @param boodGroup the boodGroup to set
	 */
	public void setBoodGroup(String boodGroup) {
		this.boodGroup = boodGroup;
	}

	/**
	 * @return the status
	 */
	public Boolean getStatus() {
		return status;
	}

	/**
	 * @param status the status to set
	 */
	public void setStatus(Boolean status) {
		this.status = status;
	}

	/**
	 * @return the gaurdianId
	 */
	public Long getGaurdianId() {
		return gaurdianId;
	}

	/**
	 * @param gaurdianId the gaurdianId to set
	 */
	public void setGaurdianId(Long gaurdianId) {
		this.gaurdianId = gaurdianId;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/**
	 * @return the enrollDate
	 */
	public String getEnrollDate() {
		return enrollDate;
	}

	/**
	 * @param enrollDate the enrollDate to set
	 */
	public void setEnrollDate(String enrollDate) {
		this.enrollDate = enrollDate;
	}

	/**
	 * @return the dateOfBirht
	 */
	public String getDateOfBirht() {
		return dateOfBirht;
	}

	/**
	 * @param dateOfBirht the dateOfBirht to set
	 */
	public void setDateOfBirht(String dateOfBirht) {
		this.dateOfBirht = dateOfBirht;
	}

	/**
	 * @return the gradeId
	 */
	public Long getGradeId() {
		return gradeId;
	}

	/**
	 * @param gradeId the gradeId to set
	 */
	public void setGradeId(Long gradeId) {
		this.gradeId = gradeId;
	}


}