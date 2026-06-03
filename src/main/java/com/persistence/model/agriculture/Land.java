package com.persistence.model.agriculture;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity(name="Land")
@Table(name = "land", uniqueConstraints = { @UniqueConstraint(columnNames = {"land_name","user_id"}) })
public class Land implements Serializable {
	private static final long serialVersionUID = 1L;

	public Land() {
		
	}
	
	@Id
	@SequenceGenerator(name = "mySeqGen", sequenceName = "myDbSeq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "mySeqGen")	
	@Column(name = "land_id", unique = true, nullable = false)
	@Setter @Getter	
	private Long id;

//	@ManyToOne(optional = false)
//	@JoinColumn(name = "user")
	@Column(name = "user_id", nullable = false)
	@Setter @Getter	
	private Long userId;

	@Column(name = "user_type")
	@Setter @Getter	
	private String userType;

	@Column(name = "land_unit")
	@Setter @Getter	
	private String landUnit;    

	@Column(name = "total_land_unit")
	@Setter @Getter	
	private String totalLandUnit;

	
	@Column(name="land_name",nullable = false)	
	@Setter @Getter	
	private String landName;

	@Column(name = "land_type")
	@Setter @Getter	
	private String landType;

	@Column(name = "description")
	@Setter @Getter	
	private String description;

	@Column(name="dated", updatable=false)
	@Setter @Getter	
	private LocalDate dated;

	@Column(name="updated")
	@Setter @Getter	
	private LocalDate updated;
	

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}