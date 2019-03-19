package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "alert")

public class Alerts implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "alert_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long uId;

	@Column(name = "consumers")
	private String c;

	@Column(name = "user_type")
	private Long ut;

	@Column(name = "alert_type")
	private String at;

	@Column(name = "delivery_type")
	private String dt;

	@Column(name = "delivery_channel")
	private String dc;

	@Column(name = "delivery_period")
	private String dp;

	@Column(name = "alert_heading")
	private String ah;

	@Column(name = "alert_message")
	private String am;
	
	@Column(name = "alert_signature")
	private String as;

	@Column(name = "start_date")
	private LocalDate sd;

	@Column(name = "end_date")
	private LocalDate ed;

	@Column(name = "status")
	private String st;	
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
	 * @return the uId
	 */
	public Long getuId() {
		return uId;
	}

	/**
	 * @param uId the uId to set
	 */
	public void setuId(Long uId) {
		this.uId = uId;
	}

	
	/**
	 * @return the c
	 */
	public String getC() {
		return c;
	}

	/**
	 * @param c the c to set
	 */
	public void setC(String c) {
		this.c = c;
	}

	/**
	 * @return the ut
	 */
	public Long getUt() {
		return ut;
	}

	/**
	 * @param ut the ut to set
	 */
	public void setUt(Long ut) {
		this.ut = ut;
	}

	/**
	 * @return the at
	 */
	public String getAt() {
		return at;
	}

	/**
	 * @param at the at to set
	 */
	public void setAt(String at) {
		this.at = at;
	}

	/**
	 * @return the dt
	 */
	public String getDt() {
		return dt;
	}

	/**
	 * @param dt the dt to set
	 */
	public void setDt(String dt) {
		this.dt = dt;
	}

	
	/**
	 * @return the dc
	 */
	public String getDc() {
		return dc;
	}

	/**
	 * @param dc the dc to set
	 */
	public void setDc(String dc) {
		this.dc = dc;
	}

	/**
	 * @return the dp
	 */
	public String getDp() {
		return dp;
	}

	/**
	 * @param dp the dp to set
	 */
	public void setDp(String dp) {
		this.dp = dp;
	}

	/**
	 * @return the ah
	 */
	public String getAh() {
		return ah;
	}

	/**
	 * @param ah the ah to set
	 */
	public void setAh(String ah) {
		this.ah = ah;
	}

	/**
	 * @return the am
	 */
	public String getAm() {
		return am;
	}

	/**
	 * @param am the am to set
	 */
	public void setAm(String am) {
		this.am = am;
	}

	/**
	 * @return the as
	 */
	public String getAs() {
		return as;
	}

	/**
	 * @param as the as to set
	 */
	public void setAs(String as) {
		this.as = as;
	}

	/**
	 * @return the sd
	 */
	public LocalDate getSd() {
		return sd;
	}

	/**
	 * @param sd the sd to set
	 */
	public void setSd(LocalDate sd) {
		this.sd = sd;
	}

	/**
	 * @return the ed
	 */
	public LocalDate getEd() {
		return ed;
	}

	/**
	 * @param ed the ed to set
	 */
	public void setEd(LocalDate ed) {
		this.ed = ed;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}