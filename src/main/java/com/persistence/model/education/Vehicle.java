package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "vehicle")

public class Vehicle implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "agri_vehicle_gen", sequenceName = "agri_vehicle_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "agri_vehicle_gen")	
//	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "vehicle_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "vehicle_name", nullable = false)
	private String name;

	@Column(name = "vehicle_number", nullable = false)
	private String number;

	@Column(name = "driver_name")
	private String driverName;

	@Column(name = "driver_mobile")
	private String driverMobile;

	@Column(name = "owner_name")
	private String ownerName;

	@Column(name = "owner_mobile")
	private String ownerMobile;

	@Column(name = "user_id")
	private Long userId;

	private String status="Active";

	@Column(updatable = false)
	private LocalDateTime dated;

	private LocalDateTime updated;

	// create one to one relation to see subject/school performance
//	@ManyToOne(optional=false)
	@Column(name = "school_id")
	private Long schoolId;

//	// bi-directional many-to-one association to Student
//	@OneToMany(mappedBy = "vehicle")
//	@NotFound(action = NotFoundAction.IGNORE)
//	private Set<Student> students;

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
	 * @return the number
	 */
	public String getNumber() {
		return number;
	}

	/**
	 * @param number the number to set
	 */
	public void setNumber(String number) {
		this.number = number;
	}

	/**
	 * @return the driverName
	 */
	public String getDriverName() {
		return driverName;
	}

	/**
	 * @param driverName the driverName to set
	 */
	public void setDriverName(String driverName) {
		this.driverName = driverName;
	}

	/**
	 * @return the ownerName
	 */
	public String getOwnerName() {
		return ownerName;
	}

	/**
	 * @param ownerName the ownerName to set
	 */
	public void setOwnerName(String ownerName) {
		this.ownerName = ownerName;
	}

	/**
	 * @return the driverMobile
	 */
	public String getDriverMobile() {
		return driverMobile;
	}

	/**
	 * @param driverMobile the driverMobile to set
	 */
	public void setDriverMobile(String driverMobile) {
		this.driverMobile = driverMobile;
	}

	/**
	 * @return the ownerMobile
	 */
	public String getOwnerMobile() {
		return ownerMobile;
	}

	/**
	 * @param ownerMobile the ownerMobile to set
	 */
	public void setOwnerMobile(String ownerMobile) {
		this.ownerMobile = ownerMobile;
	}

	/**
	 * @return the school
	 */

	/**
	 * @return the userId
	 */
	public Long getUserId() {
		return userId;
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
	 * @param userId the userId to set
	 */
	public void setUserId(Long userId) {
		this.userId = userId;
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

//	/**
//	 * @return the students
//	 */
//	public Set<Student> getStudents() {
//		return students;
//	}
//
//	/**
//	 * @param students the students to set
//	 */
//	public void setStudents(Set<Student> students) {
//		this.students = students;
//	}

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