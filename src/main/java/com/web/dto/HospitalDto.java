package com.web.dto;

import java.util.List;

import javax.validation.constraints.NotNull;

import com.persistence.model.Appointment;
import com.persistence.model.Doctor;
import com.validation.ValidEmail;

public class HospitalDto{
	
	private int hospitalId;
    @NotNull
	private String name;
    @NotNull
	private String phone;
    @ValidEmail
    @NotNull
	private String email;
//    @NotNull
	private String datetime;
    @NotNull
	private String countryCode;
    @NotNull
    private String state;    
	@NotNull
    private String geoId;
//    @NotNull
	private String logoUrl;

    @NotNull
    private String appointmentOfferType;
    
    @NotNull
    private Short appointmentOfferValue;
    
    @NotNull
    private short hours;
    
	private java.math.BigInteger userId;

	private Appointment appointment;

	private List<Appointment> appointments;

	private Doctor doctor;
	
	private List<Doctor> doctors;

	public int getHospitalId() {
		return hospitalId;
	}

	public void setHospitalId(int hospitalId) {
		this.hospitalId = hospitalId;
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

	public String getLogoUrl() {
		return logoUrl;
	}

	public void setLogoUrl(String logoUrl) {
		this.logoUrl = logoUrl;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

    public String getAppointmentOfferType() {
		return appointmentOfferType;
	}

	public void setAppointmentOfferType(String appointmentOfferType) {
		this.appointmentOfferType = appointmentOfferType;
	}

	public java.math.BigInteger getUserId() {
		return userId;
	}

	public void setUserId(java.math.BigInteger userId) {
		this.userId = userId;
	}

	public Appointment getAppointment() {
		return appointment;
	}

	public void setAppointment(Appointment appointment) {
		this.appointment = appointment;
	}

	public List<Appointment> getAppointments() {
		return appointments;
	}

	public void setAppointments(List<Appointment> appointments) {
		this.appointments = appointments;
	}

	public Doctor getDoctor() {
		return doctor;
	}

	public void setDoctor(Doctor doctor) {
		this.doctor = doctor;
	}

	public List<Doctor> getDoctors() {
		return doctors;
	}

	public void setDoctors(List<Doctor> doctors) {
		this.doctors = doctors;
	}

	
	public Short getAppointmentOfferValue() {
		return appointmentOfferValue;
	}

	public void setAppointmentOfferValue(Short appointmentOfferValue) {
		this.appointmentOfferValue = appointmentOfferValue;
	}

	public short getHours() {
		return hours;
	}

	public void setHours(short hours) {
		this.hours = hours;
	}

	
	public String getCountryCode() {
		return countryCode;
	}

	public void setCountryCode(String countryCode) {
		this.countryCode = countryCode;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getGeoId() {
		return geoId;
	}

	public void setGeoId(String geoId) {
		this.geoId = geoId;
	}

	@Override
	public String toString() {
		return "HospitalDto [hospitalId=" + hospitalId + ", geoId=" + geoId+ ", countryCode=" + countryCode + ", datetime="
				+ datetime + ", email=" + email + ", logoUrl=" + logoUrl + ", name=" + name + ", phone=" + phone
				+ ", appointmentOfferType="+appointmentOfferType + ", userId=" + userId + ", appointment=" + appointment
				+ ", appointments=" + appointments + ", doctor=" + doctor + ", doctors=" + doctors + "]";
	}
	

	
}
