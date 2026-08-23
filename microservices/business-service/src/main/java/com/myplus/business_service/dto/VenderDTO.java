package com.myplus.business_service.dto;

import java.io.Serializable;

import com.myplus.business_service.validation.ValidEmail;
import com.myplus.business_service.validation.ValidMobileNumber;

import lombok.Data;

/**
 * The persistent class for the doctor database table.
 * 
 */
@Data
public class VenderDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;
	private Long userId;
	private String userType;
	@jakarta.validation.constraints.NotBlank(message = "name is required")
	private String name;
	/**
	 * The brands this supplier represents.
	 *
	 * <p>A COMMA-SEPARATED STRING on the wire, not a list, and that is forced by the plumbing rather than
	 * chosen: the monolith's form proxy does {@code params.put(k, v[0])}, keeping only the FIRST value of a
	 * repeated parameter. A native multi-select posting {@code companyIds=1&companyIds=2} would silently
	 * arrive here as {@code 1}. The browser's own form serialiser already joins a multi-select's selected
	 * values with commas, so one parameter carries the whole set intact.
	 */
	private String companyIds;

	/**
	 * The ORIGINAL single-brand field, still accepted.
	 *
	 * <p>Kept because widening an endpoint must not break the callers it already has: four Cypress specs and
	 * any integration outside this repo post {@code companyId}. A save that sends only this is treated as a
	 * one-brand supplier, exactly as before. {@code companyIds} wins when both arrive.
	 */
	private Long companyId;

	/** Display only — "Nokia, Samsung". Never parsed; the ids above are what round-trips. */
	private String companyNames;
	@ValidMobileNumber
	private String mobile;

	/** B2B P1 (#9, supplier side): most WE are willing to owe this vendor. Blank/null = no limit. */
	private java.math.BigDecimal creditLimit;
	private String phone;
	private String address;
	@ValidEmail
	private String email;
	private String description;
	// F1 (AP): running payable owed to this vendor (for the vendor table's Due column + Pay Vendor).
	private java.math.BigDecimal dueAmount;
	private Long partyId;               // P1: shared party/contact master id
	private String datedStr;
	private String updatedStr;

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "OwnerDTO [id=" + id + ", name=" + name + ", description=" + description + ", email=" + email
				+ ", mobile=" + mobile + ", phone=" + phone + ", address=" + address + ", datedStr=" + datedStr + "]";
	}

}