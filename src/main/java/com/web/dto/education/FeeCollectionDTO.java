package com.web.dto.education;

import java.io.Serializable;
import java.time.LocalDate;

import com.validation.ValidateEmpty;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */

public class FeeCollectionDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	private Long userId;

	@ValidateEmpty
	private String en;

	private String sn;

	private String sen;

	private String gn;
	
	@Getter@Setter
	private Long gId;
	
	private String scn;

	private String g;

	@Getter@Setter
	private Long grId;
	
	private String dt;

	private Integer d;

	private Integer dd;

	private Integer da;

//	@ValidateEmpty
	private Integer f;

	@ValidateEmpty
	private Integer fp;

	private String pdStr;

	private Integer od;

	private String odd;

	@ValidateEmpty
	private String p;

	@ValidateEmpty
	private String rb;

	@ValidateEmpty
	private String ri;

	private String cn;

	private Integer vf;

	private LocalDate lpd;

	private Integer db;

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
	 * @return the sen
	 */
	public String getSen() {
		return sen;
	}

	/**
	 * @param sen the sen to set
	 */
	public void setSen(String sen) {
		this.sen = sen;
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
	 * @return the g
	 */
	public String getG() {
		return g;
	}

	/**
	 * @return the scn
	 */
	public String getScn() {
		return scn;
	}

	/**
	 * @param scn the scn to set
	 */
	public void setScn(String scn) {
		this.scn = scn;
	}

	/**
	 * @param g the g to set
	 */
	public void setG(String g) {
		this.g = g;
	}

	/**
	 * @return the dt
	 */
	public String getDt() {
		return dt;
	}

	/**
	 * @return the d
	 */
	public Integer getD() {
		return d;
	}

	/**
	 * @param d the d to set
	 */
	public void setD(Integer d) {
		this.d = d;
	}

	/**
	 * @param dt the dt to set
	 */
	public void setDt(String dt) {
		this.dt = dt;
	}

	/**
	 * @return the da
	 */
	public Integer getDa() {
		return da;
	}

	/**
	 * @param da the da to set
	 */
	public void setDa(Integer da) {
		this.da = da;
	}

	/**
	 * @return the dd
	 */
	public Integer getDd() {
		return dd;
	}

	/**
	 * @param ddStr the dd to set
	 */
	public void setDd(Integer dd) {
		this.dd = dd;
	}

	/**
	 * @return the fp
	 */
	public Integer getFp() {
		return fp;
	}

	/**
	 * @param fp the fp to set
	 */
	public void setFp(Integer fp) {
		this.fp = fp;
	}

	/**
	 * @return the pdStr
	 */
	public String getPdStr() {
		return pdStr;
	}

	/**
	 * @param pdStr the pdStr to set
	 */
	public void setPdStr(String pdStr) {
		this.pdStr = pdStr;
	}

	/**
	 * @return the f
	 */
	public Integer getF() {
		return f;
	}

	/**
	 * @param f the f to set
	 */
	public void setF(Integer f) {
		this.f = f;
	}

	/**
	 * @return the od
	 */
	public Integer getOd() {
		return od;
	}

	/**
	 * @param od the od to set
	 */
	public void setOd(Integer od) {
		this.od = od;
	}

	/**
	 * @return the odd
	 */
	public String getOdd() {
		return odd;
	}

	/**
	 * @param odd the odd to set
	 */
	public void setOdd(String odd) {
		this.odd = odd;
	}

	/**
	 * @return the p
	 */
	public String getP() {
		return p;
	}

	/**
	 * @param p the p to set
	 */
	public void setP(String p) {
		this.p = p;
	}

	/**
	 * @return the rb
	 */
	public String getRb() {
		return rb;
	}

	/**
	 * @param rb the rb to set
	 */
	public void setRb(String rb) {
		this.rb = rb;
	}

	/**
	 * @return the ri
	 */
	public String getRi() {
		return ri;
	}

	/**
	 * @param ri the ri to set
	 */
	public void setRi(String ri) {
		this.ri = ri;
	}

	/**
	 * @return the cn
	 */
	public String getCn() {
		return cn;
	}

	/**
	 * @param cn the cn to set
	 */
	public void setCn(String cn) {
		this.cn = cn;
	}

	/**
	 * @return the vf
	 */
	public Integer getVf() {
		return vf;
	}

	/**
	 * @param vf the vf to set
	 */
	public void setVf(Integer vf) {
		this.vf = vf;
	}

	/**
	 * @return the lpd
	 */
	public LocalDate getLpd() {
		return lpd;
	}

	/**
	 * @param lpd the lpd to set
	 */
	public void setLpd(LocalDate lpd) {
		this.lpd = lpd;
	}

	/**
	 * @return the db
	 */
	public Integer getDb() {
		return db;
	}

	/**
	 * @param db the db to set
	 */
	public void setDb(Integer db) {
		this.db = db;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}