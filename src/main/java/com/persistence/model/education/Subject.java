package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "subject")

public class Subject implements Serializable {
	private static final long serialVersionUID = 1L;

	@Column(name = "name", nullable = false)
	private String name;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "subject_id", nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	private String code;

	private String publisher;

	private String edition;

//	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable=false)
	private LocalDateTime dated;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;

	private String status;

//	//create one to one relation to see subject/school performance
//	@ManyToOne(cascade=CascadeType.ALL)
//	@JoinColumn(name = "school_id")
//	private School school;

	// @ManyToMany(cascade=CascadeType.ALL)
//	@JoinTable(name = "subjects_schools", joinColumns = @JoinColumn(name = "subject_id", referencedColumnName = "subject_id"), inverseJoinColumns = @JoinColumn(name = "school_id", referencedColumnName = "school_id"))
//	private Set<School> schools;

	// bi-directional many-to-one association to Grade
	@ManyToOne(optional=false)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "grade_id")
	private Grade grade;

//	// bi-directional many-to-one association to Staff
//	@ManyToOne(cascade=CascadeType.ALL)
//	@JoinColumn(name = "staff_id")
//	private Staff staff;

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
	 * @return the publisher
	 */
	public String getPublisher() {
		return publisher;
	}

	/**
	 * @param publisher the publisher to set
	 */
	public void setPublisher(String publisher) {
		this.publisher = publisher;
	}

	/**
	 * @return the edition
	 */
	public String getEdition() {
		return edition;
	}

	/**
	 * @param edition the edition to set
	 */
	public void setEdition(String edition) {
		this.edition = edition;
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

//	/**
//	 * @return the school
//	 */
//	public School getSchool() {
//		return school;
//	}
//
//	/**
//	 * @param school the school to set
//	 */
//	public void setSchool(School school) {
//		this.school = school;
//	}

//	/**
//	 * @return the staff
//	 */
//	public Staff getStaff() {
//		return staff;
//	}
//
//	/**
//	 * @param staff the staff to set
//	 */
//	public void setStaff(Staff staff) {
//		this.staff = staff;
//	}

}