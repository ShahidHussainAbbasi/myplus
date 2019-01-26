package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "school")

public class School implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "school_id", unique = true, nullable = false)
	private Long id;

	private String name;

	@Column(name = "user_id")
	private Long userId;

	private String email;

	private String phone;

	private String address;

	@Column(name = "branch_name")
	private String branchName;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime dated;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;

	private String status;

	@ManyToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
	@JoinTable(name = "schools_owners", joinColumns = @JoinColumn(name = "school_id", referencedColumnName = "school_id"), inverseJoinColumns = @JoinColumn(name = "owner_id", referencedColumnName = "owner_id"))
	private Set<Owner> owners;

	@ManyToMany(mappedBy = "schools")
	private Set<Staff> staffs;

//	@OneToOne(mappedBy="school",cascade = CascadeType.ALL)
//	private Student student;
//
	// bi-directional one-to-many association to Vehicle
	@OneToMany(cascade = CascadeType.ALL)
	private Set<Vehicle> vehicles;

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
	 * @return the owners
	 */
	public Set<Owner> getOwners() {
		return owners;
	}

	/**
	 * @param owners the owners to set
	 */
	public void setOwners(Set<Owner> owners) {
		this.owners = owners;
	}

	/**
	 * @return the staffs
	 */
	public Set<Staff> getStaffs() {
		return staffs;
	}

	/**
	 * @param staffs the staffs to set
	 */
	public void setStaffs(Set<Staff> staffs) {
		this.staffs = staffs;
	}

	/**
	 * @return the vehicles
	 */
	public Set<Vehicle> getPickAndDrops() {
		return vehicles;
	}

	/**
	 * @param vehicles the vehicles to set
	 */
	public void setPickAndDrops(Set<Vehicle> vehicles) {
		this.vehicles = vehicles;
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

	/**
	 * @return the vehicles
	 */
	public Set<Vehicle> getVehicles() {
		return vehicles;
	}

	/**
	 * @param vehicles the vehicles to set
	 */
	public void setVehicles(Set<Vehicle> vehicles) {
		this.vehicles = vehicles;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}