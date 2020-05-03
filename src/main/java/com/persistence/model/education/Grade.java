package com.persistence.model.education;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "grade")
@Data
public class Grade implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@SequenceGenerator(name = "grade_gen", sequenceName = "grade_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "grade_gen")	
//	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "grade_id", unique = true, nullable = false)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "user_id")
	private Long userId;

	private String code;

//	@Temporal(TemporalType.TIMESTAMP)
	@Column(updatable=false)
	private LocalDateTime dated;

//	@Temporal(TemporalType.TIMESTAMP)
	private LocalDateTime updated;
	
	private String section;

	@Column(name = "time_from")
//	@Temporal(TemporalType.TIME)
	private LocalTime timeFrom;

	@Column(name = "time_to")
//	@Temporal(TemporalType.TIME)
	private LocalTime timeTo;
	
	private String status;

/*	// bi-directional many-to-one association to School
	@ManyToOne(optional=false)
	@NotFound(action = NotFoundAction.IGNORE)
	@JoinColumn(name = "school_id")
*/	
	@Column(name = "school_id")
	private Long schoolId;

/*	@ManyToMany(mappedBy = "grades")
	private Set<Staff> staff;
*/	
	private Float fee;
	
	private Long room;


}