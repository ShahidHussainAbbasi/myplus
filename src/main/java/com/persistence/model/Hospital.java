package com.persistence.model;
import java.io.Serializable;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;


/**
 * The persistent class for the hospital database table.
 * 
 */
@Entity

@Table(
        name = "hospital",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "hospital_id"),
                @UniqueConstraint(columnNames = "name")
        }
)
public class Hospital implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	@Column(name="hospital_id")
	private Long hospitalId;

/*	
	private String country;

	private String state;

	private String city;

	private String zip;

*/	private String datetime;

	private String email;

	@Column(name="logo_url")
	private String logoUrl;

	private String name;

	private String phone;


	@JoinColumn(name="geo_id")
	@Column(name="geo_id")
	private java.math.BigInteger geoId;
	

	@JoinColumn(name="user_id")
	@Column(name="user_id")
	private java.math.BigInteger userId;

	//bi-directional many-to-one association to Appointment
	@OneToMany(mappedBy="hospital")
	private List<Appointment> appointments;

	//bi-directional many-to-one association to Doctor
	@OneToMany(mappedBy="hospital")
	private List<Doctor> doctors;

	public Hospital() {
	}

	public Long getHospitalId() {
		return this.hospitalId;
	}

	public void setHospitalId(Long hospitalId) {
		this.hospitalId = hospitalId;
	}

	public String getDatetime() {
		return this.datetime;
	}

	public void setDatetime(String datetime) {
		this.datetime = datetime;
	}

	public String getEmail() {
		return this.email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getLogoUrl() {
		return this.logoUrl;
	}

	public void setLogoUrl(String logoUrl) {
		this.logoUrl = logoUrl;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPhone() {
		return this.phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public java.math.BigInteger getUserId() {
		return this.userId;
	}

	public void setUserId(java.math.BigInteger userId) {
		this.userId = userId;
	}

	public java.math.BigInteger getGeoId() {
		return geoId;
	}

	public void setGeoId(java.math.BigInteger geoId) {
		this.geoId = geoId;
	}
/*	public String getAppointmentOfferType() {
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
*/
	public List<Appointment> getAppointments() {
		return this.appointments;
	}

	public void setAppointments(List<Appointment> appointments) {
		this.appointments = appointments;
	}

	public Appointment addAppointment(Appointment appointment) {
		getAppointments().add(appointment);
		appointment.setHospital(this);

		return appointment;
	}

	public Appointment removeAppointment(Appointment appointment) {
		getAppointments().remove(appointment);
		appointment.setHospital(null);

		return appointment;
	}

	public List<Doctor> getDoctors() {
		return this.doctors;
	}

	public void setDoctors(List<Doctor> doctors) {
		this.doctors = doctors;
	}

	public Doctor addDoctor(Doctor doctor) {
		getDoctors().add(doctor);
		doctor.setHospital(this);

		return doctor;
	}

	public Doctor removeDoctor(Doctor doctor) {
		getDoctors().remove(doctor);
		doctor.setHospital(null);

		return doctor;
	}

}