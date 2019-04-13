package com.web.dto.education;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * 
 */

/**
 * @author sabbasi
 *
 */
public class FeeVoucherDTO {

	private Short vb = 1;
	private String vi = null;
	private Short vp = 1;
	
	@Getter@Setter
	private Short rb = -1;//report by
	@Getter@Setter
	private Short rbs = -1;//report by student status
	@Getter@Setter
	private String ri = null;//report input
	@Getter@Setter
	private Short rp = 1;
	@Getter@Setter
	private String redStr = null;
	@Getter@Setter
	private String rsdStr = null;
	private String sdStr = null;
	private String edStr = null;
	private LocalDate sd = LocalDate.now();
	private LocalDate ed = LocalDate.now();
	/**
	 * @return the vb
	 */
	public Short getVb() {
		return vb;
	}
	/**
	 * @param vb the vb to set
	 */
	public void setVb(Short vb) {
		this.vb = vb;
	}
	/**
	 * @return the vi
	 */
	public String getVi() {
		return vi;
	}
	/**
	 * @param vi the vi to set
	 */
	public void setVi(String vi) {
		this.vi = vi;
	}
	/**
	 * @return the vp
	 */
	public Short getVp() {
		return vp;
	}
	/**
	 * @param vp the vp to set
	 */
	public void setVp(Short vp) {
		this.vp = vp;
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
	
}
