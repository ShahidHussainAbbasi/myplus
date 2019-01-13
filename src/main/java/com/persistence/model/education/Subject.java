package com.persistence.model.education;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(
        name = "subject",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "name")
        }
)	

public class Subject implements Serializable {
	private static final long serialVersionUID = 1L;
	
	@Column(name = "name", unique = true, nullable = false)
	private String name;

	@Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="subject_id", unique = true, nullable = false)
	private Long id;

	@Column(name="user_id")
	private Long userId;

	private String code;

	private String publisher;

	private String edition;
	
	private String dated;
	
	private Boolean status;
	
	//bi-directional many-to-one association to Grade
	@ManyToOne(optional = false)
	@JoinColumn(name="grade_id")
	private Grade grade;
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
	 * @return the code
	 */
	public String getCode() {
		return code;
	}


	/**
	 * @param code the code to set
	 */
	public void setCode(String code) {
		this.code = code;
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
	 * @return the status
	 */
	public Boolean getStatus() {
		return status;
	}


	/**
	 * @param status the status to set
	 */
	public void setStatus(Boolean status) {
		this.status = status;
	}


	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}


	/**
	 * @return the publisher
	 */
	public String getPublisher() {
		return publisher;
	}


	/**
	 * @param publisher the publisher to set
	 */
	public void setPublisher(String publisher) {
		this.publisher = publisher;
	}


	/**
	 * @return the edition
	 */
	public String getEdition() {
		return edition;
	}


	/**
	 * @param edition the edition to set
	 */
	public void setEdition(String edition) {
		this.edition = edition;
	}


	/**
	 * @return the grade
	 */
	public Grade getGrade() {
		return grade;
	}


	/**
	 * @param grade the grade to set
	 */
	public void setGrade(Grade grade) {
		this.grade = grade;
	}

	
}