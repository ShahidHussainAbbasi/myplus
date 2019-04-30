package com.web.dto.education;

import java.io.Serializable;
import java.sql.Time;
import java.util.List;

import com.validation.ValidateEmpty;

import lombok.Getter;
import lombok.Setter;

public class StudentDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	@ValidateEmpty
	private String name;

	private Long userId;

	private String userType;

	private String ys;
	
	private String ye;
	
	@ValidateEmpty
	private String enrollNo;

	@ValidateEmpty
	private String enrollDate;

	private String dateOfBirth;

	private String feeMode="Monthly";

	private String email;

	private String mobile;

	private String phone;

	private String address;

	private Time time_in;

	private Time time_out;

	private List<String> hobbies;

	private String bloodGroup;

	@ValidateEmpty
	private Long guardianId;

	private String guardianName;

	private Long vehicleId;

	private String vehicleName;

	private Long discountId;

	private String discountName;

	private Integer nd;

	private String di;
	
	@ValidateEmpty
	private Long schoolId;

	private String schoolName;

	@ValidateEmpty
	private Long gradeId;

	private String gradeName;

	private String status = "Active";

	@ValidateEmpty
	private String gender;

	private String datedStr;

	private String updatedStr;
	
	@Getter@Setter
	private Float fee;

	private Integer dueDay = 10;
	
	private Integer vf;

	@Getter@Setter
	private String pob;

	@Getter@Setter
	private String mn;

	@Getter@Setter
	private String wa;

	@Getter@Setter
	private String religion="ISLAM";
	
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
	 * @return the feeMode
	 */
	public String getFeeMode() {
		return feeMode;
	}

	/**
	 * @param feeMode the feeMode to set
	 */
	public void setFeeMode(String feeMode) {
		this.feeMode = feeMode;
	}

	/**
	 * @param enrollDate the enrollDate to set
	 */
	public void setEnrollDate(String enrollDate) {
		this.enrollDate = enrollDate;
	}

	/**
	 * @return the guardianId
	 */
	public Long getGuardianId() {
		return guardianId;
	}

	/**
	 * @param guardianId the guardianId to set
	 */
	public void setGuardianId(Long guardianId) {
		this.guardianId = guardianId;
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
	 * @return the ys
	 */
	public String getYs() {
		return ys;
	}

	/**
	 * @param ys the ys to set
	 */
	public void setYs(String ys) {
		this.ys = ys;
	}

	/**
	 * @return the ye
	 */
	public String getYe() {
		return ye;
	}

	/**
	 * @param ye the ye to set
	 */
	public void setYe(String ye) {
		this.ye = ye;
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
	 * @return the bloodGroup
	 */
	public String getBloodGroup() {
		return bloodGroup;
	}

	/**
	 * @param bloodGroup the bloodGroup to set
	 */
	public void setBloodGroup(String bloodGroup) {
		this.bloodGroup = bloodGroup;
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

	/**
	 * @return the guardianName
	 */
	public String getGuardianName() {
		return guardianName;
	}

	/**
	 * @param guardianName the guardianName to set
	 */
	public void setGuardianName(String guardianName) {
		this.guardianName = guardianName;
	}

	/**
	 * @return the vehicleId
	 */
	public Long getVehicleId() {
		return vehicleId;
	}

	/**
	 * @param vehicleId the vehicleId to set
	 */
	public void setVehicleId(Long vehicleId) {
		this.vehicleId = vehicleId;
	}

	/**
	 * @return the vehicleName
	 */
	public String getVehicleName() {
		return vehicleName;
	}

	/**
	 * @return the discountId
	 */
	public Long getDiscountId() {
		return discountId;
	}

	/**
	 * @param discountId the discountId to set
	 */
	public void setDiscountId(Long discountId) {
		this.discountId = discountId;
	}

	/**
	 * @return the discountName
	 */
	public String getDiscountName() {
		return discountName;
	}

	/**
	 * @param discountName the discountName to set
	 */
	public void setDiscountName(String discountName) {
		this.discountName = discountName;
	}
	
	/**
	 * @return the nd
	 */
	public Integer getNd() {
		return nd;
	}

	/**
	 * @param nd the nd to set
	 */
	public void setNd(Integer nd) {
		this.nd = nd;
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
	 * @param vehicleName the vehicleName to set
	 */
	public void setVehicleName(String vehicleName) {
		this.vehicleName = vehicleName;
	}

	/**
	 * @return the schoolId
	 */
	public Long getSchoolId() {
		return schoolId;
	}

	/**
	 * @param schoolId the schoolId to set
	 */
	public void setSchoolId(Long schoolId) {
		this.schoolId = schoolId;
	}

	/**
	 * @return the schoolName
	 */
	public String getSchoolName() {
		return schoolName;
	}

	/**
	 * @param schoolName the schoolName to set
	 */
	public void setSchoolName(String schoolName) {
		this.schoolName = schoolName;
	}

	/**
	 * @return the dateOfBirth
	 */
	public String getDateOfBirth() {
		return dateOfBirth;
	}

	/**
	 * @param dateOfBirth the dateOfBirth to set
	 */
	public void setDateOfBirth(String dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
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
	 * @return the gradeName
	 */
	public String getGradeName() {
		return gradeName;
	}

	/**
	 * @param gradeName the gradeName to set
	 */
	public void setGradeName(String gradeName) {
		this.gradeName = gradeName;
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


	/**
	 * @return the dueDay
	 */
	public Integer getDueDay() {
		return dueDay;
	}

	/**
	 * @param dueDay the dueDay to set
	 */
	public void setDueDay(Integer dueDay) {
		this.dueDay = dueDay;
	}

	/**
	 * @return the vf
	 */
	public Integer getVf() {
		return vf;
	}

	/**
	 * @param vf the vf to set
	 */
	public void setVf(Integer vf) {
		this.vf = vf;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "StudentDTO [id=" + id + ", name=" + name + ", userId=" + userId + ", userType=" + userType
				+ ", enrollNo=" + enrollNo + ", updatedStr=" + updatedStr + ", email=" + email + ", mobile=" + mobile
				+ ", phone=" + phone + ", address=" + address + ", dateOfBirth=" + dateOfBirth + ", time_out="
				+ time_out + ", hobbies=" + hobbies + ", bloodGroup=" + bloodGroup + ", enrollDate=" + enrollDate
				+ ", guardianId=" + guardianId + ", gradeId=" + gradeId + ", status=" + status + "]";
	}

}