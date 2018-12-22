package com.web.dto;

import javax.validation.constraints.NotNull;

import com.validation.ValidMobileNumber;
import com.validation.ValidateEmpty;

public class AppointmentDTO extends BaseDOTO{
	
	@ValidateEmpty
	private Long hospitalId;
	@ValidateEmpty
	private Long doctorId;
	@ValidateEmpty
	private String name;
	@ValidMobileNumber
	private String mobile;
//	@NotNull
//	@ValidEmail
	private String email;
	@NotNull
	private String address;
//	@NotNull
//	private String datetime;
	
	private Integer appntmntNo;
	
	public Long getHospitalId() {
		return hospitalId;
	}

	public void setHospitalId(Long hospitalId) {
		this.hospitalId = hospitalId;
	}

	public Long getDoctorId() {
		return doctorId;
	}

	public void setDoctorId(Long doctorId) {
		this.doctorId = doctorId;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

//	public String getDatetime() {
//		return datetime;
//	}
//
//	public void setDatetime(String datetime) {
//		this.datetime = datetime;
//	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getMobile() {
		return mobile;
	}

	public void setMobile(String mobile) {
		this.mobile = mobile;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Integer getAppntmntNo() {
		return appntmntNo;
	}

	public void setAppntmntNo(Integer appntmntNo) {
		this.appntmntNo = appntmntNo;
	}

}
