package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "student")

public class Student implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "student_gen", sequenceName = "student_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "student_gen")	
	@Column(name = "student_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "year_start")
	private LocalDate ys;
	
	@Column(name = "year_end")
	private LocalDate ye;

	@Column(name = "enroll_no")
	private String enrollNo;

	@Column(name = "enroll_date")
	private LocalDate enrollDate;

	@Column(name = "fee_mode")
	private String feeMode;

	@Column(updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;

	private String email;

	private String mobile;

	private String address;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	private String gender;

	@Column(name = "blood_group")
	private String bloodGroup;

	private String status;

//	@ManyToOne//(cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE)
	@Column(name = "school_id")
	private Long schoolId;

	// @Column(name="gaurdian_id")
//	private Long gaurdianId = null;
//	@ManyToOne(optional = false) // (cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE)
	@Column(name = "guardian_id")
	private Long guardianId;

//	@Column(name="grade_id")
//	private Long gradeId;
//	@OneToOne//(cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE)
	@Column(name = "grade_id")
	private Long gradeId;

//	@Column(name = "vehicle_id")
//	private Long vehicleId;
//	@ManyToOne(fetch = FetchType.LAZY) // (cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE) // Works only if it's parent don't have more parent
	@Column(name = "vehicle_id")
	private Long vehicleId;

//	@Column(name = "discount_id")
//	private Long discountId;
//	@OneToOne(optional = false)
//	@NotFound(action = NotFoundAction.IGNORE)
	@Column(name = "discount_id")
	private Long discountId;
	
	@Column(name = "new_discount")
	private Integer nd;

	@Column(name = "discount_in")
	private String di;

	@Getter@Setter
	private Float fee;

	private Integer dueDay;
	
	@Column(name="vehicle_fare")
	private Integer vf;

	@Getter@Setter
	@Column(name="place_0f_birth")
	private String pob;

	@Getter@Setter
	@Column(name="mother_name")
	private String mn;

	@Getter@Setter
	@Column(name="watts_app")
	private String wa;

	@Getter@Setter
	@Column(name="religion")
	private String religion;
	
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
	 * @return the ys
	 */
	public LocalDate getYs() {
		return ys;
	}

	/**
	 * @param ys the ys to set
	 */
	public void setYs(LocalDate ys) {
		this.ys = ys;
	}

	/**
	 * @return the ye
	 */
	public LocalDate getYe() {
		return ye;
	}

	/**
	 * @param ye the ye to set
	 */
	public void setYe(LocalDate ye) {
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
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/**
	 * @return the enrollDate
	 */
	public LocalDate getEnrollDate() {
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
	 * @return the dated
	 */
	public LocalDateTime getDated() {
		return dated;
	}

	/**
	 * @param dated the dated to set
	 */
	public void setDated(LocalDateTime dated) {
		this.dated = dated;
	}

	/**
	 * @return the updated
	 */
	public LocalDateTime getUpdated() {
		return updated;
	}

	/**
	 * @param updated the updated to set
	 */
	public void setUpdated(LocalDateTime updated) {
		this.updated = updated;
	}

	/**
	 * @param enrollDate the enrollDate to set
	 */
	public void setEnrollDate(LocalDate enrollDate) {
		this.enrollDate = enrollDate;
	}

	/**
	 * @return the dateOfBirth
	 */
	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	/**
	 * @param dateOfBirth the dateOfBirth to set
	 */
	public void setDateOfBirth(LocalDate dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
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
	 * @return the grade
	 */
	public Long getGradeId() {
		return gradeId;
	}

	/**
	 * @param grade the grade to set
	 */
	public void setGradeId(Long gradeId) {
		this.gradeId = gradeId;
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
	 * @return the school
	 */
	public Long getSchoolId() {
		return schoolId;
	}

	/**
	 * @param school the school to set
	 */
	public void setSchoolId(Long schoolId) {
		this.schoolId = schoolId;
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

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "Student [id=" + id + ", name=" + name + ", userId=" + userId + ", ys=" + ys + ", ye=" + ye
				+ ", enrollNo=" + enrollNo + ", enrollDate=" + enrollDate + ", feeMode=" + feeMode + ", dated=" + dated
				+ ", updated=" + updated + ", email=" + email + ", mobile=" + mobile + ", address=" + address
				+ ", dateOfBirth=" + dateOfBirth + ", gender=" + gender + ", bloodGroup=" + bloodGroup + ", status="
				+ status + ", schoolId=" + schoolId + ", guardianId=" + guardianId + ", gradeId=" + gradeId
				+ ", vehicleId=" + vehicleId + ", discountId=" + discountId + ", nd=" + nd + ", di=" + di + ", fee="
				+ fee + ", dueDay=" + dueDay + ", vf=" + vf + ", pob=" + pob + ", mn=" + mn + ", wa=" + wa
				+ ", religion=" + religion + "]";
	}

}