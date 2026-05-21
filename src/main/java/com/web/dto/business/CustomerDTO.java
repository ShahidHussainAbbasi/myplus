package com.web.dto.business;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.persistence.model.business.Sell;
import com.persistence.model.business.enums.CustomerType;
import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long customerId;

	@ValidateEmpty(message = "Customer name is required")
	private String name;

	private CustomerType customerType;	

	@ValidMobileNumber(message = "Invalid contact number")
	private String contact;

	@ValidateEmpty(message = "Customer email is required")
	private String email;

	private String address;

	@ValidateEmpty(message = "Paid amount is required")
	private Float paidAmount;

    private Float dueAmount;

    private LocalDate dueDate;
	
	private LocalDateTime dated;

	private LocalDateTime updated;

	private CustomerHistoryDTO customerHistory;

	private Long userId;

	private String userType;

	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}