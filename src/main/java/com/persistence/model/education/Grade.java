package com.persistence.model.education;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "grade",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "name")
        }
)

public class Grade implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Column(name = "name", unique = true, nullable = false)
	private String name;

	@Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="grade_id", unique = true, nullable = false)
	private Long id;

	@Column(name="user_id")
	private Long userId;

	private String code;

	private String dated;
	
	private String section;
	
	@Column(name="time_from")
	private String timeFrom;
	
	@Column(name="time_to")
	private String timeTo;
	
	//bi-directional many-to-one association to School
	@ManyToOne(optional = false)
	@JoinColumn(name="school_id")
	private School school;

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
	public String getTimeFrom() {
		return timeFrom;
	}


	/**
	 * @param timeFrom the timeFrom to set
	 */
	public void setTimeFrom(String timeFrom) {
		this.timeFrom = timeFrom;
	}


	/**
	 * @return the timeTo
	 */
	public String getTimeTo() {
		return timeTo;
	}


	/**
	 * @param timeTo the timeTo to set
	 */
	public void setTimeTo(String timeTo) {
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


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}