package com.web.dto.education;

import java.io.Serializable;

import com.validation.ValidateEmpty;

public class GradeDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	@ValidateEmpty
	private String name;

	private Long id;

	private Long userId;

	private String userType;

	private String code;

	private String dated;

	@ValidateEmpty
	private String timeFrom;

	@ValidateEmpty
	private String timeTo;

	@ValidateEmpty
	private Long schoolId;

	private String schoolName;

	private String room;

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
	 * @return the schoolName
	 */
	public String getSchoolName() {
		return schoolName;
	}

	/**
	 * @param schoolName the schoolName to set
	 */
	public void setSchoolName(String schoolName) {
		this.schoolName = schoolName;
	}

	/**
	 * @return the room
	 */
	public String getRoom() {
		return room;
	}

	/**
	 * @param room the room to set
	 */
	public void setRoom(String room) {
		this.room = room;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "GradeDTO [name=" + name + ", id=" + id + ", userId=" + userId + ", userType=" + userType + ", code="
				+ code + ", dated=" + dated + ", timeFrom=" + timeFrom + ", timeTo=" + timeTo + ", schoolId=" + schoolId
				+ ", room=" + room + "]";
	}

}