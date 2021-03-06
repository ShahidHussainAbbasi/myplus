package com.web.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.persistence.model.Appointment;
import com.persistence.model.Doctor;
import com.persistence.model.Hospital;
import com.validation.PasswordMatches;
import com.validation.ValidEmail;
import com.validation.ValidMobileNumber;
import com.validation.ValidPassword;

@PasswordMatches
public class DoctorDTO {
	
	private int doctorId;
	@NotNull
	private String address;
	@NotNull
	private String availabe;
	@NotNull
	private String datetime;
	@NotNull
	@ValidEmail
	private String email;
	@NotNull
	@ValidMobileNumber
	private String mobile;
	@NotNull
	private String name;
//	@NotNull
	private String speciality;

	private String timeIn;

	private String timeOut;

	private String dayFrom;
	
	private String dayTo;
	
	@Column(name="appointment_offer_type")
	private String appointmentOfferType;
	
	@Column(name="appointment_offer_value")
	private Short appointmentOfferValue;

	//bi-directional many-to-one association to Appointment
	private List<Appointment> appointments;
	private Appointment appointment;
	private Long hospitalId;
	
	public Long getHospitalId() {
		return hospitalId;
	}

	public void setHospitalId(Long hospitalId) {
		this.hospitalId = hospitalId;
	}

	public int getDoctorId() {
		return doctorId;
	}

	public void setDoctorId(int doctorId) {
		this.doctorId = doctorId;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getAvailabe() {
		return availabe;
	}

	public void setAvailabe(String availabe) {
		this.availabe = availabe;
	}

	public String getDatetime() {
		return datetime;
	}

	public void setDatetime(String datetime) {
		this.datetime = datetime;
	}

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

	public String getSpeciality() {
		return speciality;
	}

	public void setSpeciality(String speciality) {
		this.speciality = speciality;
	}

	public String getTimeIn() {
		return timeIn;
	}

	public void setTimeIn(String timeIn) {
		this.timeIn = timeIn;
	}

	public String getTimeOut() {
		return timeOut;
	}

	public void setTimeOut(String timeOut) {
		this.timeOut = timeOut;
	}

	public List<Appointment> getAppointments() {
		return appointments;
	}

	public void setAppointments(List<Appointment> appointments) {
		this.appointments = appointments;
	}

	public Appointment getAppointment() {
		return appointment;
	}

	public void setAppointment(Appointment appointment) {
		this.appointment = appointment;
	}
/*
	public Map<Long, String> getHospitals() {
		return hospitals;
	}

	public void setHospitals(Map<Long, String> hospitals) {
		this.hospitals = hospitals;
	}
*/
	public String getDayFrom() {
		return dayFrom;
	}

	public void setDayFrom(String dayFrom) {
		this.dayFrom = dayFrom;
	}

	public String getDayTo() {
		return dayTo;
	}

	public void setDayTo(String dayTo) {
		this.dayTo = dayTo;
	}

	public String getAppointmentOfferType() {
		return appointmentOfferType;
	}

	public void setAppointmentOfferType(String appointmentOfferType) {
		this.appointmentOfferType = appointmentOfferType;
	}

	public Short getAppointmentOfferValue() {
		return appointmentOfferValue;
	}

	public void setAppointmentOfferValue(Short appointmentOfferValue) {
		this.appointmentOfferValue = appointmentOfferValue;
	}	
	
}
