package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "student")

public class Student implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "student_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "enroll_no")
	private String enrollNo;

	@Column(name = "enroll_date")
//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime enrollDate;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;

	private String email;

	private String mobile;

	private String address;

	@Column(name = "date_of_birth")
	private String dateOfBirth = null;

	private String gender;

	@Column(name = "blood_group")
	private String boodGroup;

	private String status;

	@OneToOne(cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "school_id")
	private School school;

	// @Column(name="gaurdian_id")
//	private Long gaurdianId = null;
	@ManyToOne(cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "guardian_id")
	private Guardian guardian;

//	@Column(name="grade_id")
//	private Long gradeId;
	@ManyToOne(cascade = CascadeType.ALL)
//	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "grade_id")
	private Grade grade;

//	@Column(name = "vehicle_id")
//	private Long vehicleId;
	@ManyToOne(cascade = CascadeType.ALL)
	@JoinColumn(name = "vehicle_id")
//	@NotFound(action = NotFoundAction.IGNORE)
	private Vehicle vehicle;

//	@Column(name = "discount_id")
//	private Long discountId;
	@OneToMany(mappedBy = "student")
	private Set<Discount> discounts;

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
	public LocalDateTime getEnrollDate() {
		return enrollDate;
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
	public void setEnrollDate(LocalDateTime enrollDate) {
		this.enrollDate = enrollDate;
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
	 * @return the guardian
	 */
	public Guardian getGuardian() {
		return guardian;
	}

	/**
	 * @param guardian the guardian to set
	 */
	public void setGuardian(Guardian guardian) {
		this.guardian = guardian;
	}

	/**
	 * @return the grade
	 */
	public Grade getGrade() {
		return grade;
	}

	/**
	 * @param grade the grade to set
	 */
	public void setGrade(Grade grade) {
		this.grade = grade;
	}

	/**
	 * @return the discounts
	 */
	public Set<Discount> getDiscounts() {
		return discounts;
	}

	/**
	 * @param discounts the discounts to set
	 */
	public void setDiscounts(Set<Discount> discounts) {
		this.discounts = discounts;
	}

	/**
	 * @return the vehicle
	 */
	public Vehicle getVehicle() {
		return vehicle;
	}

	/**
	 * @param vehicle the vehicle to set
	 */
	public void setVehicle(Vehicle vehicle) {
		this.vehicle = vehicle;
	}

	/**
	 * @return the school
	 */
	public School getSchool() {
		return school;
	}

	/**
	 * @param school the school to set
	 */
	public void setSchool(School school) {
		this.school = school;
	}

}