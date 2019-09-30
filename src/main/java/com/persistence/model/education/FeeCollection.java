package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "fee_collection", uniqueConstraints = { @UniqueConstraint(columnNames = "fc_id") })
public class FeeCollection implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "agri_feeCollec_gen", sequenceName = "agri_feeCollec_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "agri_feeCollec_gen")	
//	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "fc_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "enroll_no")
	private String en;

	@Column(name = "discount_type")
	private String dt;

	@Column(name = "discount")
	private Integer d;

	@Column(name = "due_day_of_month")
	private Integer dd;

	@Column(name = "due_amount")
	private Integer da;

	@Column(name = "fee")
	private Integer f;

	@Column(name = "fee_paid")
	private Integer fp;

	@Column(name = "payment_date")
	private LocalDate pd;

	@Column(name = "other_dues")
	private Integer od;

	@Column(name = "other_dues_description")
	private String odd;

	@Column(name = "payee")
	private String p;

	@Column(name = "recieved_by")
	private String rb;

	@Column(name = "recieved_in")
	private String ri;

	@Column(name = "check_no")
	private String cn;

	@Column(name = "vehicle_fee")
	private Integer vf;

	@Column(name = "due_balance")
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
	 * @param dd the dd to set
	 */
	public void setDd(Integer dd) {
		this.dd = dd;
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
	 * @return the pd
	 */
	public LocalDate getPd() {
		return pd;
	}

	/**
	 * @param pd the pd to set
	 */
	public void setPd(LocalDate pd) {
		this.pd = pd;
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