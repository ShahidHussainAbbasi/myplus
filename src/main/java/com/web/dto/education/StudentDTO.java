package com.web.dto.education;

import java.io.Serializable;
import java.sql.Time;
import java.util.List;

import com.validation.ValidEmail;
import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

public class StudentDTO implements Serializable {
	private static final long serialVersionUID = 1L;
	
	private Long id;

	@ValidateEmpty
	private String name;

	private Long userId;

	private String userType;
	
	@ValidateEmpty
	private String enrollNo;
	
	@ValidateEmpty
	private String enrollmentDate;
	
	private String updateDate;

	@ValidEmail
	private String email;

	@ValidMobileNumber
	private String mobile;

	private String phone;

	@ValidateEmpty
	private String address;

	@ValidateEmpty
	private String DOB = null;
	
	@ValidateEmpty
	private String Gender;
	
	private Time time_in;
	
	private Time time_out;
	
	private List<String> hobbies;
	
	private String boodGroup;
	
	@ValidateEmpty
	private String enrollDate;
	
	@ValidateEmpty
	private String dateOfBirht = null;
	
	@ValidateEmpty
	private String gender;
	
	private Long gaurdianId = null;

	private Long gradeId;
	
	private Boolean status = true;
	
	private String dated;

	@ValidateEmpty
	private String grade = null;
	
	@ValidateEmpty
	private String gaurdian = null;

	@ValidateEmpty
	private List<String> subjects;


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
	 * @return the enrollmentDate
	 */
	public String getEnrollmentDate() {
		return enrollmentDate;
	}

	/**
	 * @param enrollmentDate the enrollmentDate to set
	 */
	public void setEnrollmentDate(String enrollmentDate) {
		this.enrollmentDate = enrollmentDate;
	}

	/**
	 * @return the updateDate
	 */
	public String getUpdateDate() {
		return updateDate;
	}

	/**
	 * @param updateDate the updateDate to set
	 */
	public void setUpdateDate(String updateDate) {
		this.updateDate = updateDate;
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
	 * @return the phone
	 */
	public String getPhone() {
		return phone;
	}

	/**
	 * @param phone the phone to set
	 */
	public void setPhone(String phone) {
		this.phone = phone;
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
	 * @return the dOB
	 */
	public String getDOB() {
		return DOB;
	}

	/**
	 * @param dOB the dOB to set
	 */
	public void setDOB(String dOB) {
		DOB = dOB;
	}

	/**
	 * @return the gender
	 */
	public String getGender() {
		return Gender;
	}

	/**
	 * @param gender the gender to set
	 */
	public void setGender(String gender) {
		Gender = gender;
	}

	/**
	 * @return the time_in
	 */
	public Time getTime_in() {
		return time_in;
	}

	/**
	 * @param time_in the time_in to set
	 */
	public void setTime_in(Time time_in) {
		this.time_in = time_in;
	}

	/**
	 * @return the time_out
	 */
	public Time getTime_out() {
		return time_out;
	}

	/**
	 * @param time_out the time_out to set
	 */
	public void setTime_out(Time time_out) {
		this.time_out = time_out;
	}

	/**
	 * @return the hobbies
	 */
	public List<String> getHobbies() {
		return hobbies;
	}

	/**
	 * @param hobbies the hobbies to set
	 */
	public void setHobbies(List<String> hobbies) {
		this.hobbies = hobbies;
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

	/**
	 * @return the grade
	 */
	public String getGrade() {
		return grade;
	}

	/**
	 * @param grade the grade to set
	 */
	public void setGrade(String grade) {
		this.grade = grade;
	}

	/**
	 * @return the gaurdian
	 */
	public String getGaurdian() {
		return gaurdian;
	}

	/**
	 * @param gaurdian the gaurdian to set
	 */
	public void setGaurdian(String gaurdian) {
		this.gaurdian = gaurdian;
	}

	/**
	 * @return the subjects
	 */
	public List<String> getSubjects() {
		return subjects;
	}

	/**
	 * @param subjects the subjects to set
	 */
	public void setSubjects(List<String> subjects) {
		this.subjects = subjects;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "StudentDTO [id=" + id + ", name=" + name + ", userId=" + userId + ", userType=" + userType
				+ ", enrollNo=" + enrollNo + ", enrollmentDate=" + enrollmentDate + ", updateDate=" + updateDate
				+ ", email=" + email + ", mobile=" + mobile + ", phone=" + phone + ", address=" + address + ", DOB="
				+ DOB + ", Gender=" + Gender + ", time_in=" + time_in + ", time_out=" + time_out + ", hobbies="
				+ hobbies + ", boodGroup=" + boodGroup + ", enrollDate=" + enrollDate + ", dateOfBirht=" + dateOfBirht
				+ ", gender=" + gender + ", gaurdianId=" + gaurdianId + ", gradeId=" + gradeId + ", status=" + status
				+ ", dated=" + dated + ", grade=" + grade + ", gaurdian=" + gaurdian + ", subjects=" + subjects + "]";
	}
	

}