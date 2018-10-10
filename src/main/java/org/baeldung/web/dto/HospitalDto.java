package org.baeldung.web.dto;

import java.util.List;

import javax.persistence.Column;
import javax.persistence.OneToMany;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.baeldung.persistence.model.Appointment;
import org.baeldung.persistence.model.Doctor;
import org.baeldung.validation.PasswordMatches;
import org.baeldung.validation.ValidEmail;
import org.baeldung.validation.ValidPassword;

@PasswordMatches
public class HospitalDto {
	
	private int hospitalId;
    @NotNull
	private String city;
    @NotNull
	private String country;
    @NotNull
	private String datetime;
    @ValidEmail
    @NotNull
	private String email;
    @NotNull
	private String logoUrl;
    @NotNull
	private String name;
    @NotNull
	private String phone;
    @NotNull
	private String state;

    private String zip;
    @NotNull
    private String appointmentOfferType;
    
	@NotNull
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

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
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

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getZip() {
		return zip;
	}

	public void setZip(String zip) {
		this.zip = zip;
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

	@Override
	public String toString() {
		return "HospitalDto [hospitalId=" + hospitalId + ", city=" + city + ", country=" + country + ", datetime="
				+ datetime + ", email=" + email + ", logoUrl=" + logoUrl + ", name=" + name + ", phone=" + phone
				+ ", state=" + state + ", zip=" + zip + " ,appointmentOfferType="+appointmentOfferType + ", userId=" + userId + ", appointment=" + appointment
				+ ", appointments=" + appointments + ", doctor=" + doctor + ", doctors=" + doctors + "]";
	}
	

	
}
