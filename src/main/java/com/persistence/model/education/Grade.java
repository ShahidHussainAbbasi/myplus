package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "grade")

public class Grade implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "grade_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "user_id")
	private Long userId;

	private String code;

//	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable=false)
	private LocalDateTime dated;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;
	
	private String section;

	@Column(name = "time_from")
//	@Temporal(TemporalType.TIME)
	private LocalTime timeFrom;

	@Column(name = "time_to")
//	@Temporal(TemporalType.TIME)
	private LocalTime timeTo;
	
	private String status;

	// bi-directional many-to-one association to School
	@ManyToOne(optional=false)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "school_id")
	private School school;

	@ManyToMany(mappedBy = "grades")
	private Set<Staff> staff;
	
	@Getter@Setter
	private Float fee;
	

	private Long room;

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
	 * @return the section
	 */
	public String getSection() {
		return section;
	}

	/**
	 * @param section the section to set
	 */
	public void setSection(String section) {
		this.section = section;
	}

	/**
	 * @return the timeFrom
	 */
	public LocalTime getTimeFrom() {
		return timeFrom;
	}

	/**
	 * @param timeFrom the timeFrom to set
	 */
	public void setTimeFrom(LocalTime timeFrom) {
		this.timeFrom = timeFrom;
	}

	/**
	 * @return the timeTo
	 */
	public LocalTime getTimeTo() {
		return timeTo;
	}

	/**
	 * @param timeTo the timeTo to set
	 */
	public void setTimeTo(LocalTime timeTo) {
		this.timeTo = timeTo;
	}

	/**
	 * @return the room
	 */
	public Long getRoom() {
		return room;
	}

	/**
	 * @param room the room to set
	 */
	public void setRoom(Long room) {
		this.room = room;
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

//	/**
//	 * @return the staffs
//	 */
//	public Set<Staff> getStaffs() {
//		return staffs;
//	}
//
//	/**
//	 * @param staffs the staffs to set
//	 */
//	public void setStaffs(Set<Staff> staffs) {
//		this.staffs = staffs;
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

}