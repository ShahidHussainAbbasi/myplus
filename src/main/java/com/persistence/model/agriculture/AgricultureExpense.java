package com.persistence.model.agriculture;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Entity
@Table(name = "agriculture_expense", uniqueConstraints = { @UniqueConstraint(columnNames = "agri_expense_id") })
public class AgricultureExpense implements Serializable {
	private static final long serialVersionUID = 1L;

	public AgricultureExpense() {
		
	}
	public AgricultureExpense(Long userId) {
		this.userId = userId;
	}
	
	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	@Column(name = "agri_expense_id", unique = true, nullable = false)
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