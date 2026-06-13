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
	private Long companyId;
	private String companyName;
	@ValidMobileNumber
	private String mobile;
	private String phone;
	private String address;
	@ValidEmail
	private String email;
	private String description;
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