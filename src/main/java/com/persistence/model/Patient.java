package com.persistence.model;

import java.io.Serializable;
import javax.persistence.*;
import java.util.List;


/**
 * The persistent class for the patient database table.
 * 
 */
@Entity
@Table(
        name = "patient",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "mobile"),
                @UniqueConstraint(columnNames = "cnic"),
        }
)
public class Patient implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	@Column(name="patient_id")
	private int patientId;

	private String address;

	private String email;

	private String name;

	private String mobile;

	private String cnic;

	private boolean blocked;
	
	//bi-directional many-to-one association to Appointment
	@OneToMany(mappedBy="patient")
	private List<Appointment> appointments;

	public Patient() {
	}

	public int getPatientId() {
		return this.patientId;
	}

	public void setPatientId(int patientId) {
		this.patientId = patientId;
	}

	public String getAddress() {
		return this.address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getEmail() {
		return this.email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMobile() {
		return mobile;
	}

	public void setMobile(String mobile) {
		this.mobile = mobile;
	}

	public List<Appointment> getAppointments() {
		return this.appointments;
	}

	public void setAppointments(List<Appointment> appointments) {
		this.appointments = appointments;
	}

	public Appointment addAppointment(Appointment appointment) {
		getAppointments().add(appointment);
		appointment.setPatient(this);

		return appointment;
	}

	public Appointment removeAppointment(Appointment appointment) {
		getAppointments().remove(appointment);
		appointment.setPatient(null);

		return appointment;
	}

	public String getCnic() {
		return cnic;
	}

	public void setCnic(String cnic) {
		this.cnic = cnic;
	}

	public boolean isBlocked() {
		return blocked;
	}

	public void setBlocked(boolean blocked) {
		this.blocked = blocked;
	}

	
}