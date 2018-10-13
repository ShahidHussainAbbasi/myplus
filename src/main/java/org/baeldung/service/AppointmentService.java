package org.baeldung.service;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import org.baeldung.persistence.dao.AppoinmentRepository;
import org.baeldung.persistence.dao.DoctorRepository;
import org.baeldung.persistence.dao.HospitalRepository;
import org.baeldung.persistence.dao.PasswordResetTokenRepository;
import org.baeldung.persistence.dao.PatientRepository;
import org.baeldung.persistence.dao.RoleRepository;
import org.baeldung.persistence.dao.VerificationTokenRepository;
import org.baeldung.persistence.model.Appointment;
import org.baeldung.persistence.model.Doctor;
import org.baeldung.persistence.model.Hospital;
import org.baeldung.persistence.model.Patient;
import org.baeldung.web.dto.AppointmentDTO;
import org.baeldung.web.dto.DoctorDTO;
import org.baeldung.web.dto.HospitalDto;
import org.baeldung.web.util.AppUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class AppointmentService implements IAppointmentService {

    @Autowired
    private AppoinmentRepository appoinmentRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    UserService userService;

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";

    // API

    @Override
    public AppointmentDTO registerNewAppointment(final AppointmentDTO appointmentDTO) throws Exception{
    	final Patient patient = new Patient();
    	
    	patient.setName(appointmentDTO.getName());
    	patient.setAddress(appointmentDTO.getAddress());
    	patient.setAppointments(null);
    	patient.setCnic(null);
    	patient.setEmail(appointmentDTO.getEmail());
    	patient.setPhone(appointmentDTO.getMobile());
    	patientRepository.save(patient);
    	
        final Appointment appointment = new Appointment();
        
        appointment.setPatient(patient);
        
       	Hospital hospital = new Hospital();
       	hospital.setHospitalId(appointmentDTO.getHospitalId());
        appointment.setHospital(hospital);

       	Doctor doctor = new Doctor();
       	doctor.setDoctorId(appointmentDTO.getDoctorId().intValue());
       	appointment.setDoctor(doctor);

       	appointment.setAppointmentFee(null);
       	appointment.setAppointmentType(null);
       	
        appointment.setDateTime(AppUtil.todayDateStr());

        appoinmentRepository.save(appointment);
        
        return appointmentDTO;
    }


	@Override
	public void saveDoctor(DoctorDTO doctorDTO) throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteDoctor(DoctorDTO doctorDTO) throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Optional<DoctorDTO> fineByID(long id) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public boolean isBlocked(String mobile) throws Exception {
		return (patientRepository.findByPhone(mobile) != null && patientRepository.findByPhone(mobile).isBlocked()) ?true:false;
	}


}