package com.web.dto.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class AttendanceDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	@Getter@Setter
	private Long id;

	@Getter@Setter
	private Long userId;

	@Getter@Setter
	private String dtStr;

	@Getter@Setter
	private String en;
	
	@Getter@Setter
	private String sn;

	@Getter@Setter
	private Long grid;

	@Getter@Setter
	private String g;

	@Getter@Setter
	private String gn;

	@Getter@Setter
	private String status="Active";

	@Getter@Setter
	private LocalDateTime dt;
	
	@Getter@Setter
	private LocalTime in;

	@Getter@Setter
	private LocalTime out;

	@Getter@Setter
	private String rem;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}