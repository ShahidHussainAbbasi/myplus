package com.web.dto.education;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.Set;

import com.validation.ValidEmail;
import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

import lombok.Data;

@Data
public class StaffDTO implements Serializable {
	private static final long serialVersionUID = 1L;

	private Long id;

	@ValidateEmpty
	private String name;

	private String code;

	private Long userId;

	private String userType;

	@ValidEmail
	private String email;

	@ValidMobileNumber
	private String mobile;

	private String phone;

	@ValidateEmpty
	private String address;

	private String designation;

	private String staffDOB;

	private String gender;

	private String timeInStr;

	private String timeOutStr;

	private LocalTime timeIn;

	private LocalTime timeOut;

	private String qualification;

	private String martialStatus = "Single";

	private String status = "active";

	private String datedStr;

	private String updatedStr;

	private Set<Long> schoolIds;

//	@ValidateEmpty
	private Set<Long> gradeIds;

//	@ValidateEmpty
	private Set<Long> subjectIds;

	private Set<String> schoolNames;

	private Set<String> gradeNames;

//	private Set<String> subjectNames;



	/**
	 * @return the serialversionuid
	 */
	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}