package com.persistence.model.agriculture;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "agriculture_expense")
public class AgricultureExpense implements Serializable {
	private static final long serialVersionUID = 1L;

	public AgricultureExpense() {
		
	}
	public AgricultureExpense(Long userId) {
		this.userId = userId;
	}
	public AgricultureExpense(Long userId,Long landId,String expenseName,LocalDate updated) {
		this.userId = userId;
		this.landId = landId;
		this.expenseName = expenseName;
		this.updated = updated;
	}
	
	@Id
	@SequenceGenerator(name = "agri_expense_gen", sequenceName = "agri_expense_seq",initialValue = 1, allocationSize = 1)
	@GeneratedValue(generator = "agri_expense_gen")	
//	@GeneratedValue(strategy = GenerationType.SEQUENCE)
	@Column(name = "agri_expense_id", unique = true, nullable = false)
	@Setter @Getter	
	private Long id;

	@Column(name = "user_id", nullable = false)
	@Setter @Getter	
	private Long userId;

	@Column(name = "user_type")
	@Setter @Getter	
	private String userType;

	@Column(name = "land_id")
	@Setter @Getter	
	private Long landId;

	@Column(name = "land_name")
	@Setter @Getter	
	private String landName;

	//	@Column(name = "land_unit")
//	@Setter @Getter	
//	private String landUnit;    
//
//	@Column(name = "total_land_unit")
//	@Setter @Getter	
//	private String totalLandUnit;

	@Column(name = "crop_name")
	@Setter @Getter	
	private String cropName;

	@Column(name = "crop_type")
	@Setter @Getter	
	private String cropType;

	@Column(name = "expense_type")
	@Setter @Getter	
	private String expenseType;

	@Column(name = "expense_name")
	@Setter @Getter	
	private String expenseName;

	@Column(name = "amount")
	@Setter @Getter	
	private Float amount;

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