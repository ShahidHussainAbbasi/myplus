package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.myplus.business_service.entity.enums.CustomerType;
import com.myplus.business_service.validation.ValidMobileNumber;
import com.myplus.business_service.validation.ValidateEmpty;
import com.myplus.common.security.SafeText;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
// @JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long customerId;

	@ValidateEmpty(message = "Customer name is required")
	@SafeText
	@jakarta.validation.constraints.NotBlank(message = "name is required")
	private String name;

	private CustomerType customerType;

	// @ValidMobileNumber(message = "Invalid contact number")
	@SafeText
	private String contact;

	// @ValidateEmpty(message = "Customer email is required")
	@SafeText
	private String email;

	@SafeText
	private String address;

	// @ValidateEmpty(message = "Paid amount is required")
	private BigDecimal paidAmount;

    private BigDecimal dueAmount;

    private LocalDate dueDate;
	
	private String dated;

	private String updated;

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