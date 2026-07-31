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
	private String enrollNo;

	private String sn;

	private String sen;

	private String gn;
	
	@Getter@Setter
	private Long gId;
	
	private String scn;

	private String g;

	@Getter@Setter
	private Long grId;
	
	private String discountType;

	private Integer discount;

	private Integer dueDayOfMonth;

	private Integer dueAmount;

	@Getter@Setter
	private Float fee;

	@ValidateEmpty
	private Integer feePaid;

	private String pdStr;

	private Integer otherDues;

	private String otherDuesDescription;

	@ValidateEmpty
	private String payee;

	@ValidateEmpty
	private String receivedBy;

	@ValidateEmpty
	private String receivedIn;

	private String checkNo;

	private Integer vehicleFee;

	private LocalDate lpd;

	private Integer dueBalance;

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
	public String getEnrollNo() {
		return enrollNo;
	}

	/**
	 * @param en the en to set
	 */
	public void setEnrollNo(String en) {
		this.enrollNo = en;
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
	public String getDiscountType() {
		return discountType;
	}

	/**
	 * @return the d
	 */
	public Integer getDiscount() {
		return discount;
	}

	/**
	 * @param d the d to set
	 */
	public void setDiscount(Integer d) {
		this.discount = d;
	}

	/**
	 * @param dt the dt to set
	 */
	public void setDiscountType(String dt) {
		this.discountType = dt;
	}

	/**
	 * @return the da
	 */
	public Integer getDueAmount() {
		return dueAmount;
	}

	/**
	 * @param da the da to set
	 */
	public void setDueAmount(Integer da) {
		this.dueAmount = da;
	}

	/**
	 * @return the dd
	 */
	public Integer getDueDayOfMonth() {
		return dueDayOfMonth;
	}

	/**
	 * @param ddStr the dd to set
	 */
	public void setDueDayOfMonth(Integer dd) {
		this.dueDayOfMonth = dd;
	}

	/**
	 * @return the fp
	 */
	public Integer getFeePaid() {
		return feePaid;
	}

	/**
	 * @param fp the fp to set
	 */
	public void setFeePaid(Integer fp) {
		this.feePaid = fp;
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
	 * @return the od
	 */
	public Integer getOtherDues() {
		return otherDues;
	}

	/**
	 * @param od the od to set
	 */
	public void setOtherDues(Integer od) {
		this.otherDues = od;
	}

	/**
	 * @return the odd
	 */
	public String getOtherDuesDescription() {
		return otherDuesDescription;
	}

	/**
	 * @param odd the odd to set
	 */
	public void setOtherDuesDescription(String odd) {
		this.otherDuesDescription = odd;
	}

	/**
	 * @return the p
	 */
	public String getPayee() {
		return payee;
	}

	/**
	 * @param p the p to set
	 */
	public void setPayee(String p) {
		this.payee = p;
	}

	/**
	 * @return the rb
	 */
	public String getReceivedBy() {
		return receivedBy;
	}

	/**
	 * @param rb the rb to set
	 */
	public void setReceivedBy(String rb) {
		this.receivedBy = rb;
	}

	/**
	 * @return the ri
	 */
	public String getReceivedIn() {
		return receivedIn;
	}

	/**
	 * @param ri the ri to set
	 */
	public void setReceivedIn(String ri) {
		this.receivedIn = ri;
	}

	/**
	 * @return the cn
	 */
	public String getCheckNo() {
		return checkNo;
	}

	/**
	 * @param cn the cn to set
	 */
	public void setCheckNo(String cn) {
		this.checkNo = cn;
	}

	/**
	 * @return the vf
	 */
	public Integer getVehicleFee() {
		return vehicleFee;
	}

	/**
	 * @param vf the vf to set
	 */
	public void setVehicleFee(Integer vf) {
		this.vehicleFee = vf;
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
	public Integer getDueBalance() {
		return dueBalance;
	}

	/**
	 * @param db the db to set
	 */
	public void setDueBalance(Integer db) {
		this.dueBalance = db;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}