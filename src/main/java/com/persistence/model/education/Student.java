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
        name = "student"
)

public class Student implements Serializable {
	private static final long serialVersionUID = 1L;
	
	
	@Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="student_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name="user_id")
	private Long userId;

	@Column(name="user_type")
	private String userType;
	
	@Column(name="enroll_no")
	private String enrollNo;
	
	@Column(name="enroll_date")
	private String enrollmentDate;
	
	@Column(name="update_date")
	private String updateDate;
	
	private String email;

	private String mobile;

	private String phone;

	private String address;

	@Column(name="date_of_birth")
	private String DOB = null;
	
	private String Gender;
	
	private Time time_in;
	
	private Time time_out;
	
	private String specialist;
	
	private String qualification;
	
	@Column(name="blood_group")
	private String boodGroup;
	
	private Boolean status;
	
	private String dated;
	
	@Column(name="class_id")
	private Long classId = null;
	
	@Column(name="gaurdian_id")
	private Long gaurdianId = null;

	//bi-directional many-to-one association to Appointment
	@ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	private List<Staff> staff;

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
	 * @return the classId
	 */
	public Long getClassId() {
		return classId;
	}

	/**
	 * @param classId the classId to set
	 */
	public void setClassId(Long classId) {
		this.classId = classId;
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
		gaurdianId = gaurdianId;
	}

	/**
	 * @return the staff
	 */
	public List<Staff> getStaff() {
		return staff;
	}

	/**
	 * @param staff the staff to set
	 */
	public void setStaff(List<Staff> staff) {
		this.staff = staff;
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


}