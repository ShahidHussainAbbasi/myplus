package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "discount")

public class Discount implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "discount_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	private String name;

	private String discountIn;// percent/amount

	private Float amount;

	private LocalDateTime start;

	private LocalDateTime end;

	private String description;

	private String referenceName;

	private String referenceMobile;

	private String status;
	
//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime dated;
	
//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;
	

	// bi-directional many-to-one association to Student
	@ManyToOne(optional=false)//(cascade = CascadeType.ALL)
	@NotFound(action = NotFoundAction.IGNORE)//toOne is eager that's why we add NotFoundAction.IGNORE if not found
	@JoinColumn(name = "student_id")
	private Student student;

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
	 * @return the discountIn
	 */
	public String getDiscountIn() {
		return discountIn;
	}

	/**
	 * @param discountIn the discountIn to set
	 */
	public void setDiscountIn(String discountIn) {
		this.discountIn = discountIn;
	}

	/**
	 * @return the amount
	 */
	public Float getAmount() {
		return amount;
	}

	/**
	 * @param amount the amount to set
	 */
	public void setAmount(Float amount) {
		this.amount = amount;
	}

	/**
	 * @return the start
	 */
	public LocalDateTime getStart() {
		return start;
	}

	/**
	 * @param start the start to set
	 */
	public void setStart(LocalDateTime start) {
		this.start = start;
	}

	/**
	 * @return the end
	 */
	public LocalDateTime getEnd() {
		return end;
	}

	/**
	 * @param end the end to set
	 */
	public void setEnd(LocalDateTime end) {
		this.end = end;
	}

	/**
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * @param description the description to set
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * @return the referenceName
	 */
	public String getReferenceName() {
		return referenceName;
	}

	/**
	 * @param referenceName the referenceName to set
	 */
	public void setReferenceName(String referenceName) {
		this.referenceName = referenceName;
	}

	/**
	 * @return the referenceMobile
	 */
	public String getReferenceMobile() {
		return referenceMobile;
	}

	/**
	 * @param referenceMobile the referenceMobile to set
	 */
	public void setReferenceMobile(String referenceMobile) {
		this.referenceMobile = referenceMobile;
	}

	/**
	 * @return the student
	 */
	public Student getStudent() {
		return student;
	}

	/**
	 * @param student the student to set
	 */
	public void setStudent(Student student) {
		this.student = student;
	}

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
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

}