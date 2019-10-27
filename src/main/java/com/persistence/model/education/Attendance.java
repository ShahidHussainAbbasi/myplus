package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "attendance", uniqueConstraints = { @UniqueConstraint(columnNames = "attendance_id") })
public class Attendance implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "attendance_gen", sequenceName = "attendance_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "attendance_gen")	
	@Column(name = "attendance_id", unique = true, nullable = false)
	@Getter@Setter
	private Long id;

	@Column(name = "user_id")
	@Getter@Setter
	private Long userId;

	@Column(name = "enroll_no")
	@Getter@Setter
	private String en;

	@Column(name = "student_name")
	@Getter@Setter
	private String sn;

	@Getter@Setter
	@Column(name = "grade_Id")
	private Long grid;

	@Column(name = "grade_name")
	@Getter@Setter
	private String gn;

	@Column(name = "time_in")
	@Getter@Setter
	private LocalTime in;

	@Column(name = "time_out")
	@Getter@Setter
	private LocalTime out;

	@Column(name = "status")
	@Getter@Setter
	private String status="Active";

	@Column(name = "dated_time")
	@Getter@Setter
	private LocalDateTime dt;

	@Column(name = "remarks")
	@Getter@Setter
	private String rem;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}