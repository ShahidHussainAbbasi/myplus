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

	/** B2B P1 (#9): most this customer may owe. Blank/null = no limit. */
	private java.math.BigDecimal creditLimit;

	/** B2B P1 (#9): net terms in days (Net 30/60). Blank/null = none; due date stays hand-entered. */
	private Integer paymentTermsDays;

	// @ValidMobileNumber(message = "Invalid contact number")
	@SafeText
	private String contact;

	// @ValidateEmpty(message = "Customer email is required")
	@SafeText
	private String email;

	@SafeText
	private String address;

	/**
	 * B2B-P3g: trade-buyer identity printed on an invoice. {@code address} is one free-text line and cannot
	 * be split reliably after the fact, so {@code city} is captured separately rather than parsed out of it.
	 *
	 * <p>{@code licenseNo}/{@code licenseExpiry} are the BUYER's licence — a pharmaceutical distributor may
	 * only supply a licensed reseller, and the licence is printed on the invoice as evidence of that. The
	 * seller's own licence is a setting, not a column (see {@link LetterheadDTO}).
	 */
	@SafeText
	private String city;

	@SafeText
	private String cnic;

	@SafeText
	private String licenseNo;

	private LocalDate licenseExpiry;

	// @ValidateEmpty(message = "Paid amount is required")
	private BigDecimal paidAmount;

    private BigDecimal dueAmount;

    private BigDecimal creditBalance;   // SF-5 Model B: redeemable store credit the customer holds

    private Long partyId;               // P1: shared party/contact master id

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