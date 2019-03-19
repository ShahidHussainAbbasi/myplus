package com.web.dto.education;

import java.io.Serializable;

/**
 * The persistent class for the doctor database table.
 * 
 */
public class AlertsDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private Long uId;

	private String c;

	private String at;

	private String dc;
	
	private String dt;

	private String dp;

	private String ah;

	private String am;
	
	private String as;

	private String sdStr;

	private String edStr;

	private String st="Active";

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
	 * @return the a
	 */
	public String getC() {
		return c;
	}

	/**
	 * @param a the a to set
	 */
	public void setC(String c) {
		this.c = c;
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
	 * @return the sdStr
	 */
	public String getSdStr() {
		return sdStr;
	}

	/**
	 * @param sdStr the sdStr to set
	 */
	public void setSdStr(String sdStr) {
		this.sdStr = sdStr;
	}

	/**
	 * @return the edStr
	 */
	public String getEdStr() {
		return edStr;
	}

	/**
	 * @param edStr the edStr to set
	 */
	public void setEdStr(String edStr) {
		this.edStr = edStr;
	}

	
	/**
	 * @return the st
	 */
	public String getSt() {
		return st;
	}

	/**
	 * @param st the st to set
	 */
	public void setSt(String st) {
		this.st = st;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "AlertsDTO [id=" + id + ", userId=" + uId + ", consumer=" + c + ", alertType=" + at + ", deliveryType=" + dt + ", deliveryPeriod=" + dp
				+ ", alertHeading=" + ah + ", alertMessage=" + am + ", alertSignature=" + as + ", startDate=" + sdStr + ", endDate=" + edStr + "]";
	}

}