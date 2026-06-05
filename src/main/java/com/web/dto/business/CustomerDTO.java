package com.web.dto.business;

import java.io.Serializable;
import java.time.LocalDate;
import com.validation.ValidateEmpty;

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
	private String name;

	private String customerType;	

	// @ValidMobileNumber(message = "Invalid contact number")
	private String contact;

	// @ValidateEmpty(message = "Customer email is required")
	private String email;

	private String address;

	// @ValidateEmpty(message = "Paid amount is required")
	private Float paidAmount;

    private Float dueAmount;

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