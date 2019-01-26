package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.validation.ValidEmail;
import com.validation.ValidateEmpty;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "owner", uniqueConstraints = { @UniqueConstraint(columnNames = "name") })

public class Owner implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "owner_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@ValidateEmpty
	@Column(name = "name", unique = true, nullable = false)
	private String name;

	@ValidEmail
	private String email;

	private String mobile;

	private String address;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime dated;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;

	private String status;

	@ManyToMany(mappedBy = "owners")
	private Set<School> schools;

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
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the mobile
	 */
	public String getMobile() {
		return mobile;
	}

	/**
	 * @param mobile the mobile to set
	 */
	public void setMobile(String mobile) {
		this.mobile = mobile;
	}

	/**
	 * @return the address
	 */
	public String getAddress() {
		return address;
	}

	/**
	 * @param address the address to set
	 */
	public void setAddress(String address) {
		this.address = address;
	}

	/**
	 * @return the dated
	 */
	public LocalDateTime getDated() {
		return dated;
	}

	/**
	 * @param dated the dated to set
	 */
	public void setDated(LocalDateTime dated) {
		this.dated = dated;
	}

	/**
	 * @return the updated
	 */
	public LocalDateTime getUpdated() {
		return updated;
	}

	/**
	 * @param updated the updated to set
	 */
	public void setUpdated(LocalDateTime updated) {
		this.updated = updated;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	/**
	 * @return the email
	 */
	public String getEmail() {
		return email;
	}

	/**
	 * @param email the email to set
	 */
	public void setEmail(String email) {
		this.email = email;
	}

	/**
	 * @return the schools
	 */
	public Set<School> getSchools() {
		return schools;
	}

	/**
	 * @param schools the schools to set
	 */
	public void setSchools(Set<School> schools) {
		this.schools = schools;
	}

	/**
	 * @return the status
	 */
	public String getStatus() {
		return status;
	}

	/**
	 * @param status the status to set
	 */
	public void setStatus(String status) {
		this.status = status;
	}

}