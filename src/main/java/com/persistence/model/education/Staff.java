package com.persistence.model.education;

import java.io.Serializable;
import java.sql.Time;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.persistence.Table;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "staff"
)

public class Staff implements Serializable {
	private static final long serialVersionUID = 1L;
	

	@Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="staff_id", unique = true, nullable = false)
	private Long id;
	
	@Column(name = "name", nullable = false)
	private String name;
	
	private String code;

	@Column(name="user_id")
	private Long userId;

	@Column(name="user_type")
	private String userType;
	
	private String email;

	private String mobile;

	private String phone;

	private String address;

	private String designation;
	
	@Column(name="date_of_birth")
	private String DOB = null;
	
	private String Gender;
	
	private Time time_in;
	
	private Time time_out;
	
	private String specialist;
	
	private String qualification;
	
	private Boolean marriad;
	
	private Boolean status;
	
	private String dated;
	
	@Column(name="owner_id")
	private Long ownerId = null;
	
	//bi-directional many-to-one association to Appointment
	@ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	private List<School> schools;

	//bi-directional many-to-one association to Appointment
	@OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	private List<Grade> grades;

	//bi-directional many-to-one association to Appointment
	@OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	private List<Subject> subjects;

	
	
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
	 * @return the code
	 */
	public String getCode() {
		return code;
	}



	/**
	 * @param code the code to set
	 */
	public void setCode(String code) {
		this.code = code;
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
	 * @return the specialist
	 */
	public String getSpecialist() {
		return specialist;
	}



	/**
	 * @param specialist the specialist to set
	 */
	public void setSpecialist(String specialist) {
		this.specialist = specialist;
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
	 * @return the marriad
	 */
	public Boolean getMarriad() {
		return marriad;
	}



	/**
	 * @param marriad the marriad to set
	 */
	public void setMarriad(Boolean marriad) {
		this.marriad = marriad;
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
	 * @return the ownerId
	 */
	public Long getOwnerId() {
		return ownerId;
	}



	/**
	 * @param ownerId the ownerId to set
	 */
	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}



	/**
	 * @return the schools
	 */
	public List<School> getSchools() {
		return schools;
	}



	/**
	 * @param schools the schools to set
	 */
	public void setSchools(List<School> schools) {
		this.schools = schools;
	}



	/**
	 * @return the grades
	 */
	public List<Grade> getGrades() {
		return grades;
	}



	/**
	 * @param grades the grades to set
	 */
	public void setGrades(List<Grade> grades) {
		this.grades = grades;
	}



	/**
	 * @return the subjects
	 */
	public List<Subject> getSubjects() {
		return subjects;
	}



	/**
	 * @param subjects the subjects to set
	 */
	public void setSubjects(List<Subject> subjects) {
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
		return "Staff [id=" + id + ", name=" + name + ", code=" + code + ", userId=" + userId + ", userType=" + userType
				+ ", email=" + email + ", mobile=" + mobile + ", phone=" + phone + ", address=" + address
				+ ", designation=" + designation + ", DOB=" + DOB + ", Gender=" + Gender + ", time_in=" + time_in
				+ ", time_out=" + time_out + ", specialist=" + specialist + ", qualification=" + qualification
				+ ", marriad=" + marriad + ", status=" + status + ", dated=" + dated + ", ownerId=" + ownerId
				+ ", schools=" + schools + ", grades=" + grades + ", subjects=" + subjects + "]";
	}


}