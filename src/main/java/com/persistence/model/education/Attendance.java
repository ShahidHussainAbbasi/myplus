package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "attendance", uniqueConstraints = { @UniqueConstraint(columnNames = "attendance_id") })
public class Attendance implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "attendance_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "enroll_no")
	private String en;

	@Column(name = "student_name")
	private String sn;

	@Column(name = "grade_name")
	private String gn;

	@Column(name = "time_in")
	private LocalTime i;

	@Column(name = "time_out")
	private LocalTime o;

	@Column(name = "status")
	private Character s;

	@Column(name = "dated_time")
	private LocalDateTime dt;

	@Column(name = "remarks")
	private String r;


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
	 * @return the en
	 */
	public String getEn() {
		return en;
	}


	/**
	 * @param en the en to set
	 */
	public void setEn(String en) {
		this.en = en;
	}


	/**
	 * @return the sn
	 */
	public String getSn() {
		return sn;
	}


	/**
	 * @param sn the sn to set
	 */
	public void setSn(String sn) {
		this.sn = sn;
	}


	/**
	 * @return the gn
	 */
	public String getGn() {
		return gn;
	}


	/**
	 * @param gn the gn to set
	 */
	public void setGn(String gn) {
		this.gn = gn;
	}


	/**
	 * @return the i
	 */
	public LocalTime getI() {
		return i;
	}


	/**
	 * @param i the i to set
	 */
	public void setI(LocalTime i) {
		this.i = i;
	}


	/**
	 * @return the o
	 */
	public LocalTime getO() {
		return o;
	}


	/**
	 * @param o the o to set
	 */
	public void setO(LocalTime o) {
		this.o = o;
	}


	/**
	 * @return the s
	 */
	public Character getS() {
		return s;
	}


	/**
	 * @param s the s to set
	 */
	public void setS(Character s) {
		this.s = s;
	}

	/**
	 * @return the dt
	 */
	public LocalDateTime getDt() {
		return dt;
	}


	/**
	 * @param dt the dt to set
	 */
	public void setDt(LocalDateTime dt) {
		this.dt = dt;
	}


	/**
	 * @return the r
	 */
	public String getR() {
		return r;
	}


	/**
	 * @param r the r to set
	 */
	public void setR(String r) {
		this.r = r;
	}


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}