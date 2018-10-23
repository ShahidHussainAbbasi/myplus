package com.service;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;

import com.persistence.dao.DoctorRepository;
import com.persistence.model.Doctor;
import com.persistence.model.Hospital;
import com.web.dto.DoctorDTO;
import com.web.util.AppUtil;

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
        if(hospitalDto.getDayFrom().equals("All") || hospitalDto.getDayTo().equals("All")) {
            doctor.setDayFrom("All");
            doctor.setDayTo("All");
        }
        doctor.setDayFrom(hospitalDto.getDayFrom());
        doctor.setDayTo(hospitalDto.getDayTo());
        doctor.setAppointmentOfferType(hospitalDto.getAppointmentOfferType());
        doctor.setAppointmentOfferValue(hospitalDto.getAppointmentOfferValue());
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
	public Optional<Doctor> fineByID(Long id) throws Exception {
		// TODO Auto-generated method stub
		return repository.findById(id);
	}


	@Override
	public List<Doctor> findByHospitalId(Long hospitalId) {
		Doctor doctor = new Doctor();
		Hospital hospital = new Hospital();
		hospital.setHospitalId(hospitalId);
		doctor.setHospital(hospital);
		return repository.findAll(Example.of(doctor));
		// TODO Auto-generated method stub
	}


}