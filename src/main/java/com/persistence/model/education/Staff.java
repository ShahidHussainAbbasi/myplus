package com.persistence.model.education;

import java.io.Serializable;
import java.util.Collection;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "staff")

public class Staff implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "staff_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "user_id")
	private Long userId;

	private String email;

	private String mobile;

	private String phone;

	private String address;

	private String designation;

	@Column(name = "date_of_birth")
	private String dateOfBirth = null;

	private String gender;

	@Column(name = "time_in")
	private String timeIn;

	@Column(name = "time_out")
	private String timeOut;

	private String qualification;

	private Boolean married;

	private Boolean status;

	private String dated;

//    @ManyToMany(fetch = FetchType.LAZY)
//    @JoinTable(name = "staff_schools", joinColumns = @JoinColumn(name = "staff_id", referencedColumnName = "staff_id"), 
//    inverseJoinColumns = @JoinColumn(name = "school_id", referencedColumnName = "school_id"))
	// uni-directional on-to-many association to subject
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "school_id")
    private Collection<School> schools;
//    @ManyToMany(fetch = FetchType.LAZY)
//    @JoinTable(name = "staff_grades", joinColumns = @JoinColumn(name = "staff_id", referencedColumnName = "staff_id"), 
//    inverseJoinColumns = @JoinColumn(name = "grade_id", referencedColumnName = "grade_id"))
	// uni-directional on-to-many association to subject
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "grade_id")
    private Collection<Grade> grades;

	// uni-directional on-to-many association to subject
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "subject_id")
	private Collection<Subject> subjects;

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
	 * @return the designation
	 */
	public String getDesignation() {
		return designation;
	}

	/**
	 * @param designation the designation to set
	 */
	public void setDesignation(String designation) {
		this.designation = designation;
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
	 * @return the qualification
	 */
	public String getQualification() {
		return qualification;
	}

	/**
	 * @param qualification the qualification to set
	 */
	public void setQualification(String qualification) {
		this.qualification = qualification;
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
	 * @return the schools
	 */
	public Collection<School> getSchools() {
		return schools;
	}

	/**
	 * @param schools the schools to set
	 */
	public void setSchools(Collection<School> schools) {
		this.schools = schools;
	}

	/**
	 * @return the grades
	 */
	public Collection<Grade> getGrades() {
		return grades;
	}

	/**
	 * @param grades the grades to set
	 */
	public void setGrades(Collection<Grade> grades) {
		this.grades = grades;
	}

	/**
	 * @return the subjects
	 */
	public Collection<Subject> getSubjects() {
		return subjects;
	}

	/**
	 * @param subjects the subjects to set
	 */
	public void setSubjects(Collection<Subject> subjects) {
		this.subjects = subjects;
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
	 * @return the timeIn
	 */
	public String getTimeIn() {
		return timeIn;
	}

	/**
	 * @param timeIn the timeIn to set
	 */
	public void setTimeIn(String timeIn) {
		this.timeIn = timeIn;
	}

	/**
	 * @return the timeOut
	 */
	public String getTimeOut() {
		return timeOut;
	}

	/**
	 * @param timeOut the timeOut to set
	 */
	public void setTimeOut(String timeOut) {
		this.timeOut = timeOut;
	}

	/**
	 * @return the married
	 */
	public Boolean getMarried() {
		return married;
	}

	/**
	 * @param married the married to set
	 */
	public void setMarried(Boolean married) {
		this.married = married;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}