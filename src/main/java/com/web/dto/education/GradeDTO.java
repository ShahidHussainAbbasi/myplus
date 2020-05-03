package com.web.dto.education;

import java.io.Serializable;

import com.validation.ValidateEmpty;

import lombok.Data;

@Data
public class GradeDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	@ValidateEmpty
	private String name;

	private Long id;

	private Long userId;

	private String userType;

	private String code;

	private String datedStr;

	private String updatedStr;

	private String timeFromStr;

	private String timeFrom;

	private String timeToStr;

	private String timeTo;

	@ValidateEmpty
	private Long schoolId;

	private String schoolName;

	private String room;

	private String status="Active";

	private Float fee;


	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "GradeDTO [name=" + name + ", id=" + id + ", userId=" + userId + ", userType=" + userType + ", code="
				+ code + ", timeFrom=" + timeFrom + ", timeTo=" + timeTo + ", schoolId=" + schoolId
				+ ", room=" + room + "]";
	}

}