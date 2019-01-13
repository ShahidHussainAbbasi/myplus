package com.persistence.model.education;

import java.io.Serializable;
import java.util.Collection;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
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
@Table(
        name = "school_owner",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "name")
        }
)

public class SchoolOwner implements Serializable {
	private static final long serialVersionUID = 1L;
	
	
	@Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="owner_id", unique = true, nullable = false)
	private Long id;

	@Column(name="user_id")
	private Long userId;

	@ValidateEmpty
	@Column(name="name", unique = true, nullable = false)
	private String name;

	@ValidEmail
	private String email;

	private String mobile;

	private String address;

	private String dated;

    @ManyToMany(mappedBy = "owners")
    private Collection<School> schools;
	
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
	public String getDated() {
		return dated;
	}

	/**
	 * @param dated the dated to set
	 */
	public void setDated(String dated) {
		this.dated = dated;
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
	public Collection<School> getSchools() {
		return schools;
	}

	/**
	 * @param schools the schools to set
	 */
	public void setSchools(Collection<School> schools) {
		this.schools = schools;
	}

	
}