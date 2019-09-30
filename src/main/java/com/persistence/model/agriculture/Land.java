package com.persistence.model.agriculture;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

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
	
	public Land(Long userId) {
		this.userId = userId;
	}
	
	@Id
	@SequenceGenerator(name = "mySeqGen", sequenceName = "myDbSeq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "mySeqGen")	
	@Column(name = "land_id", unique = true, nullable = false)
	@Setter @Getter	
	private Long id;

	@Column(name = "user_id")
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

	@Column(name = "land_name")
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
	private LocalDateTime dated;

	@Column(name="updated")
	@Setter @Getter	
	private LocalDateTime updated;
	

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}