package com.web.dto.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class AttendanceDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private Long userId;

	private String dtStr;

	private String en;

	private String sn;

	private String gn;

	private Character s;

	private LocalDateTime dt;
	
	private LocalTime i;
	
	private LocalTime o;
	
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
	 * @return the dtStr
	 */
	public String getDtStr() {
		return dtStr;
	}


	/**
	 * @param dtStr the dtStr to set
	 */
	public void setDtStr(String dtStr) {
		this.dtStr = dtStr;
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