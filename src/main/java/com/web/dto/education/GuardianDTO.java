package com.web.dto.education;

import java.io.Serializable;

import com.validation.ValidEmail;
import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

import lombok.Data;

@Data
public class GuardianDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	@ValidateEmpty
	private String name;

	private Long userId;

	private String userType;

	@ValidEmail
	private String email;

	@ValidMobileNumber
	private String mobile;

	private String phone;

	private String tempAddress;

	private String permAddress;

	@ValidateEmpty
	private String relation;

	private String occupation;

//	private List<String> students;

	private String datedStr;

	private String updatedStr;

	private String status;

	private String cnic;



	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}