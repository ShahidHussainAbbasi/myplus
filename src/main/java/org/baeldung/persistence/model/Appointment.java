package org.baeldung.persistence.model;

import java.io.Serializable;
import javax.persistence.*;


/**
 * The persistent class for the appointment database table.
 * 
 */
@Entity
@NamedQuery(name="Appointment.findAll", query="SELECT a FROM Appointment a")
public class Appointment implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	@Column(name="appointment_id")
	private int appointmentId;

	@Column(name="appointment_fee")
	private String appointmentFee;

	@Column(name="appointment_type")
	private String appointmentType;

	@Column(name="date_time")
	private String dateTime;

	//bi-directional many-to-one association to Doctor
	@ManyToOne
	@JoinColumn(name="FK_doctor_id")
	private Doctor doctor;

	//bi-directional many-to-one association to Hospital
	@ManyToOne
	@JoinColumn(name="FK_hospital_id")
	private Hospital hospital;

	//bi-directional many-to-one association to Patient
	@ManyToOne
	@JoinColumn(name="FK_patient_id")
	private Patient patient;

	public Appointment() {
	}

	public int getAppointmentId() {
		return this.appointmentId;
	}

	public void setAppointmentId(int appointmentId) {
		this.appointmentId = appointmentId;
	}

	public String getAppointmentFee() {
		return this.appointmentFee;
	}

	public void setAppointmentFee(String appointmentFee) {
		this.appointmentFee = appointmentFee;
	}

	public String getAppointmentType() {
		return this.appointmentType;
	}

	public void setAppointmentType(String appointmentType) {
		this.appointmentType = appointmentType;
	}

	public String getDateTime() {
		return this.dateTime;
	}

	public void setDateTime(String dateTime) {
		this.dateTime = dateTime;
	}

	public Doctor getDoctor() {
		return this.doctor;
	}

	public void setDoctor(Doctor doctor) {
		this.doctor = doctor;
	}

	public Hospital getHospital() {
		return this.hospital;
	}

	public void setHospital(Hospital hospital) {
		this.hospital = hospital;
	}

	public Patient getPatient() {
		return this.patient;
	}

	public void setPatient(Patient patient) {
		this.patient = patient;
	}

}