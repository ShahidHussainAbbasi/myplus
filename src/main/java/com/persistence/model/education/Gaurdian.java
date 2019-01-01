package com.persistence.model.education;

import java.io.Serializable;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.Table;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "gaurdian"
)

public class Gaurdian implements Serializable {
	private static final long serialVersionUID = 1L;
	
	
	@Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="gaurdian_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name="user_id")
	private Long userId;

	@Column(name="user_type")
	private String userType;
	
	private String email;

	private String mobile;

	private String phone;

	@Column(name="temp_address")
	private String tempAddress;

	@Column(name="perm_address")
	private String permAddress;

	private String Gender;
	
	private String relation;
	
	private String accupation;
	
	@Column(name="blood_group")
	private String boodGroup;
	
	private String dated;
	
	//bi-directional many-to-one association to Appointment
	@OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
	private List<Student> students;


	
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
	 * @return the tempAddress
	 */
	public String getTempAddress() {
		return tempAddress;
	}


	/**
	 * @param tempAddress the tempAddress to set
	 */
	public void setTempAddress(String tempAddress) {
		this.tempAddress = tempAddress;
	}


	/**
	 * @return the permAddress
	 */
	public String getPermAddress() {
		return permAddress;
	}


	/**
	 * @param permAddress the permAddress to set
	 */
	public void setPermAddress(String permAddress) {
		this.permAddress = permAddress;
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
	 * @return the relation
	 */
	public String getRelation() {
		return relation;
	}


	/**
	 * @param relation the relation to set
	 */
	public void setRelation(String relation) {
		this.relation = relation;
	}


	/**
	 * @return the accupation
	 */
	public String getAccupation() {
		return accupation;
	}


	/**
	 * @param accupation the accupation to set
	 */
	public void setAccupation(String accupation) {
		this.accupation = accupation;
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
	 * @return the students
	 */
	public List<Student> getStudents() {
		return students;
	}


	/**
	 * @param students the students to set
	 */
	public void setStudents(List<Student> students) {
		this.students = students;
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
		return "Gaurdian [id=" + id + ", name=" + name + ", userId=" + userId + ", userType=" + userType + ", email="
				+ email + ", mobile=" + mobile + ", phone=" + phone + ", tempAddress=" + tempAddress + ", permAddress="
				+ permAddress + ", Gender=" + Gender + ", relation=" + relation + ", accupation=" + accupation
				+ ", boodGroup=" + boodGroup + ", dated=" + dated + ", students=" + students + "]";
	}


}