package org.baeldung.service;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import org.baeldung.persistence.dao.DoctorRepository;
import org.baeldung.persistence.dao.HospitalRepository;
import org.baeldung.persistence.dao.PasswordResetTokenRepository;
import org.baeldung.persistence.dao.RoleRepository;
import org.baeldung.persistence.dao.VerificationTokenRepository;
import org.baeldung.persistence.model.Doctor;
import org.baeldung.persistence.model.Hospital;
import org.baeldung.web.dto.DoctorDTO;
import org.baeldung.web.dto.HospitalDto;
import org.baeldung.web.util.AppUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class DoctorService implements IDoctorService {

    @Autowired
    private DoctorRepository repository;

    @Autowired
    UserService userService;

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";

    // API

    @Override
    public Doctor registerNewDoctor(final DoctorDTO hospitalDto) throws Exception{
        final Doctor doctor = new Doctor();
        
       	Hospital hospital = new Hospital();
       	hospital.setHospitalId(hospitalDto.getHospitalId());
        doctor.setHospital(hospital);

       	doctor.setName(hospitalDto.getName());
        doctor.setMobile(hospitalDto.getMobile());
        doctor.setEmail(hospitalDto.getEmail());
        doctor.setAddress(hospitalDto.getAddress());
       	doctor.setAvailabe(hospitalDto.getAvailabe());
        doctor.setSpeciality(hospitalDto.getSpeciality());
        doctor.setTimeIn(hospitalDto.getTimeIn());
        doctor.setTimeOut(hospitalDto.getTimeOut());
        
        doctor.setDatetime(AppUtil.todayDateStr());

        return repository.save(doctor);
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
	public boolean isExist(String mobile) throws Exception {
		return repository.findByMobile(mobile).isPresent();
	}

	@Override
	public Optional<DoctorDTO> fineByID(long id) throws Exception {
		// TODO Auto-generated method stub
		return null;
	}


}