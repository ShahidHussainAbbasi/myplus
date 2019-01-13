package com.persistence.model.education;

import java.io.Serializable;
import java.util.Collection;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "school",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "name")
        }
)

public class School implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="school_id", unique = true, nullable = false)
	private Long id;

	private String name;

	@Column(name="user_id")
	private Long userId;

	private String email;

	private String phone;

	private String address;

//	private Boolean main = true;
	
	@Column(name="branch_name")
	private String branchName;
	
	private String dated;
	
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "schools_owners", joinColumns = @JoinColumn(name = "school_id", referencedColumnName = "school_id"), 
    inverseJoinColumns = @JoinColumn(name = "owner_id", referencedColumnName = "owner_id"))
    private Collection<SchoolOwner> owners;
	
//	@ManyToMany(mappedBy = "schools")
//    private Set<SchoolOwner> schoolOwners = new HashSet<>();
//	@OneToMany(mappedBy="school")
//	private List<SchoolOwner> schoolOwners;

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
	 * @return the phone
	 */
	public String getPhone() {
		return phone;
	}

	/**
	 * @param phone the phone to set
	 */
	public void setPhone(String phone) {
		this.phone = phone;
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
	 * @return the branchName
	 */
	public String getBranchName() {
		return branchName;
	}

	/**
	 * @param branchName the branchName to set
	 */
	public void setBranchName(String branchName) {
		this.branchName = branchName;
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
	 * @return the owners
	 */
	public Collection<SchoolOwner> getOwners() {
		return owners;
	}

	/**
	 * @param owners the owners to set
	 */
	public void setOwners(Collection<SchoolOwner> owners) {
		this.owners = owners;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}